package serialport;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * 串列埠操作工具，封裝 jSerialComm 的連線、設定套用、同步 IO、非同步 IO、斷線監控與被動健康狀態。
 *
 * <p>此類別維護目前作用中的連接埠與設定，呼叫端可先設定 {@link SerialConfig}，
 * 再進行連線與資料讀寫。物件關閉後不可再次使用。</p>
 *
 * <p>此類別只負責底層 serial port 的操作與狀態清理，不直接操作 UI。
 * 若需要在斷線時更新畫面、停止輪詢或觸發重連，應透過 {@link #setDisconnectHandler(Consumer)}
 * 由上層 Coordinator 或 Service 處理。</p>
 */
public class SerialTool implements AutoCloseable {

    // =========================
    // 1. 預設參數
    // =========================

    private static final String DEFAULT_PORT_NAME = "COM3";
    private static final int DEFAULT_BAUD_RATE = 9600;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int DEFAULT_PARITY = SerialPort.NO_PARITY;
    private static final int DEFAULT_TIMEOUT_MODE =
            SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING;
    private static final int DEFAULT_READ_TIMEOUT = 100;
    private static final int DEFAULT_WRITE_TIMEOUT = 100;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    // =========================
    // 2. 設定狀態
    // =========================

    /**
     * 目前已套用、會用於建立串列埠連線的設定。
     */
    @Getter
    private volatile SerialConfig activeConfig;

    /**
     * 等待套用的串列埠設定。
     */
    @Getter
    private volatile SerialConfig pendingConfig;

    /**
     * 是否曾由外部明確提供設定。
     *
     * <p>此旗標用來避免程式在尚未由外部指定連接埠時，直接使用預設 COM port 誤連。</p>
     */
    private boolean externalConfigProvided = false;

    /**
     * 外部提供的設定是否已套用到 activeConfig。
     */
    private boolean configApplied = false;

    // =========================
    // 3. 連線生命週期狀態
    // =========================

    /**
     * 目前被選取並可能已開啟的串列埠物件。
     *
     * <p>注意：此物件存在不代表實體設備一定仍然連接。
     * USB-to-Serial 被拔除時，底層狀態可能需要透過斷線事件或讀寫失敗來更新。</p>
     */
    private volatile SerialPort currentPort;

    /**
     * 目前連線的世代編號。
     *
     * <p>每次成功開啟新連線後遞增，用來忽略舊連線延遲送達的斷線事件。</p>
     */
    private long connectionGeneration = 0;

    /**
     * 保護連線生命週期與底層 SerialPort 參考的讀寫鎖。
     *
     * <p>同步與非同步 IO 取得 read lock，因此讀取與寫入可同時進行。
     * 連線、斷線、關閉與實體斷線清理取得 write lock，避免 IO 期間替換或關閉 currentPort。</p>
     */
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    // =========================
    // 4. 讀寫與關閉狀態
    // =========================

    /**
     * 是否已有讀取作業正在進行。
     *
     * <p>用來避免同步 read 與非同步 read 同時操作同一個 serial port。</p>
     */
    private final AtomicBoolean readBusy = new AtomicBoolean(false);

    /**
     * 是否已有寫入作業正在進行。
     *
     * <p>用來避免同步 write 與非同步 write 同時操作同一個 serial port。</p>
     */
    private final AtomicBoolean writeBusy = new AtomicBoolean(false);

    /**
     * SerialTool 是否已被關閉。
     *
     * <p>用 AtomicBoolean 確保 close() 重複呼叫時只會真正釋放資源一次。</p>
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 斷線事件是否已被處理。
     *
     * <p>實體斷線時，可能同時發生多種觸發來源，例如：</p>
     * <ul>
     *     <li>jSerialComm 的 PORT_DISCONNECTED 事件</li>
     *     <li>readBytes() 回傳錯誤</li>
     *     <li>writeBytes() 回傳錯誤</li>
     * </ul>
     *
     * <p>使用 AtomicBoolean 可避免同一次斷線被重複處理，造成重複 close、
     * 重複通知 UI 或狀態被多次修改。</p>
     */
    private final AtomicBoolean disconnectHandled = new AtomicBoolean(false);

    // =========================
    // 5. 被動健康狀態
    // =========================

    /**
     * 目前被動推導出的通訊健康狀態。
     */
    private volatile SerialHealthStatus healthStatus = SerialHealthStatus.DISCONNECTED;

    /**
     * 最近一次成功完成底層 IO 或上層回報通訊成功的時間。
     */
    private volatile long lastSuccessfulIoTime = 0L;

    /**
     * 最近一次底層 IO 失敗或上層回報通訊失敗的時間。
     */
    private volatile long lastFailedIoTime = 0L;

    /**
     * 最近一次通訊失敗原因。
     */
    private volatile String lastFailureReason = "";

    /**
     * 連續通訊失敗次數。
     */
    private int consecutiveFailureCount = 0;

    /**
     * 連續失敗達到此門檻後，健康狀態會進入 COMMUNICATION_BAD。
     */
    private int failureThreshold = DEFAULT_FAILURE_THRESHOLD;

    // =========================
    // 6. 外部事件回呼與非同步執行器
    // =========================

    /**
     * 串列埠斷線時要通知外部的回呼函式。
     *
     * <p>SerialTool 只負責偵測與清理底層串列埠資源，
     * 不直接操作 UI 或上層流程。外部可透過此 handler 接收斷線原因，
     * 再自行更新畫面、停止輪詢或觸發重連流程。</p>
     *
     * <p>預設為空操作，避免未設定 handler 時發生 NullPointerException。</p>
     */
    private volatile Consumer<String> disconnectHandler = reason -> {};

    /**
     * 執行非同步讀寫工作的執行緒池。
     *
     * <p>目前固定為 2 條執行緒，分別支援讀取與寫入工作排程。</p>
     */
    private final ExecutorService executorService;

    // =========================
    // 7. 建構與預設設定
    // =========================

    /**
     * 建立串列埠工具並載入預設設定。
     */
    public SerialTool() {
        this.executorService = Executors.newFixedThreadPool(2);
        setDefaultConfig();
    }

    /**
     * 載入預設設定並清除目前狀態。
     *
     * <p>預設設定只作為初始值，不代表可以直接連線。
     * 呼叫端仍需透過 {@link #setPendingConfig(SerialConfig)} 與 {@link #applyConfig()}
     * 明確提供並套用設定後，才會進入可連線狀態。</p>
     */
    private void setDefaultConfig() {
        SerialConfig defaultConfig = createDefaultConfig();

        this.activeConfig = defaultConfig;
        this.pendingConfig = defaultConfig;
        this.currentPort = null;
        this.externalConfigProvided = false;
        this.configApplied = false;
        resetHealthStateLocked();
    }

    /**
     * 建立預設串列埠設定。
     *
     * @return 預設串列埠設定。
     */
    private SerialConfig createDefaultConfig() {
        return new SerialConfig(
                DEFAULT_PORT_NAME,
                DEFAULT_BAUD_RATE,
                DEFAULT_DATA_BITS,
                DEFAULT_STOP_BITS,
                DEFAULT_PARITY,
                DEFAULT_TIMEOUT_MODE,
                DEFAULT_READ_TIMEOUT,
                DEFAULT_WRITE_TIMEOUT
        );
    }

    // =========================
    // 8. 設定套用流程
    // =========================

    /**
     * 設定等待套用的串列埠參數。
     *
     * @param config 待套用的串列埠設定。
     * @throws IllegalStateException 當工具已關閉時拋出。
     * @throws IllegalArgumentException 當設定為 null 時拋出。
     */
    public void setPendingConfig(SerialConfig config) {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();

            if (config == null) {
                throw new IllegalArgumentException("config cannot be null");
            }

            this.pendingConfig = config;
            this.externalConfigProvided = true;
            this.configApplied = false;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 將等待套用的設定設為目前作用中的設定。
     *
     * @throws IllegalStateException 當工具已關閉，或沒有待套用設定時拋出。
     */
    public void applyConfig() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            applyConfigLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void applyConfigLocked() {
        if (pendingConfig == null) {
            throw new IllegalStateException("pendingConfig is null");
        }
        this.activeConfig = pendingConfig;
        this.configApplied = true;
    }

    // =========================
    // 9. 連線與斷線流程
    // =========================

    /**
     * 使用目前作用中的設定連接串列埠。
     *
     * <p>連線成功後會綁定 jSerialComm 的實體斷線監聽器，
     * 讓 SerialTool 能在 USB-to-Serial 裝置被拔除或 COM port 消失時清理狀態。</p>
     *
     * @return 連線成功時回傳 true；設定尚未就緒、找不到連接埠或開啟失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean connect() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            return connectLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private boolean connectLocked() {
        if (activeConfig == null) {
            return false;
        }

        if (!isConfigReadyLocked()) {
            return false;
        }

        if (isConnectedLocked() && !closeCurrentPort()) {
            return false;
        }

        boolean prepared = preparePortFromActiveConfig();

        if (!prepared) {
            return false;
        }

        SerialPort port = currentPort;
        boolean opened = port.openPort();

        if (!opened) {
            currentPort = null;
            return false;
        }

        // 每次成功建立新連線後，重置斷線處理旗標。
        // 這樣下一次實體斷線時，handlePortDisconnected() 才會再次生效。
        disconnectHandled.set(false);
        long generation = ++connectionGeneration;
        markConnectedLocked();

        // 綁定 jSerialComm 的實體斷線監聽器。
        // 此監聽器主要用來偵測 USB-to-Serial 裝置被拔除、COM port 消失等事件。
        bindDisconnectListener(port, generation);

        return true;
    }

    /**
     * 關閉目前串列埠連線。
     *
     * <p>這是使用者或上層流程主動斷線，不會呼叫 disconnectHandler。
     * disconnectHandler 只用於非預期斷線，例如實體拔除或底層讀寫失敗。</p>
     *
     * @return 沒有連線、連接埠已非開啟狀態或成功關閉時回傳 true；底層關閉失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean disconnect() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            return closeCurrentPort();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 設定串列埠斷線時的外部通知處理器。
     *
     * <p>當 SerialTool 偵測到實體連接埠中斷，或讀寫過程發生底層錯誤時，
     * 會呼叫此 handler，並傳入斷線原因。</p>
     *
     * <p>此方法通常由上層 Coordinator、Service 或 UI 綁定，用來執行：</p>
     * <ul>
     *     <li>更新連線狀態顯示</li>
     *     <li>停止設備狀態輪詢</li>
     *     <li>提示使用者設備已斷線</li>
     *     <li>啟動重新連線流程</li>
     * </ul>
     *
     * @param disconnectHandler 斷線時要執行的回呼函式；若為 null，則改為空操作。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public void setDisconnectHandler(Consumer<String> disconnectHandler) {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();

            if (disconnectHandler == null) {
                this.disconnectHandler = reason -> {};
                return;
            }

            this.disconnectHandler = disconnectHandler;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 關閉目前持有的串列埠並清除本物件內部狀態。
     *
     * <p>此方法用於主動斷線、重新連線前的清理，以及 close() 釋放資源。
     * 關閉前會先移除 data listener，避免舊的 SerialPort 物件在釋放後仍觸發 callback。</p>
     *
     * <p>此方法只負責資源清理，不會呼叫 disconnectHandler。
     * 因為使用者主動 disconnect 不等同於異常斷線。</p>
     *
     * @return 沒有連接埠、連接埠已關閉或成功關閉時回傳 true；底層 closePort() 失敗時回傳 false。
     */
    private boolean closeCurrentPort() {
        if (currentPort == null) {
            markDisconnectedLocked(null);
            return true;
        }

        SerialPort port = currentPort;

        port.removeDataListener();

        if (!port.isOpen()) {
            currentPort = null;
            markDisconnectedLocked(null);
            return true;
        }

        boolean closedSuccessfully = port.closePort();

        if (closedSuccessfully) {
            currentPort = null;
            markDisconnectedLocked(null);
        }

        return closedSuccessfully;
    }

    /**
     * 重新連接目前設定指定的串列埠。
     *
     * @return 成功斷線並重新連線時回傳 true；任一步驟失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean reconnect() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();

            if (!closeCurrentPort()) {
                return false;
            }

            return connectLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 套用待生效設定後重新連接串列埠。
     *
     * @return 重新連線成功時回傳 true；套用或連線流程失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉，或沒有待套用設定時拋出。
     */
    public boolean applyAndReconnect() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            applyConfigLocked();

            if (!closeCurrentPort()) {
                return false;
            }

            return connectLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    // =========================
    // 10. 狀態查詢
    // =========================

    /**
     * 判斷目前是否已有開啟中的串列埠。
     *
     * <p>注意：此方法主要代表 SerialTool 目前是否握有一個已開啟的 SerialPort。
     * 若 USB-to-Serial 發生特殊斷線狀況，isOpen() 不一定能立即反映實體狀態，
     * 因此仍需搭配斷線事件、讀寫失敗與應用層 heartbeat 判斷。</p>
     *
     * @return 串列埠存在且已開啟時回傳 true。
     */
    public boolean isConnected() {
        lifecycleLock.readLock().lock();

        try {
            return isConnectedLocked();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private boolean isConnectedLocked() {
        return currentPort != null && currentPort.isOpen();
    }

    /**
     * 判斷外部設定是否已提供並進入可連線狀態。
     *
     * @return 外部設定已提供且已標記套用時回傳 true。
     */
    public boolean isConfigReady() {
        lifecycleLock.readLock().lock();

        try {
            return isConfigReadyLocked();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private boolean isConfigReadyLocked() {
        return externalConfigProvided && configApplied;
    }

    /**
     * 判斷此工具是否已關閉。
     *
     * @return 已關閉時回傳 true。
     */
    public boolean isClosed() {
        return closed.get();
    }

    // =========================
    // 11. 被動健康狀態 API
    // =========================

    /**
     * 取得目前被動推導出的通訊健康狀態。
     *
     * @return 目前健康狀態。
     */
    public SerialHealthStatus getHealthStatus() {
        lifecycleLock.readLock().lock();

        try {
            return healthStatus;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 取得最近一次成功通訊的時間。
     *
     * @return epoch milliseconds；尚未成功通訊時回傳 0。
     */
    public long getLastSuccessfulIoTime() {
        return lastSuccessfulIoTime;
    }

    /**
     * 取得最近一次失敗通訊的時間。
     *
     * @return epoch milliseconds；尚未失敗通訊時回傳 0。
     */
    public long getLastFailedIoTime() {
        return lastFailedIoTime;
    }

    /**
     * 取得最近一次通訊失敗原因。
     *
     * @return 最近一次失敗原因；尚未失敗時回傳空字串。
     */
    public String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * 取得目前連續通訊失敗次數。
     *
     * @return 連續失敗次數。
     */
    public int getConsecutiveFailureCount() {
        lifecycleLock.readLock().lock();

        try {
            return consecutiveFailureCount;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 取得連續失敗門檻。
     *
     * @return 連續失敗達到此數值後，健康狀態會進入 COMMUNICATION_BAD。
     */
    public int getFailureThreshold() {
        lifecycleLock.readLock().lock();

        try {
            return failureThreshold;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 設定連續通訊失敗門檻。
     *
     * @param failureThreshold 失敗門檻，必須大於 0。
     * @throws IllegalArgumentException 當門檻小於等於 0 時拋出。
     */
    public void setFailureThreshold(int failureThreshold) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be greater than 0");
        }

        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            this.failureThreshold = failureThreshold;
            refreshHealthStatusLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 由上層回報一次有效通訊成功。
     *
     * <p>當上層完成「送指令、收到並解析有效回應」時，可呼叫此方法重置連續失敗次數。</p>
     */
    public void recordCommunicationSuccess() {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();

            if (!isConnectedLocked()) {
                return;
            }

            recordCommunicationSuccessLocked(System.currentTimeMillis());
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 由上層回報一次有效通訊失敗。
     *
     * <p>例如 request timeout、回應格式錯誤或 CRC 檢查失敗，但 serial port 尚未被底層判定斷線。</p>
     *
     * @param reason 失敗原因。
     */
    public void recordCommunicationFailure(String reason) {
        lifecycleLock.writeLock().lock();

        try {
            ensureNotClosed();
            recordCommunicationFailureLocked(reason, System.currentTimeMillis());
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    // =========================
    // 12. 串列埠準備與斷線監控
    // =========================

    /**
     * 依照 activeConfig 尋找並準備目前要使用的串列埠。
     *
     * <p>此方法只負責找到 SerialPort 物件並套用參數，不負責 openPort()。
     * 實際開啟連線由 {@link #connect()} 控制。</p>
     *
     * @return 成功找到連接埠並套用參數時回傳 true；否則回傳 false。
     */
    private boolean preparePortFromActiveConfig() {
        if (activeConfig == null) {
            return false;
        }

        SerialPort port = findPort(activeConfig.portName());

        if (port == null) {
            this.currentPort = null;
            return false;
        }

        this.currentPort = port;
        applyParameter();
        return true;
    }

    /**
     * 綁定串列埠實體斷線監聽器。
     *
     * <p>此方法使用 jSerialComm 的 {@link SerialPort#LISTENING_EVENT_PORT_DISCONNECTED}
     * 事件，用來監控連接埠是否被作業系統判定為中斷。</p>
     *
     * <p>常見觸發情境包含：</p>
     * <ul>
     *     <li>USB-to-Serial 裝置被拔除</li>
     *     <li>COM port 從系統中消失</li>
     *     <li>底層驅動回報 serial port 已不可用</li>
     * </ul>
     *
     * <p>注意：此事件不一定能偵測單純的 UART TX/RX 線鬆脫。
     * 若 USB-to-Serial 裝置仍插在電腦上，但設備端沒有回應，
     * 仍需透過應用層 heartbeat、狀態輪詢或讀寫 timeout 判斷。</p>
     *
     * @param port 要綁定斷線監聽器的串列埠。
     */
    private void bindDisconnectListener(SerialPort port, long generation) {
        if (port == null) {
            return;
        }

        port.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    handlePortDisconnected("Serial port physically disconnected", port, generation);
                }
            }
        });
    }

    /**
     * 統一處理串列埠斷線狀態。
     *
     * <p>此方法是 SerialTool 的斷線狀態收斂點。
     * 不論斷線是由事件監聽器、讀取失敗或寫入失敗觸發，
     * 都應該統一呼叫此方法，避免各處自行 close port 造成狀態不一致。</p>
     *
     * <p>處理內容包含：</p>
     * <ul>
     *     <li>避免同一次斷線被重複處理</li>
     *     <li>移除 jSerialComm data listener</li>
     *     <li>關閉目前串列埠</li>
     *     <li>清除 currentPort 參考</li>
     *     <li>重置讀寫忙碌旗標</li>
     *     <li>通知外部斷線原因</li>
     * </ul>
     *
     * @param reason 斷線原因，用於傳給外部 handler 顯示或記錄。
     */
    private void handlePortDisconnected(String reason, SerialPort disconnectedPort, long disconnectedGeneration) {
        Consumer<String> handlerToNotify = null;

        lifecycleLock.writeLock().lock();

        try {
            SerialPort port = currentPort;

            if (port == null
                    || (disconnectedPort != null && port != disconnectedPort)
                    || connectionGeneration != disconnectedGeneration) {
                return;
            }

            if (!disconnectHandled.compareAndSet(false, true)) {
                return;
            }

            port.removeDataListener();

            if (port.isOpen()) {
                port.closePort();
            }

            currentPort = null;
            readBusy.set(false);
            writeBusy.set(false);
            markDisconnectedLocked(reason);
            handlerToNotify = disconnectHandler;
        } finally {
            lifecycleLock.writeLock().unlock();
        }

        notifyDisconnectHandler(handlerToNotify, reason);
    }

    private void notifyDisconnectHandler(Consumer<String> handlerToNotify, String reason) {
        if (handlerToNotify == null) {
            return;
        }

        try {
            handlerToNotify.accept(reason);
        } catch (RuntimeException ignored) {
            // 外部 handler 不應中斷 SerialTool 的底層清理流程。
        }
    }

    // =========================
    // 13. 健康狀態內部更新
    // =========================

    private void resetHealthStateLocked() {
        healthStatus = SerialHealthStatus.DISCONNECTED;
        lastSuccessfulIoTime = 0L;
        lastFailedIoTime = 0L;
        lastFailureReason = "";
        consecutiveFailureCount = 0;
        failureThreshold = DEFAULT_FAILURE_THRESHOLD;
    }

    private void markConnectedLocked() {
        consecutiveFailureCount = 0;
        lastFailureReason = "";
        refreshHealthStatusLocked();
    }

    private void markDisconnectedLocked(String reason) {
        healthStatus = SerialHealthStatus.DISCONNECTED;
        consecutiveFailureCount = 0;

        if (reason != null && !reason.isBlank()) {
            lastFailedIoTime = System.currentTimeMillis();
            lastFailureReason = reason;
        }
    }

    private void markIoSuccess(SerialPort port, long generation) {
        lifecycleLock.writeLock().lock();

        try {
            if (port != currentPort || connectionGeneration != generation || !isConnectedLocked()) {
                return;
            }

            recordCommunicationSuccessLocked(System.currentTimeMillis());
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void recordCommunicationSuccessLocked(long now) {
        lastSuccessfulIoTime = now;
        consecutiveFailureCount = 0;
        lastFailureReason = "";
        refreshHealthStatusLocked();
    }

    private void recordCommunicationFailureLocked(String reason, long now) {
        lastFailedIoTime = now;
        lastFailureReason = reason == null || reason.isBlank() ? "Communication failed" : reason;

        if (!isConnectedLocked()) {
            healthStatus = SerialHealthStatus.DISCONNECTED;
            consecutiveFailureCount = 0;
            return;
        }

        consecutiveFailureCount++;
        refreshHealthStatusLocked();
    }

    private void refreshHealthStatusLocked() {
        if (!isConnectedLocked()) {
            healthStatus = SerialHealthStatus.DISCONNECTED;
            return;
        }

        if (consecutiveFailureCount <= 0) {
            healthStatus = SerialHealthStatus.CONNECTED;
            return;
        }

        if (consecutiveFailureCount >= failureThreshold) {
            healthStatus = SerialHealthStatus.COMMUNICATION_BAD;
            return;
        }

        healthStatus = SerialHealthStatus.SUSPECTED_STALE;
    }

    // =========================
    // 14. 參數套用與連接埠查找
    // =========================

    /**
     * 將 activeConfig 中的通訊參數套用到 currentPort。
     *
     * <p>此方法只設定 baud rate、data bits、stop bits、parity 與 timeout，
     * 不負責開啟 serial port。</p>
     *
     * @throws IllegalStateException 當 currentPort 或 activeConfig 尚未準備好時拋出。
     */
    private void applyParameter() {
        if (currentPort == null) {
            throw new IllegalStateException("currentPort is null");
        }

        if (activeConfig == null) {
            throw new IllegalStateException("activeConfig is null");
        }

        currentPort.setComPortParameters(
                activeConfig.baudRate(),
                activeConfig.dataBits(),
                activeConfig.stopBits(),
                activeConfig.parity()
        );

        currentPort.setComPortTimeouts(
                activeConfig.timeoutMode(),
                activeConfig.readTimeout(),
                activeConfig.writeTimeout()
        );
    }

    /**
     * 取得目前系統可偵測到的串列埠名稱。
     *
     * @return 串列埠系統名稱清單。
     */
    public List<String> getPortNames() {
        List<String> portNames = new ArrayList<>();

        for (SerialPort port : SerialPort.getCommPorts()) {
            portNames.add(port.getSystemPortName());
        }

        return portNames;
    }

    /**
     * 依名稱尋找串列埠。
     *
     * @param portName 要尋找的串列埠名稱，大小寫不敏感。
     * @return 找到時回傳對應的 {@link SerialPort}；名稱空白或找不到時回傳 null。
     */
    public SerialPort findPort(String portName) {
        if (portName == null || portName.isBlank()) {
            return null;
        }

        for (SerialPort port : SerialPort.getCommPorts()) {
            if (port.getSystemPortName().equalsIgnoreCase(portName)) {
                return port;
            }
        }

        return null;
    }

    /**
     * 判斷是否已有被選取的串列埠物件。
     *
     * @return 已有目前串列埠物件時回傳 true，不代表該埠一定已開啟。
     */
    public boolean hasSelectedPort() {
        lifecycleLock.readLock().lock();

        try {
            return currentPort != null;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 取得目前選取的串列埠名稱。
     *
     * @return 目前串列埠名稱；尚未選取時回傳 null。
     */
    public String getCurrentPortName() {
        lifecycleLock.readLock().lock();

        try {
            SerialPort port = currentPort;

            if (port == null) {
                return null;
            }

            return port.getSystemPortName();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    // =========================
    // 15. 同步 IO
    // =========================

    /**
     * 查詢目前串列埠可讀取的位元組數。
     *
     * @return 已連線時回傳可讀位元組數；未連線時回傳 -1。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public int availability() {
        lifecycleLock.readLock().lock();

        try {
            ensureNotClosed();

            if (!isConnectedLocked()) {
                return -1;
            }

            return currentPort.bytesAvailable();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 清空目前串列埠的輸入與輸出緩衝區。
     *
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public void clearBuffers() {
        lifecycleLock.readLock().lock();

        try {
            ensureNotClosed();

            if (!isConnectedLocked()) {
                return;
            }

            currentPort.flushIOBuffers();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 將資料寫入目前連線的串列埠。
     *
     * @param data 要寫入的位元組資料。
     * @return 實際寫入的位元組數；資料長度為 0 時回傳 0。
     * @throws IOException 當尚未連線、底層寫入失敗或已有寫入作業進行中時拋出。
     * @throws IllegalArgumentException 當資料為 null 時拋出。
     */
    public int write(byte[] data) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }

        if (data.length == 0) {
            return 0;
        }

        // 避免同步與非同步呼叫同時寫入同一個串列埠。
        if (!writeBusy.compareAndSet(false, true)) {
            throw new IOException("寫入忙碌");
        }

        try {
            SerialPort port;
            long generation;
            int writeCount;

            lifecycleLock.readLock().lock();

            try {
                port = requireConnectedPort();
                generation = connectionGeneration;
                writeCount = port.writeBytes(data, data.length);
            } finally {
                lifecycleLock.readLock().unlock();
            }

            if (writeCount < 0) {
                // jSerialComm 在底層寫入失敗時可能回傳負數。
                // 這通常代表 serial port 已不可用，因此統一視為斷線處理。
                handlePortDisconnected("Serial write failed", port, generation);
                throw new IOException("Serial write failed");
            }

            markIoSuccess(port, generation);
            return writeCount;
        } finally {
            writeBusy.set(false);
        }
    }

    /**
     * 從目前連線的串列埠讀取資料到指定緩衝區。
     *
     * @param buffer 接收資料的緩衝區。
     * @return 實際讀取的位元組數；緩衝區長度為 0 時回傳 0。
     * @throws IOException 當尚未連線、底層讀取失敗或已有讀取作業進行中時拋出。
     * @throws IllegalArgumentException 當緩衝區為 null 時拋出。
     */
    public int read(byte[] buffer) throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer cannot be null");
        }

        if (buffer.length == 0) {
            return 0;
        }

        // 避免同步與非同步呼叫同時讀取同一個串列埠。
        if (!readBusy.compareAndSet(false, true)) {
            throw new IOException("讀取忙碌");
        }

        try {
            SerialPort port;
            long generation;
            int readCount;

            lifecycleLock.readLock().lock();

            try {
                port = requireConnectedPort();
                generation = connectionGeneration;
                readCount = port.readBytes(buffer, buffer.length);
            } finally {
                lifecycleLock.readLock().unlock();
            }

            if (readCount < 0) {
                // jSerialComm 在底層讀取失敗時可能回傳負數。
                // 這通常代表 serial port 已不可用，因此統一視為斷線處理。
                handlePortDisconnected("Serial read failed", port, generation);
                throw new IOException("Serial read failed");
            }

            if (readCount > 0) {
                markIoSuccess(port, generation);
            }

            return readCount;
        } finally {
            readBusy.set(false);
        }
    }

    /**
     * 讀取目前串列埠中已可用的全部資料。
     *
     * @return 實際讀到的資料；沒有可讀資料時回傳空陣列。
     * @throws IOException 當尚未連線、讀取忙碌或底層讀取失敗時拋出。
     */
    public byte[] readAvailable() throws IOException {
        if (!readBusy.compareAndSet(false, true)) {
            throw new IOException("讀取忙碌");
        }

        try {
            SerialPort port;
            long generation;
            byte[] buffer;
            int readCount;

            lifecycleLock.readLock().lock();

            try {
                port = requireConnectedPort();
                generation = connectionGeneration;

                int available = port.bytesAvailable();

                if (available <= 0) {
                    return new byte[0];
                }

                buffer = new byte[available];
                readCount = port.readBytes(buffer, buffer.length);
            } finally {
                lifecycleLock.readLock().unlock();
            }

            if (readCount < 0) {
                // jSerialComm 在底層讀取失敗時可能回傳負數。
                // 這通常代表 serial port 已不可用，因此統一視為斷線處理。
                handlePortDisconnected("Serial read failed", port, generation);
                throw new IOException("Serial read failed");
            }

            if (readCount <= 0) {
                return new byte[0];
            }

            markIoSuccess(port, generation);

            // 依實際讀取量裁切，避免回傳尚未填滿的緩衝區尾端。
            return Arrays.copyOf(buffer, readCount);
        } finally {
            readBusy.set(false);
        }
    }

    /**
     * 判斷目前是否有讀取作業進行中。
     *
     * @return 讀取作業進行中時回傳 true。
     */
    public boolean isReading() {
        return readBusy.get();
    }

    /**
     * 判斷目前是否有寫入作業進行中。
     *
     * @return 寫入作業進行中時回傳 true。
     */
    public boolean isWriting() {
        return writeBusy.get();
    }

    /**
     * 在已取得 lifecycle read lock 的前提下，取得目前可用的串列埠。
     *
     * @return 目前已連線的串列埠。
     * @throws IOException 當工具已關閉或 serial port 尚未連線時拋出。
     */
    private SerialPort requireConnectedPort() throws IOException {
        ensureNotClosed();

        if (!isConnectedLocked()) {
            throw new IOException("Serial port is not connected");
        }

        return currentPort;
    }

    /**
     * 確認 SerialTool 尚未被關閉。
     *
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("SerialTool is closed");
        }
    }

    // =========================
    // 16. 非同步 IO
    // =========================

    /**
     * 非同步寫入資料到目前連線的串列埠。
     *
     * @param data 要寫入的位元組資料。
     * @return 可取得寫入位元組數的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<Integer> writeAsync(byte[] data) {
        lifecycleLock.readLock().lock();

        try {
            ensureNotClosed();
            return executorService.submit(() -> write(data));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 非同步讀取資料到指定緩衝區。
     *
     * @param buffer 接收資料的緩衝區。
     * @return 可取得讀取位元組數的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<Integer> readAsync(byte[] buffer) {
        lifecycleLock.readLock().lock();

        try {
            ensureNotClosed();
            return executorService.submit(() -> read(buffer));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 非同步讀取目前串列埠中已可用的全部資料。
     *
     * @return 可取得實際讀取資料的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<byte[]> readAvailableAsync() {
        lifecycleLock.readLock().lock();

        try {
            ensureNotClosed();
            return executorService.submit(this::readAvailable);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    // =========================
    // 17. 資源釋放
    // =========================

    /**
     * 關閉工具並釋放資源。
     */
    public void shutdown() {
        close();
    }

    /**
     * 關閉目前串列埠並停止非同步執行緒池。
     *
     * <p>此方法可重複呼叫，但只有第一次會真正釋放資源。</p>
     */
    @Override
    public void close() {
        // 確保重複呼叫 close() 時只釋放資源一次。
        if (closed.compareAndSet(false, true)) {
            lifecycleLock.writeLock().lock();

            try {
                closeCurrentPort();
            } finally {
                lifecycleLock.writeLock().unlock();
            }

            executorService.shutdownNow();
        }
    }
}
