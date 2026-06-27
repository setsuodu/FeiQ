# LanChat · 局域网飞聊

类飞秋（FeiQ）的局域网即时通讯工具，支持 **文字 / 图片 / 文件** 互传，无需服务器，P2P 直连。

---

## 项目结构

```
LanChat/
├── WPF/          # Windows 客户端（C# + WPF + .NET 8）
│   └── LanChat.WPF/
│       ├── Models/      Protocol.cs         # 协议、数据模型
│       ├── Services/    NetworkService.cs   # UDP发现 + TCP传输
│       ├── ViewModels/  MainViewModel.cs    # MVVM 逻辑
│       ├── Views/       MainWindow.xaml     # WPF 界面
│       └── Converters/  Converters.cs       # 值转换器
└── Kotlin/       # Android 客户端（Kotlin + Jetpack Compose）
    └── app/src/main/kotlin/com/lanchatkotlin/
        ├── model/       Protocol.kt         # 协议、数据模型
        ├── service/     NetworkService.kt   # UDP发现 + TCP传输
        ├── viewmodel/   ChatViewModel.kt    # ViewModel 逻辑
        ├── ui/screens/  MainScreen.kt       # Compose UI
        └── MainActivity.kt
```

---

## 通信协议

```
┌─────────────────────────────────────────────────────┐
│                   局域网 LAN                         │
│                                                     │
│  设备A                              设备B            │
│  ┌──────┐   UDP 广播 :52000        ┌──────┐         │
│  │      │ ──────────────────────> │      │  发现     │
│  │      │ <────────────────────── │      │  心跳5秒  │
│  │      │                         │      │          │
│  │      │   TCP 点对点 :52001      │      │          │
│  │      │ ──────────────────────> │      │  消息     │
│  │      │   [4字节长度][JSON帧]    │      │  图片     │
│  │      │   [文件字节流]           │      │  文件     │
│  └──────┘                         └──────┘         │
└─────────────────────────────────────────────────────┘
```

### TCP 帧格式

```
┌──────────────┬──────────────────────────────────────┐
│  4 bytes     │  N bytes                             │
│  msgLen (LE) │  TcpMessage JSON                    │
│              │  (如为文件：紧跟 fileSize 字节的内容)  │
└──────────────┴──────────────────────────────────────┘
```

### 消息类型

| type       | 说明                          |
|------------|-------------------------------|
| ONLINE     | 上线广播                       |
| OFFLINE    | 下线广播                       |
| HEARTBEAT  | 心跳（每5秒）                  |
| TEXT       | 文字消息                       |
| IMAGE      | 图片（base64 content 字段）    |
| FILE_REQ   | 文件传输（JSON后紧跟文件字节流）|

---

## 快速开始

### WPF（Windows）

**依赖：** .NET 8 SDK、Visual Studio 2022

```bash
cd WPF/LanChat.WPF
dotnet restore
dotnet run
```

**功能：**
- 左侧面板：自动发现局域网用户
- 右侧聊天区：文字（Enter发送）、图片、文件
- 工具栏：🖼️ 图片 / 📎 文件按钮
- 文件接收后点气泡可在资源管理器中定位

### Kotlin（Android）

**依赖：** Android Studio Hedgehog+、compileSdk 35

1. 在 Android Studio 打开 `Kotlin/` 目录
2. 同步 Gradle → Run

**文件接收路径：** `/sdcard/Download/LanChat/`

---

## 功能列表

- [x] UDP 广播自动发现局域网用户（5秒心跳）
- [x] 上线 / 下线通知
- [x] 文字消息（TCP 点对点）
- [x] 图片发送（base64，适合小图；大图建议用文件发送）
- [x] 文件发送（任意大小，流式传输，进度显示）
- [x] 聊天记录（会话内保留，重启清空）
- [x] WPF ↔ Android 互通
- [x] WPF ↔ WPF 互通
- [x] Android ↔ Android 互通

## 扩展方向

- [ ] 头像设置（base64 随 UDP 包广播）
- [ ] 消息通知（Windows Toast / Android Notification）
- [ ] 消息持久化（SQLite）
- [ ] 图片压缩（大图走文件通道）
- [ ] 传输加密（TLS）
- [ ] 群组广播消息
- [ ] macOS/Linux 客户端（Avalonia UI）

---

## 注意事项

1. **防火墙**：Windows 首次运行会弹出防火墙提示，需允许 UDP 52000 / TCP 52001
2. **同一子网**：两端需在同一 Wi-Fi / 局域网下
3. **Android Wi-Fi**：确保 Wi-Fi 已连接，需 `CHANGE_WIFI_MULTICAST_STATE` 权限
4. **大文件**：文件通过流式 TCP 传输，理论上无大小限制，受限于局域网带宽
