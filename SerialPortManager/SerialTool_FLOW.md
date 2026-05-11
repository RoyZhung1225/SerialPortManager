# SerialTool 流程地圖

這份文件不是語法說明，而是用來掌握 `SerialTool` 的執行流程與狀態變化。

## 一句話理解

`SerialTool` 負責管理一個串列埠從「設定、連線、讀寫、斷線、健康狀態、釋放資源」的完整生命週期。

它不是單純包裝 `SerialPort.readBytes()` / `writeBytes()`，而是額外處理：

- 連線設定是否已由外部明確提供。
- 目前是否已有開啟中的 serial port。
- 同一時間是否已有讀取或寫入作業。
- 實體拔除 USB-to-Serial 時如何清理狀態。
- 通訊成功或失敗時如何更新健康狀態。
- 多執行緒下如何避免 read/write 與 close/reconnect 互相踩到。

## 主要狀態欄位

| 欄位 | 用途 | 主要由誰改變 |
| --- | --- | --- |
| `activeConfig` | 目前真正用來連線的設定 | `applyConfig()` |
| `pendingConfig` | 等待套用的設定 | `setPendingConfig()` |
| `externalConfigProvided` | 是否曾由外部指定設定 | `setPendingConfig()` |
| `configApplied` | pending 設定是否已套用 | `applyConfig()` |
| `currentPort` | 目前持有的 serial port | `connect()`、`disconnect()`、斷線處理 |
| `connectionGeneration` | 目前連線世代編號 | `connect()` 成功後遞增 |
| `readBusy` | 是否已有讀取正在進行 | `read()`、`readAvailable()` |
| `writeBusy` | 是否已有寫入正在進行 | `write()` |
| `closed` | `SerialTool` 是否已永久關閉 | `close()` |
| `disconnectHandled` | 本次斷線是否已處理過 | `connect()`、斷線處理 |
| `healthStatus` | 被動健康狀態 | 連線、讀寫、斷線、上層回報 |
| `lastSuccessfulIoTime` | 最近成功通訊時間 | IO 成功、上層回報成功 |
| `lastFailedIoTime` | 最近失敗通訊時間 | 斷線、上層回報失敗 |
| `lastFailureReason` | 最近失敗原因 | 斷線、上層回報失敗 |
| `consecutiveFailureCount` | 連續失敗次數 | 成功歸零，失敗累加 |
| `failureThreshold` | 判定 `COMMUNICATION_BAD` 的門檻 | `setFailureThreshold()` |

## 鎖的規則

| 操作類型 | 使用的鎖 | 原因 |
| --- | --- | --- |
| `connect()` | write lock | 會建立或替換 `currentPort` |
| `disconnect()` | write lock | 會關閉並清除 `currentPort` |
| `reconnect()` | write lock | 會先關閉再重新連線 |
| `close()` | write lock | 會釋放 port 與 executor |
| `handlePortDisconnected()` | write lock | 會清除連線狀態與健康狀態 |
| `read()` / `write()` | read lock | 使用目前 port，但不替換 port |
| 狀態查詢 | read lock | 讀取狀態，不改變生命週期 |

重點：read lock 允許多個「不改生命週期」的操作同時進行；write lock 會擋住所有讀寫，確保關閉或重連時不會有人正在使用 `currentPort`。

## 流程 1：物件建立

```text
new SerialTool()
  -> 建立 executorService
  -> setDefaultConfig()
  -> activeConfig = defaultConfig
  -> pendingConfig = defaultConfig
  -> currentPort = null
  -> externalConfigProvided = false
  -> configApplied = false
  -> healthStatus = DISCONNECTED
```

意義：

- 工具有預設值，但還不能直接連線。
- 必須由外部明確呼叫 `setPendingConfig()` 與 `applyConfig()`。

## 流程 2：設定參數

```text
setPendingConfig(config)
  -> write lock
  -> 檢查工具未 close
  -> pendingConfig = config
  -> externalConfigProvided = true
  -> configApplied = false
  -> unlock
```

狀態變化：

| 欄位 | 變化 |
| --- | --- |
| `pendingConfig` | 改成外部傳入設定 |
| `externalConfigProvided` | `true` |
| `configApplied` | `false` |

意義：

- 只是準備設定，還沒有真的套用。
- 此時 `connect()` 還不能成功，因為 `configApplied` 是 `false`。

## 流程 3：套用設定

```text
applyConfig()
  -> write lock
  -> 檢查工具未 close
  -> activeConfig = pendingConfig
  -> configApplied = true
  -> unlock
```

狀態變化：

| 欄位 | 變化 |
| --- | --- |
| `activeConfig` | 改成 `pendingConfig` |
| `configApplied` | `true` |

意義：

- 從這一步開始，`activeConfig` 才是 `connect()` 會使用的設定。

## 流程 4：正常連線

```text
connect()
  -> write lock
  -> 檢查工具未 close
  -> 檢查 activeConfig != null
  -> 檢查 externalConfigProvided && configApplied
  -> 如果已連線，先 closeCurrentPort()
  -> preparePortFromActiveConfig()
       -> findPort(activeConfig.portName())
       -> currentPort = 找到的 port
       -> applyParameter()
  -> currentPort.openPort()
  -> disconnectHandled = false
  -> connectionGeneration++
  -> markConnectedLocked()
  -> bindDisconnectListener(port, generation)
  -> unlock
```

狀態變化：

| 欄位 | 變化 |
| --- | --- |
| `currentPort` | 指向找到並開啟的 port |
| `disconnectHandled` | `false` |
| `connectionGeneration` | 加 1 |
| `healthStatus` | 通常變成 `CONNECTED` |
| `consecutiveFailureCount` | 歸零 |
| `lastFailureReason` | 清空 |

意義：

- 這是 serial port 從「只有設定」進入「真正可讀寫」的流程。
- `connectionGeneration` 是保護機制，用來避免舊 listener 晚到時誤關新連線。

## 流程 5：同步寫入

```text
write(data)
  -> 檢查 data 不為 null
  -> data 長度為 0 則回傳 0
  -> writeBusy 從 false 改 true
  -> read lock
  -> requireConnectedPort()
  -> 記錄目前 connectionGeneration
  -> port.writeBytes(data, data.length)
  -> unlock read lock
  -> 如果 writeCount < 0
       -> handlePortDisconnected("Serial write failed", port, generation)
       -> throw IOException
  -> markIoSuccess(port, generation)
  -> writeBusy = false
```

狀態變化：

| 情況 | 狀態變化 |
| --- | --- |
| 寫入成功 | `lastSuccessfulIoTime` 更新，`consecutiveFailureCount` 歸零 |
| 寫入失敗 | 進入斷線處理流程 |
| 同時重複寫入 | 拋出 `IOException("寫入忙碌")` |

意義：

- `writeBusy` 防止兩個寫入同時操作同一個 port。
- read lock 防止寫入期間 port 被 close/reconnect。

## 流程 6：同步讀取

```text
read(buffer)
  -> 檢查 buffer 不為 null
  -> buffer 長度為 0 則回傳 0
  -> readBusy 從 false 改 true
  -> read lock
  -> requireConnectedPort()
  -> 記錄目前 connectionGeneration
  -> port.readBytes(buffer, buffer.length)
  -> unlock read lock
  -> 如果 readCount < 0
       -> handlePortDisconnected("Serial read failed", port, generation)
       -> throw IOException
  -> 如果 readCount > 0
       -> markIoSuccess(port, generation)
  -> readBusy = false
```

狀態變化：

| 情況 | 狀態變化 |
| --- | --- |
| 讀到資料 | `lastSuccessfulIoTime` 更新，`consecutiveFailureCount` 歸零 |
| 回傳 0 | 不算成功，也不算失敗 |
| 回傳負數 | 進入斷線處理流程 |
| 同時重複讀取 | 拋出 `IOException("讀取忙碌")` |

意義：

- 讀到 0 只代表這次沒有資料，不代表設備壞掉。
- 所以目前只有讀到資料才更新成功時間。

## 流程 7：讀取目前可用資料

```text
readAvailable()
  -> readBusy 從 false 改 true
  -> read lock
  -> requireConnectedPort()
  -> available = port.bytesAvailable()
  -> 如果 available <= 0，回傳空陣列
  -> 建立 buffer
  -> port.readBytes(buffer, buffer.length)
  -> unlock read lock
  -> 如果 readCount < 0，進入斷線處理
  -> 如果 readCount <= 0，回傳空陣列
  -> markIoSuccess(port, generation)
  -> 回傳裁切後的資料
  -> readBusy = false
```

意義：

- 這不是「向設備查詢最新資料」。
- 它只是把電腦端目前 serial buffer 裡已有的資料讀出來。

## 流程 8：上層回報通訊失敗

```text
recordCommunicationFailure(reason)
  -> write lock
  -> 檢查工具未 close
  -> lastFailedIoTime = now
  -> lastFailureReason = reason
  -> 如果目前沒連線
       -> healthStatus = DISCONNECTED
       -> consecutiveFailureCount = 0
  -> 如果目前仍連線
       -> consecutiveFailureCount++
       -> refreshHealthStatusLocked()
  -> unlock
```

健康狀態變化：

| 條件 | 狀態 |
| --- | --- |
| 沒連線 | `DISCONNECTED` |
| 失敗次數 = 1 到門檻前 | `SUSPECTED_STALE` |
| 失敗次數 >= `failureThreshold` | `COMMUNICATION_BAD` |

使用時機：

- request timeout。
- 回應格式錯誤。
- CRC 檢查失敗。
- 上層解析不到有效資料。

## 流程 9：上層回報通訊成功

```text
recordCommunicationSuccess()
  -> write lock
  -> 檢查工具未 close
  -> 如果目前沒連線，直接 return
  -> lastSuccessfulIoTime = now
  -> consecutiveFailureCount = 0
  -> lastFailureReason = ""
  -> refreshHealthStatusLocked()
  -> unlock
```

意義：

- 這是給上層業務流程使用的。
- 例如你送出指令、收到回應、CRC 正確、解析成功，就可以呼叫它。

## 流程 10：實體斷線或底層 IO 失敗

```text
handlePortDisconnected(reason, disconnectedPort, disconnectedGeneration)
  -> write lock
  -> 如果 currentPort 為 null，return
  -> 如果 disconnectedPort 不是目前 currentPort，return
  -> 如果 generation 不是目前 generation，return
  -> 如果 disconnectHandled 已經是 true，return
  -> removeDataListener()
  -> closePort()
  -> currentPort = null
  -> readBusy = false
  -> writeBusy = false
  -> healthStatus = DISCONNECTED
  -> lastFailedIoTime = now
  -> lastFailureReason = reason
  -> unlock
  -> notifyDisconnectHandler(reason)
```

意義：

- 所有非預期斷線都收斂到這裡。
- 外部 handler 在 lock 外呼叫，避免 UI 或上層邏輯卡住底層狀態清理。

## 流程 11：主動斷線

```text
disconnect()
  -> write lock
  -> 檢查工具未 close
  -> closeCurrentPort()
       -> removeDataListener()
       -> closePort()
       -> currentPort = null
       -> healthStatus = DISCONNECTED
  -> unlock
```

注意：

- 主動斷線不會呼叫 `disconnectHandler`。
- 因為這不是異常，而是使用者或上層流程主動要求。

## 流程 12：重新連線

```text
reconnect()
  -> write lock
  -> 檢查工具未 close
  -> closeCurrentPort()
  -> connectLocked()
  -> unlock
```

意義：

- 同一把 write lock 會保護整個「先關再開」流程。
- 不會發生 close 到一半被 read/write 插進來。

## 流程 13：套用設定並重新連線

```text
applyAndReconnect()
  -> write lock
  -> 檢查工具未 close
  -> applyConfigLocked()
  -> closeCurrentPort()
  -> connectLocked()
  -> unlock
```

意義：

- 適合使用者改了 port 或 baud rate 後，直接套用並重連。

## 流程 14：關閉工具

```text
close()
  -> closed 從 false 改 true
  -> write lock
  -> closeCurrentPort()
  -> unlock
  -> executorService.shutdownNow()
```

狀態變化：

| 欄位 | 變化 |
| --- | --- |
| `closed` | `true` |
| `currentPort` | `null` |
| `healthStatus` | `DISCONNECTED` |

意義：

- `close()` 後這個 `SerialTool` 不應再被使用。
- 重複呼叫 `close()` 不會重複釋放資源。

## 正常使用順序

```text
new SerialTool()
  -> setPendingConfig(config)
  -> applyConfig()
  -> connect()
  -> write/read/readAvailable
  -> disconnect()
  -> close()
```

## 健康狀態轉換簡圖

```text
初始 / close / disconnect / 實體斷線
  -> DISCONNECTED

connect 成功
  -> CONNECTED

上層回報少量失敗
  -> SUSPECTED_STALE

連續失敗達門檻
  -> COMMUNICATION_BAD

IO 成功或上層回報成功
  -> CONNECTED
```

## 最容易混淆的點

1. `isConnected()` 只代表 port 開著，不代表設備韌體一定正常。
2. `readAvailable()` 只讀目前 buffer，不是向設備發問。
3. `recordCommunicationFailure()` 是給上層業務流程回報用，不是底層自動 heartbeat。
4. `disconnectHandler` 只處理非預期斷線，不處理主動 `disconnect()`。
5. `connectionGeneration` 是避免舊 listener 影響新連線，不是連線次數統計。
6. `readBusy/writeBusy` 只防止同方向重複 IO，生命週期安全主要靠 `lifecycleLock`。
