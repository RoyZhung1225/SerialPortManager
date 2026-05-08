package serialport;

/**
 * 串列埠連線參數的不可變設定。
 *
 * <p>建立物件時會驗證必要欄位，避免後續連線流程收到空白連接埠名稱或不合理的基本參數。</p>
 *
 * @param portName 串列埠名稱，例如 COM3。
 * @param baudRate 傳輸鮑率，必須大於 0。
 * @param dataBits 資料位元數，必須大於 0。
 * @param stopBits 停止位設定，通常使用 jSerialComm 的常數。
 * @param parity 同位檢查設定，通常使用 jSerialComm 的常數。
 * @param timeoutMode 讀寫逾時模式，通常使用 jSerialComm 的 TIMEOUT_* 常數組合。
 * @param readTimeout 讀取逾時毫秒數，不可為負。
 * @param writeTimeout 寫入逾時毫秒數，不可為負。
 */
public record SerialConfig(String portName, int baudRate, int dataBits, int stopBits, int parity, int timeoutMode,
                           int readTimeout, int writeTimeout) {
    /**
     * 建立並驗證串列埠設定。
     *
     * @throws IllegalArgumentException 當連接埠名稱空白，或數值參數不符合基本限制時拋出。
     */
    public SerialConfig {
        if (portName == null || portName.isBlank()) {
            throw new IllegalArgumentException("portName cannot be null or blank");
        }

        if (baudRate <= 0) {
            throw new IllegalArgumentException("baudRate must be greater than 0");
        }

        if (dataBits <= 0) {
            throw new IllegalArgumentException("dataBits must be greater than 0");
        }

        if (readTimeout < 0) {
            throw new IllegalArgumentException("readTimeout cannot be negative");
        }

        if (writeTimeout < 0) {
            throw new IllegalArgumentException("writeTimeout cannot be negative");
        }

    }

}
