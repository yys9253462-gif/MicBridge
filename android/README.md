# MicBridge Android 麦克风音频端

基于 Kotlin + Jetpack Compose + Android 原生底层音频采集框架，将手机麦克风作为电脑的高保真、超低延迟外置麦克风。

## 规格与架构

- **采样参数**：48000 Hz, 16-bit PCM, 单声道 Mono
- **音频帧周期**：10ms 帧长（480 个采样点 = 960 字节净荷）
- **数据包格式**（单包 972 字节）：
  - `[0..3]` Magic: 0x4D494331 ("MIC1")
  - `[4..5]` Sequence: 2 字节递增序号 (0~65535)
  - `[6..7]` Length: 2 字节有效载荷长度 (960)
  - `[8..11]` Timestamp: 4 字节单调递增时间戳
  - `[12..971]` Raw PCM Data: 960 字节音频载荷
- **降噪/回声消除**：
  - 音频源：`MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  - 自动绑定硬件级 `AcousticEchoCanceler` (AEC)、`NoiseSuppressor` (NS)、`AutomaticGainControl` (AGC)

## 传输通道模块

1. **WiFi UDP 高速通道 (`WifiUdpSender.kt`)**：
   - 局域网 UDP 传输，非阻塞 FIFO 队列缓冲（队列满丢弃旧包，保证最低延迟）
   - 支持多播/广播动态配对与 PC 端自动心跳发现
2. **USB AOA 2.0 通道 (`UsbAoaSender.kt`)**：
   - Android Open Accessory 2.0 模式
   - **手机无需开启开发者选项与 USB 调试**，电脑作为 Host 握手后直连，硬件级稳定超低延迟
3. **USB ADB 端口转发 (`UsbAdbSender.kt`)**：
   - 手机本地绑定 `127.0.0.1:18888`
   - PC 执行 `adb forward tcp:18888 tcp:18888` 后直连
4. **经典蓝牙 SPP 串口 (`BluetoothSppSender.kt`)**：
   - 标准 RFCOMM 串口通道（UUID: `00001101-0000-1000-8000-00805F9B34FB`）

## 保活与后台采集

- `MicService.kt` 声明为 `foregroundServiceType="microphone"`（满足 Android 14+ 严格规范）
- 申请 CPU `PARTIAL_WAKE_LOCK`，锁屏与切后台不掉帧不休眠
- 实时通过 StateFlow 向 Compose 界面与通知栏同步 RMS 音量和连接状态

## 快速编译与运行

在 Android Studio 中打开 `android` 目录，或者使用命令行：
```bash
./gradlew assembleDebug
```
生成的 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`
