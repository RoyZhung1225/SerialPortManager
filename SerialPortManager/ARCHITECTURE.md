# SerialPortManager 架構決策紀錄

## 核心目的

`SerialTool` 的目的很簡單：

> 封裝所有對底層串列埠通訊的任務。

也就是說，上層程式不需要直接面對 jSerialComm、不需要自己管理 serial port 的生命週期、不需要自己處理讀寫忙碌、實體斷線、底層 IO 失敗與資源釋放。

`SerialTool` 是底層通訊邊界層，不是設備業務邏輯層。

## SerialTool 負責的事

- 串列埠參數設定與套用。
- `connect()` / `disconnect()` / `reconnect()` / `close()`。
- 底層同步讀寫：`read()`、`write()`、`readAvailable()`。
- 以 `Future` 包裝的非同步讀寫。
- 防止同方向重複讀寫。
- 防止連線生命週期操作與 IO 互相踩到。
- 偵測 USB-to-Serial 實體斷線事件。
- 在底層 read/write 失敗時統一收斂斷線處理。
- 更新被動健康狀態。
- 通知上層非預期斷線。
- 釋放 serial port 與 executor 資源。

## SerialTool 不負責的事

- UI 顯示與畫面狀態更新。
- 設備協議解析。
- 指令語意，例如 `readVoltage()`、`readTemperature()`。
- 設備資料 cache。
- 主動 heartbeat。
- 自動重連策略。
- 資料庫、檔案或網路上傳。

如果某個功能是在解讀「設備資料代表什麼」，它不應該放進 `SerialTool`。

如果某個功能是在保護「serial port 本身如何安全讀寫與釋放」，它可以放進 `SerialTool`。

## 分層邊界

建議概念分層如下：

```text
UI / Application
  顯示狀態、按鈕操作、錯誤提示

Device Service / Command Client
  設備指令、封包格式、CRC、request timeout、資料解析

SerialTool
  底層 serial port 連線、讀寫、斷線、健康狀態、資源釋放

jSerialComm
  實際與作業系統 serial port 溝通
```

目前專案主要實作的是 `SerialTool` 這一層。

## 決策 1：pendingConfig 與 activeConfig 分離

原因：

使用者或上層程式可能先調整設定，但不一定要立刻影響目前連線。

因此設定被分成：

- `pendingConfig`：等待套用的設定。
- `activeConfig`：目前真正用來連線的設定。

流程是：

```text
setPendingConfig(config)
  -> 只更新 pendingConfig

applyConfig()
  -> activeConfig = pendingConfig
```

如果不這樣做：

- UI 還在編輯設定時，可能立刻影響目前連線。
- 使用者尚未確認就改變 serial port 參數。

## 決策 2：不允許直接使用預設 COM port 誤連

原因：

預設值只是讓物件有初始狀態，不代表應該直接連線。

因此使用：

- `externalConfigProvided`
- `configApplied`

來確認外部已明確提供並套用設定。

如果不這樣做：

程式可能在使用者尚未選擇 port 時，直接使用 `COM3` 嘗試連線。

## 決策 3：使用 lifecycle ReadWriteLock

原因：

`currentPort` 是最重要的共享資源。它可能被：

- `read()` 使用。
- `write()` 使用。
- `disconnect()` 關閉。
- `reconnect()` 替換。
- `close()` 釋放。
- 實體斷線事件清除。

因此使用 `ReadWriteLock`：

| 操作 | 鎖 |
| --- | --- |
| read/write/查詢 | read lock |
| connect/disconnect/reconnect/close/斷線清理 | write lock |

這樣可以保留讀寫同時進行的能力，同時避免 IO 期間 port 被關掉。

如果不這樣做：

- `read()` 正在執行時，另一個 thread 可能 `close()`。
- `write()` 正在執行時，另一個 thread 可能 `reconnect()`。
- `currentPort` 可能突然變成 null 或被換成另一個 port。

## 決策 4：保留 readBusy 與 writeBusy

原因：

`ReadWriteLock` 保護的是 serial port 生命週期，不是同方向 IO 的重入。

因此仍保留：

- `readBusy`
- `writeBusy`

規則是：

- 同一時間只允許一個讀取。
- 同一時間只允許一個寫入。
- 讀取與寫入可以同時發生。

這符合多數 serial port 全雙工通訊的基本模型。

如果不這樣做：

- 兩個 `read()` 可能互相搶資料。
- 兩個 `write()` 可能交錯送出封包。
- 上層收到的回應順序可能變得難以判斷。

## 決策 5：使用 connectionGeneration

原因：

jSerialComm 的 listener 可能在舊連線已經關閉後才晚到。

如果程式已經重新連線，舊 listener 不應該有權限關掉新連線。

因此每次成功連線時：

```text
connectionGeneration++
bindDisconnectListener(port, generation)
```

斷線事件進來時會檢查：

- 事件的 port 是否等於目前 `currentPort`。
- 事件的 generation 是否等於目前 `connectionGeneration`。

如果不這樣做：

舊連線的延遲斷線事件可能誤清掉新連線。

## 決策 6：實體斷線統一收斂到 handlePortDisconnected

原因：

斷線可能由多個來源觸發：

- jSerialComm 的 `LISTENING_EVENT_PORT_DISCONNECTED`。
- `writeBytes()` 回傳負數。
- `readBytes()` 回傳負數。

這些來源都代表底層 port 可能已不可用，所以統一進：

```text
handlePortDisconnected(...)
```

這個方法負責：

- 避免重複處理同一次斷線。
- 移除 listener。
- 關閉 port。
- 清除 `currentPort`。
- 重置 read/write busy。
- 更新健康狀態為 `DISCONNECTED`。
- 通知外部 handler。

如果不這樣做：

每個地方各自 close port，狀態會很容易不一致。

## 決策 7：disconnectHandler 不直接放 UI 邏輯

原因：

`SerialTool` 是底層通訊層，不應該知道 UI 怎麼更新。

所以它只提供：

```java
setDisconnectHandler(Consumer<String> handler)
```

讓上層自己決定斷線後要做什麼。

而且 handler 會在 lock 外呼叫，避免上層邏輯卡住底層狀態清理。

## 決策 8：不做主動 heartbeat

原因：

目前無法要求韌體提供一個安全的 ping/pong 指令。

真正 heartbeat 需要滿足：

```text
主機送出不改變設備狀態的探測指令
設備回覆可驗證的回應
```

如果沒有韌體配合，不應該硬用現有業務指令假裝 heartbeat。

風險：

- 讀取不到資料不代表設備壞掉。
- 有資料也可能是舊資料。
- 某些指令可能會改變設備狀態。
- heartbeat 可能和正常業務指令搶回應。

替代方案：

使用被動健康狀態。

## 決策 9：使用被動健康狀態

原因：

雖然不能做主動 heartbeat，但仍需要知道通訊品質是否正在變差。

因此加入：

- `CONNECTED`
- `SUSPECTED_STALE`
- `COMMUNICATION_BAD`
- `DISCONNECTED`

判斷來源：

- 底層 IO 成功。
- 底層 IO 失敗。
- 實體斷線事件。
- 上層回報 request timeout、CRC 錯、解析失敗。

這讓上層可以知道：

- serial port 是否仍開著。
- 最近是否有成功通訊。
- 是否連續發生通訊失敗。
- 是否已達到不健康門檻。

## 決策 10：非同步 IO 是 Future 包裝，不是 Java NIO

目前的非同步方法：

```java
writeAsync(...)
readAsync(...)
readAvailableAsync(...)
```

本質是把同步 IO 丟到 `ExecutorService`，再回傳 `Future`。

它不是 Java NIO，也不是真正的 non-blocking serial IO。

正確理解：

```text
同步 read/write
  -> 由 executor 背景執行
  -> 呼叫端用 Future 等結果
```

## 目前重要限制

- `SerialTool` 不理解封包格式。
- `SerialTool` 不知道哪個回應對應哪個指令。
- `SerialTool` 不處理 CRC。
- `SerialTool` 不負責 cache 設備資料。
- `readAvailable()` 只讀目前 buffer，不代表設備最新狀態。
- `isConnected()` 只代表 port 開著，不代表設備韌體正常。

## 未來可以補的方向

這些功能可以補，但不一定應該放在 `SerialTool`：

| 功能 | 建議位置 |
| --- | --- |
| request/response 指令層 | `SerialCommandClient` |
| CRC 驗證 | 指令層 |
| 封包解析 | 指令層 |
| 設備資料 cache | `DeviceCacheClient` |
| 自動重連策略 | 上層 Service 或 Coordinator |
| UI 狀態更新 | UI / Application |
| 測試用 fake serial port | Adapter 或測試層 |

## 記憶重點

如果忘記整個架構，只要記住這句：

> `SerialTool` 封裝所有底層通訊任務，但不處理設備業務語意。

判斷一個功能要不要放進 `SerialTool`：

- 和 serial port 連線、讀寫、安全、釋放有關，可以放。
- 和設備資料意思、畫面顯示、業務指令、cache 有關，放上層。
