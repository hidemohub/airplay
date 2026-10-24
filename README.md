# airplay

用于 Windows 的 AirPlay 接收程序，支持 iPhone / iPad 屏幕镜像、音视频播放，以及实验性 AirPlay 2 音乐接收。提供主窗口、独立视频窗口和系统托盘，内置运行所需的 Java、GStreamer 和音乐后端环境。

当前目录是经过修复和定制的便携发行包，包含可执行程序、补丁源码和维护工具。

**下载运行：** 请在 [GitHub Releases](https://github.com/hidemohub/airplay/releases/latest) 下载完整 Windows x64 便携包并解压，再运行其中的 `start.exe`。仓库文件用于查看和维护补丁源码，GitHub 的“Download ZIP”源码归档不包含 Java、GStreamer、Python 运行环境和主程序 JAR。

## 功能

- **屏幕镜像**：接收并播放 H.264 视频；可开启实验性 H.265 / HEVC 协商。
- **视频信息**：独立窗口标题栏显示实际分辨率、实时 FPS 和编码，随横竖屏及视频格式变化更新。
- **窗口控制**：置顶、全屏、最小化、最大化与还原；支持 `F11` 切换全屏、`Esc` 退出全屏。
- **独立播放**：打开独立视频窗口后隐藏主窗口，关闭独立窗口后恢复主窗口。
- **桌面设置**：支持中英文、主题切换，以及关闭窗口时退出或隐藏到托盘。
- **音乐接收**：独立的实验性 AirPlay 2 音乐后端，可在设置中即时启停。
- **退出清理**：退出时立即隐藏窗口，在后台清理镜像和音乐服务，并回收音乐子进程。

## 快速开始

1. 将整个项目目录放在可读写的位置，保留目录结构。
2. 让 Windows 电脑与 iPhone / iPad 接入能够互相通信的同一局域网。
3. 双击 **`start.exe`**。完整发行包无需另装 Java、Python 或 GStreamer。
4. 在 iPhone / iPad 的控制中心打开“屏幕镜像”，选择 **`airplay`**；如已修改接收器名称，则选择修改后的名称。
5. 如需投放音乐，在系统或音乐应用的音频输出列表中选择 **`airplay Music`**。音乐接收开关位于程序设置页。

首次启动时，如 Windows 防火墙询问网络访问权限，请允许程序在所使用的局域网中通信。路由器的访客网络、客户端隔离或不支持组播的网络可能影响设备发现。

## 使用说明

### 窗口与托盘

标题栏右侧提供置顶、全屏、最小化、最大化 / 还原和关闭按钮。每次启动默认不置顶。

“关闭窗口时”可设置为：

| 选项 | 行为 |
| --- | --- |
| 退出程序 | 隐藏窗口并清理接收服务，随后退出 |
| 隐藏到托盘 | 保持接收服务运行，通过托盘菜单重新打开窗口 |

托盘菜单中的“退出”始终完整退出程序。若系统托盘不可用，关闭窗口时会退出程序。

### 分辨率、帧率与编码

独立窗口标题栏示例：

```text
airplay - 视频 | 1554 × 1080 | 29.9 FPS | H.264
```

显示的尺寸来自实际视频流及解码结果，可能与手机屏幕的物理分辨率不同。尚未取得参数时显示检测提示，不使用配置中的最大分辨率或最大帧率代替实际值。

H.265 开关用于允许发送端协商 HEVC。实际发送的编码由设备决定，开启后仍可能收到 H.264。

## 配置

常用选项可以在界面设置页修改；需要重启的修改使用“保存并重启”生效。

发行包默认配置位于 [`config/application.properties`](config/application.properties)，示例配置位于 [`config/application.example.properties`](config/application.example.properties)。界面保存的接收器设置位于用户目录下的 `.java-airplay/application.properties`，其优先级高于发行包默认配置。修改默认配置后若没有生效，请检查已保存的用户设置。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `airplay.serverName` | `airplay` | 镜像接收器名称；音乐设备名称附加 ` Music` |
| `airplay.width` / `airplay.height` | `1920` / `1080` | 声明的最大视频尺寸；支持 `auto` |
| `airplay.fps` | `60` | 声明的最大帧率；支持 `auto` |
| `airplay.networkInterface` | `auto` | 自动选择网卡，或指定实际使用的网卡 |
| `airplay.requirePairing` | `true` | 要求完成配对验证后才能建立媒体会话 |
| `airplay.hevc` | `false` | 允许实验性 H.265 / HEVC 镜像 |
| `airplay.audioJitterPackets` | `3` | 音频乱序缓冲包数量 |
| `player.gstreamer.videoDecoder` | `auto` | 自动选择解码器，或指定受支持的解码器 |
| `player.gstreamer.gpuAdapter` | `auto` | 自动选择 GPU，或指定 DXGI 适配器索引 |
| `player.gstreamer.videoQueueDepth` | `2` | Java 输入侧视频队列深度，允许 `1`–`16` |
| `player.gstreamer.renderMode` | `balanced` | 支持 `balanced`、`quality`、`low-latency` |
| `airplay.airplay2.port` | `7001` | 实验性音乐后端的 TCP 控制端口 |

GPU 的 DXGI 索引不一定与任务管理器中的 GPU 编号一致。手动选择 GPU 时，必须存在对应的 GStreamer 硬件解码器。

`balanced` 为默认呈现模式；`quality` 不主动丢帧；`low-latency` 优先立即呈现，可能增加抖动或撕裂。调整缓冲和队列大小会影响延迟与播放稳定性。

在项目目录打开 PowerShell，可使用指定配置启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -ConfigPath .\config\application.properties
```

用户已保存的接收器设置仍可能覆盖指定文件中的同名选项。语言偏好通过 Java Preferences 保存，不写入 `config/application.properties`。

## 数据与日志

为兼容已有配置和配对，用户数据目录继续使用 **`%USERPROFILE%\.java-airplay`**。

| 路径 | 用途 |
| --- | --- |
| `.java-airplay/application.properties` | 界面保存的接收器配置 |
| `.java-airplay/desktop.properties` | 关闭行为、音乐接收开关等桌面偏好 |
| `.java-airplay/identity.key` | 镜像接收器的持久身份 |
| `.java-airplay/airplay2/` | 音乐接收器身份、配对及相关状态 |
| `.java-airplay/airplay.log` | 主程序日志，默认记录警告和错误 |
| `.java-airplay/airplay2/receiver.log` | 音乐后端日志，每次启动覆盖 |
| 项目目录 `logs/` | 启动器、Java 标准输出及原生组件错误日志 |

主日志单文件滚动阈值为 `2MB`，历史保留一天，历史文件总量上限为 `4MB`。音乐后端不再单独生成音频、控制和事件调试日志。启动脚本会清理已停用的旧日志。

保留身份文件与配对数据可以让设备在重启后继续识别同一个接收器。旧默认设备名称 `Java AirPlay` 会在读取用户设置时映射为 `airplay`，自定义名称保留。

## 常见问题

### 手机找不到设备

- 确认电脑和手机位于同一局域网，路由器未启用客户端隔离。
- 检查防火墙是否允许程序访问当前网络。
- 多网卡、VPN 或移动热点环境下，在设置中选择手机实际连接的网络适配器，保存并重启。
- 区分“屏幕镜像”列表中的 `airplay` 与音乐输出列表中的 `airplay Music`。

### 有声音但无画面，或视频播放异常

先使用默认的 H.264 协商、自动解码器、自动 GPU 与 `balanced` 模式。手动指定解码器或 GPU 后出现问题时，恢复自动选择并重启，再查看主程序和 `logs/java.stderr.log` 中的错误。

### 无法启动或音乐接收失败

检查目录是否完整，再查看 `logs/launcher.log`、`logs/java.stderr.log`；音乐接收失败时查看 `.java-airplay/airplay2/receiver.log`。

需要前台诊断输出时运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Console
```

普通启动使用运行时文件快速检查。需要完整检查 Java 架构及 GStreamer 插件时运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime.ps1 -CheckOnly
```

### 退出后进程没有立即消失

窗口会先隐藏，网络服务注销、播放管线关闭和音乐子进程回收在后台继续进行，通常需要数秒。程序保留 30 秒退出超时兜底机制。

## 目录结构

```text
airplay/
├── start.exe                   # Windows 桌面启动入口
├── airplay-server-1.1.2.jar     # 主程序
├── config/                     # 默认配置与示例
├── runtime/                    # 内置 Java 25 运行时
├── .runtime/
│   ├── gstreamer/              # 视频与音频播放环境
│   └── airplay2/               # 实验性音乐后端及便携 Python
├── scripts/                    # 启动与运行时维护脚本
├── maintenance/                # 补丁源码、构建工具与验证记录
├── logs/                       # 启动诊断日志
├── LICENSE
└── README.md
```

## 构建与维护

当前包采用发行 JAR 加补丁源码的维护方式，未包含完整的原始 Gradle 工程。正常使用无需构建。

重新编译并打包补丁需要 JDK 25、Python 3.9+ 和 Windows .NET Framework C# 编译器。在项目目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\maintenance\rebuild.ps1 -JdkBin 'C:\Program Files\Java\jdk-25.0.5.0.2\bin'
```

构建过程从 `maintenance/java-airplay-server-1.1.2.original.jar` 提取依赖，编译补丁，生成根目录中的主程序 JAR 和启动器。请保留该原始 JAR。

重新生成内置 Java 运行时：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\maintenance\rebuild-runtime.ps1 -JdkHome 'C:\Program Files\Java\jdk-25.0.5.0.2'
```

默认输出到尚不存在的 `.runtime/runtime-build` 目录。确认构建成功并关闭程序后，再替换 `runtime`。Windows 运行时需包含 `jdk.crypto.mscapi`，并使用脚本配置的原生安全随机数提供器，以避免缓慢的随机数初始化回退。

## 使用限制

- AirPlay 2 音乐接收属于实验功能，**尚未实现精确的多设备 / 多房间同步**。
- 开启 HEVC 不保证发送端使用 H.265，具体结果取决于发送端协商。
- 本地协议与解码测试不等同于真实设备兼容性验证。
- 当前仅面向 Windows x64。

## 许可与第三方组件

项目根目录保留 MIT 许可证，详见 [`LICENSE`](LICENSE)。GStreamer、Java 运行时、Python 及音乐后端依赖适用各自的许可证；分发时应保留对应组件的版权与许可文件。
