package serialport;

/**
 * 串列埠通訊健康狀態。
 *
 * <p>此狀態不是主動 heartbeat，而是根據現有 read/write 成功、失敗與斷線事件被動更新。</p>
 */
public enum SerialHealthStatus {
    /**
     * 串列埠已連線，且最近通訊沒有連續失敗。
     */
    CONNECTED,

    /**
     * 串列埠仍開啟，但已發生少量連續通訊失敗。
     */
    SUSPECTED_STALE,

    /**
     * 串列埠仍可能開啟，但連續通訊失敗已達門檻。
     */
    COMMUNICATION_BAD,

    /**
     * 串列埠未連線、已關閉或已偵測到實體斷線。
     */
    DISCONNECTED
}
