# MicBridge (麦克风之桥) 🎙️

> 将你的 Android 手机变成电脑的高清、超低延迟系统虚拟麦克风。支持 WiFi、USB、蓝牙全连接模式，开箱即用。

---

## 🌟 核心特性

- **开箱即用**：
  - PC 端为独立单文件可执行文件（`.exe`），内置所需全部运行时，无需预先安装 .NET 环境。
  - Android 端提供轻量原生应用（`.apk`），权限自适应。
- **全主流连接方式覆盖**：
  - 📶 **WiFi (局域网)**：基于 UDP 高速低延迟数据流 + 局域网广播信标自动发现，无需手动查找和输入 IP。
  - 🔌 **USB 有线连接**：
    - **AOA 2.0 免调试模式**：手机无需开启“开发者模式”与“USB 调试”，插线即可握手通信。
    - **ADB 极客模式**：支持原生 ADB 反向端口映射，实现稳定 <15ms 极低延迟。
  - 📡 **经典蓝牙**：基于 RFCOMM (SPP 串口流) 协议，绕开传统蓝牙耳机 8kHz/16kHz 劣质电话音，传输 48kHz 全频带高保真音质。
- **超低时延与硬件级音质**：
  - 采样规范：`48000Hz`、`16-bit PCM`、单声道 Mono、10ms 极小帧长。
  - 调动手机硬件芯片层级的 **AEC（回声消除）**、**NS（噪声抑制）** 和 **AGC（自动增益）**。
- **PC 虚拟音频无感注入**：
  - 基于 Windows 原生 WASAPI Event-Driven 驱动注入机制。
  - 内置基于 RFC 3550 标准的抗抖动环形缓冲区（Jitter Buffer），有效平滑网络抖动与乱序。
  - 智能防静音保护：适配虚拟音频设备时，自动保护并还原系统默认扬声器。

---

## 📦 快速下载使用

前往 [Releases 页面](../../releases) 获取最新打包产物：

1. **电脑端**：下载 `MicBridge.PC.exe` 直接双击运行（控制台将显示实时网络延迟、收包率与音频音量电平）。
2. **手机端**：下载 `MicBridge.apk` 安装到 Android 手机。
3. **连接使用**：
   - 打开手机端 App，授予录音权限。
   - 选择连接模式（WiFi / USB / 蓝牙），点击**启动麦克风推流**。
   - 电脑端微信、腾讯会议、Discord、OBS 等软件在输入麦克风中选择 `CABLE Output` 即可！

---

## 🛠️ 项目架构

```text
MicBridge/
├── android/             # Android 原生客户端 (Kotlin + Jetpack Compose)
│   ├── app/src/main/    # AudioRecord 硬件采集、前台保活服务与传输模块
├── pc-client/           # PC 端服务 (.NET 8.0 C# + WASAPI + JitterBuffer)
│   ├── Audio/           # WASAPI 注入、驱动管理与 JitterBuffer 环形缓冲
│   └── Transports/      # WiFi UDP、USB 端口映射与蓝牙接收器
└── release/             # 预编译分发包 (MicBridge.PC.exe & MicBridge.apk)
```

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源。
