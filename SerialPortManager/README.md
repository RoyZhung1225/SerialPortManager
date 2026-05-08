# SerialPortManager

SerialPortManager 是一個以 jSerialComm 為底層的 Java 串列埠工具包，封裝常見的連線設定、連接/斷線、同步讀寫、非阻塞式同步(NIO)讀寫與資源釋放流程。

## 功能特色

- 使用 `SerialConfig` 統一管理串列埠連線參數。
- 使用 `SerialTool` 封裝串列埠連接、重新連線與關閉流程。
- 支援同步 `read`、`write` 與 `readAvailable`。
- 支援以 `Future` 回傳結果的非阻塞式同步(NIO)讀寫。
- 內建讀寫忙碌狀態保護，避免同一時間重複讀取或寫入同一個串列埠。
- 實作 `AutoCloseable`，可搭配 try-with-resources 自動釋放資源。

## 環境需求

- Java 17 或以上版本。
- Maven 專案環境。
- jSerialComm 2.9.3。
- Lombok 1.18.44。

## Maven 依賴

本專案已在 `pom.xml` 中設定必要依賴：

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.44</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <version>2.9.3</version>
</dependency>
```

## 快速開始

```java
import com.fazecast.jSerialComm.SerialPort;
import serialport.SerialConfig;
import serialport.SerialTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Example {
    public static void main(String[] args) throws IOException {
        SerialConfig config = new SerialConfig(
                "COM3",
                9600,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY,
                SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                100,
                100
        );

        try (SerialTool serialTool = new SerialTool()) {
            serialTool.setPendingConfig(config);
            serialTool.applyConfig();

            if (!serialTool.connect()) {
                System.out.println("串列埠連線失敗");
                return;
            }

            serialTool.write("PING".getBytes(StandardCharsets.UTF_8));

            byte[] response = serialTool.readAvailable();
            System.out.println(new String(response, StandardCharsets.UTF_8));
        }
    }
}
```

## 使用流程

1. 建立 `SerialConfig`。
2. 建立 `SerialTool`。
3. 呼叫 `setPendingConfig(config)` 設定待套用參數。
4. 呼叫 `applyConfig()` 套用設定。
5. 呼叫 `connect()` 開啟串列埠。
6. 使用 `write`、`read` 或 `readAvailable` 進行資料交換。
7. 呼叫 `disconnect()` 或 `close()` 釋放資源。

## SerialConfig 參數

| 參數 | 說明 |
| --- | --- |
| `portName` | 串列埠名稱，例如 `COM3`。 |
| `baudRate` | 傳輸鮑率，必須大於 0。 |
| `dataBits` | 資料位元數，必須大於 0。 |
| `stopBits` | 停止位設定，建議使用 jSerialComm 常數。 |
| `parity` | 同位檢查設定，建議使用 jSerialComm 常數。 |
| `timeoutMode` | 讀寫逾時模式，建議使用 jSerialComm 的 `TIMEOUT_*` 常數。 |
| `readTimeout` | 讀取逾時毫秒數，不可為負。 |
| `writeTimeout` | 寫入逾時毫秒數，不可為負。 |

## SerialTool 主要方法

| 方法 | 說明 |
| --- | --- |
| `setPendingConfig(SerialConfig config)` | 設定等待套用的串列埠參數。 |
| `applyConfig()` | 將等待套用的設定設為目前作用中的設定。 |
| `connect()` | 使用目前作用中的設定連接串列埠。 |
| `disconnect()` | 關閉目前串列埠連線。 |
| `reconnect()` | 先斷線，再使用目前設定重新連線。 |
| `applyAndReconnect()` | 套用待生效設定後重新連線。 |
| `getPortNames()` | 取得系統可偵測到的串列埠名稱清單。 |
| `findPort(String portName)` | 依名稱尋找串列埠。 |
| `availability()` | 查詢目前可讀取的位元組數。 |
| `clearBuffers()` | 清空輸入與輸出緩衝區。 |
| `write(byte[] data)` | 同步寫入資料。 |
| `read(byte[] buffer)` | 同步讀取資料到指定緩衝區。 |
| `readAvailable()` | 讀取目前已可用的全部資料。 |
| `writeAsync(byte[] data)` | 非阻塞式同步(NIO)寫入資料。 |
| `readAsync(byte[] buffer)` | 非阻塞式同步(NIO)讀取資料。 |
| `readAvailableAsync()` | 非阻塞式同步(NIO)讀取目前已可用的全部資料。 |
| `close()` | 關閉串列埠並停止非阻塞式同步(NIO)執行緒池。 |

## 非阻塞式同步(NIO)讀寫範例

```java
Future<Integer> writeFuture = serialTool.writeAsync(data);
Integer writeCount = writeFuture.get();

Future<byte[]> readFuture = serialTool.readAvailableAsync();
byte[] response = readFuture.get();
```

非阻塞式同步(NIO)方法會將實際 IO 工作交給內部執行緒池處理，結果透過 `Future` 取得。呼叫 `close()` 後，工具會停止執行緒池並釋放目前串列埠。

## 注意事項

- `SerialTool` 關閉後不可再次使用。
- 進行讀寫前必須先成功連線。
- 同一時間只能有一個讀取作業與一個寫入作業；重複呼叫會拋出 `IOException`。
- `availability()` 在未連線時會回傳 `-1`。
- `readAvailable()` 沒有可讀資料時會回傳空陣列。
- `connect()` 會在已連線時先嘗試斷線，再重新依目前設定開啟串列埠。
- 串列埠名稱會依作業系統不同而不同，例如 Windows 常見為 `COM3`，Linux/macOS 可能是 `/dev/ttyUSB0` 或 `/dev/tty.usbserial-*`。

## 專案結構

```text
src/main/java/serialport
├── SerialConfig.java
└── SerialTool.java
```

## 授權

目前尚未指定授權條款。
