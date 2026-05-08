package serialport;

import com.fazecast.jSerialComm.SerialPort;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 串列埠操作工具，封裝 jSerialComm 的連線、設定套用、同步 IO 與非同步 IO。
 *
 * <p>此類別維護目前作用中的連接埠與設定，呼叫端可先設定 {@link SerialConfig}，
 * 再進行連線與資料讀寫。物件關閉後不可再次使用。</p>
 */
public class SerialTool implements AutoCloseable {
    private static final String DEFAULT_PORT_NAME = "COM3";
    private static final int DEFAULT_BAUD_RATE = 9600;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int DEFAULT_PARITY = SerialPort.NO_PARITY;
    private static final int DEFAULT_TIMEOUT_MODE =
            SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING;
    private static final int DEFAULT_READ_TIMEOUT = 100;
    private static final int DEFAULT_WRITE_TIMEOUT = 100;

    /**
     * 目前已套用、會用於建立串列埠連線的設定。
     */
    @Getter
    private SerialConfig activeConfig;

    /**
     * 等待套用的串列埠設定。
     */
    @Getter
    private SerialConfig pendingConfig;

    // 連線前需確認呼叫端已明確提供並套用設定，避免直接使用預設值誤連。
    private boolean externalConfigProvided = false;
    private boolean configApplied = false;

    private SerialPort currentPort;

    private final AtomicBoolean readBusy = new AtomicBoolean(false);
    private final AtomicBoolean writeBusy = new AtomicBoolean(false);

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ExecutorService executorService;


    /**
     * 建立串列埠工具並載入預設設定。
     */
    public SerialTool() {
        this.executorService = Executors.newFixedThreadPool(2);
        setDefaultConfig();
    }

    private void setDefaultConfig() {
        SerialConfig defaultConfig = createDefaultConfig();

        this.activeConfig = defaultConfig;
        this.pendingConfig = defaultConfig;
        this.currentPort = null;
        this.externalConfigProvided = false;
        this.configApplied = false;
    }

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

    /**
     * 設定等待套用的串列埠參數。
     *
     * @param config 待套用的串列埠設定。
     * @throws IllegalStateException 當工具已關閉時拋出。
     * @throws IllegalArgumentException 當設定為 null 時拋出。
     */
    public void setPendingConfig(SerialConfig config) {
        ensureNotClosed();

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        this.pendingConfig = config;
        this.externalConfigProvided = true;
        this.configApplied = false;
    }

    /**
     * 將等待套用的設定設為目前作用中的設定。
     *
     * @throws IllegalStateException 當工具已關閉，或沒有待套用設定時拋出。
     */
    public void applyConfig() {
        ensureNotClosed();

        if (pendingConfig == null) {
            throw new IllegalStateException("pendingConfig is null");
        }

        this.activeConfig = pendingConfig;
        this.configApplied = true;
    }

    /**
     * 使用目前作用中的設定連接串列埠。
     *
     * @return 連線成功時回傳 true；設定尚未就緒、找不到連接埠或開啟失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean connect() {
        ensureNotClosed();

        if (activeConfig == null) {
            return false;
        }

        if (!isConfigReady()) {
            return false;
        }

        if (isConnected()) {
            disconnect();
        }

        boolean prepared = preparePortFromActiveConfig();

        if (!prepared) {
            return false;
        }

        return currentPort.openPort();
    }

    /**
     * 關閉目前串列埠連線。
     *
     * @return 沒有連線、連接埠已非開啟狀態或成功關閉時回傳 true；底層關閉失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean disconnect() {
        ensureNotClosed();
        return closeCurrentPort();
    }

    private boolean closeCurrentPort() {
        if (currentPort == null) {
            return true;
        }

        if (!currentPort.isOpen()) {
            return true;
        }

        return currentPort.closePort();
    }

    /**
     * 重新連接目前設定指定的串列埠。
     *
     * @return 成功斷線並重新連線時回傳 true；任一步驟失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public boolean reconnect() {
        ensureNotClosed();

        if (!disconnect()) {
            return false;
        }

        return connect();
    }

    /**
     * 套用待生效設定後重新連接串列埠。
     *
     * @return 重新連線成功時回傳 true；套用或連線流程失敗時回傳 false。
     * @throws IllegalStateException 當工具已關閉，或沒有待套用設定時拋出。
     */
    public boolean applyAndReconnect() {
        ensureNotClosed();
        applyConfig();
        return reconnect();
    }

    /**
     * 判斷目前是否已有開啟中的串列埠。
     *
     * @return 串列埠存在且已開啟時回傳 true。
     */
    public boolean isConnected() {
        return currentPort != null && currentPort.isOpen();
    }

    /**
     * 判斷外部設定是否已提供並進入可連線狀態。
     *
     * @return 外部設定已提供且已標記套用時回傳 true。
     */
    public boolean isConfigReady() {
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
        return currentPort != null;
    }

    /**
     * 取得目前選取的串列埠名稱。
     *
     * @return 目前串列埠名稱；尚未選取時回傳 null。
     */
    public String getCurrentPortName() {
        if (currentPort == null) {
            return null;
        }

        return currentPort.getSystemPortName();
    }

    // =========================
    // 第三階段：同步 IO
    // =========================

    /**
     * 查詢目前串列埠可讀取的位元組數。
     *
     * @return 已連線時回傳可讀位元組數；未連線時回傳 -1。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public int availability() {
        ensureNotClosed();

        if (!isConnected()) {
            return -1;
        }

        return currentPort.bytesAvailable();
    }

    /**
     * 清空目前串列埠的輸入與輸出緩衝區。
     *
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public void clearBuffers() {
        ensureNotClosed();

        if (!isConnected()) {
            return;
        }

        currentPort.flushIOBuffers();
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
        ensureConnected();

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
            return currentPort.writeBytes(data, data.length);
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
        ensureConnected();

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
            return currentPort.readBytes(buffer, buffer.length);
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
        ensureConnected();

        int available = availability();

        if (available <= 0) {
            return new byte[0];
        }

        byte[] buffer = new byte[available];
        int readCount = read(buffer);

        if (readCount <= 0) {
            return new byte[0];
        }

        // 依實際讀取量裁切，避免回傳尚未填滿的緩衝區尾端。
        return Arrays.copyOf(buffer, readCount);
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

    private void ensureConnected() throws IOException {
        ensureNotClosed();

        if (!isConnected()) {
            throw new IOException("Serial port is not connected");
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("SerialTool is closed");
        }
    }

    // =========================
    // 第四階段：Future IO
    // =========================

    /**
     * 非同步寫入資料到目前連線的串列埠。
     *
     * @param data 要寫入的位元組資料。
     * @return 可取得寫入位元組數的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<Integer> writeAsync(byte[] data) {
        ensureNotClosed();
        return executorService.submit(() -> write(data));
    }

    /**
     * 非同步讀取資料到指定緩衝區。
     *
     * @param buffer 接收資料的緩衝區。
     * @return 可取得讀取位元組數的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<Integer> readAsync(byte[] buffer) {
        ensureNotClosed();
        return executorService.submit(() -> read(buffer));
    }

    /**
     * 非同步讀取目前串列埠中已可用的全部資料。
     *
     * @return 可取得實際讀取資料的 Future。
     * @throws IllegalStateException 當工具已關閉時拋出。
     */
    public Future<byte[]> readAvailableAsync() {
        ensureNotClosed();
        return executorService.submit(this::readAvailable);
    }

    /**
     * 關閉工具並釋放資源。
     */
    public void shutdown() {
        close();
    }

    /**
     * 關閉目前串列埠並停止非同步執行緒池。
     */
    @Override
    public void close() {
        // 確保重複呼叫 close() 時只釋放資源一次。
        if (closed.compareAndSet(false, true)) {
            closeCurrentPort();
            currentPort = null;
            executorService.shutdownNow();
        }
    }

}
