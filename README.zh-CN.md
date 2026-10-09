# TiPlay

**用安卓手机把 CarPlay 送到特斯拉车载浏览器，并继续支持兼容的比亚迪安卓车机。** 独立应用：`com.ujzk.tiplay`。

特斯拉浏览器模式正在开发，尚未发布。在这个模式下，安卓手机运行 TiPlay 并开热点。iPhone 以无线 CarPlay 连接这台手机，特斯拉连接同一个热点，用车载浏览器显示 CarPlay，并把触摸操作传回手机。手机不转码，直接把 iPhone 的 H.264 或 HEVC 视频交给浏览器；音乐、导航语音、Siri 和电话仍走 iPhone 与特斯拉之间的蓝牙。方案和实测记录见 [docs/todo.md](docs/todo.md)。TiPlay 同时保留 DiPlay 面向兼容比亚迪安卓车机的 CarPlay 功能，见下文。

TiPlay 基于 shihabal3amri 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）。本项目与 Tesla, Inc.、Apple Inc. 及比亚迪无隶属关系，也未获其认可。Tesla 和 CarPlay 是其各自所有者的商标。

> 车机模式专注于比亚迪汽车。它可能在其他品牌上运行，但其他品牌不在支持范围内，也没有增加支持或修复其品牌特定兼容性问题的计划。

[版本发布](https://github.com/uJZk/DiPlay/releases) · [完整说明（英文）](README.md) · [报告问题](https://github.com/uJZk/DiPlay/issues/new/choose) · [上游 DiPlay](https://github.com/shihabal3amri/DiPlay)

## 当前状态

TiPlay 还没有发布版本，现在试用请[从源码构建](docs/BUILD.md)。TiPlay 以 DiPlay 0.2.13 为起点，使用独立包名 `com.ujzk.tiplay`，因此可与 DiPlay（`com.shihab.diplay`）同时安装，但不会沿用 DiPlay 的设置、配对记录或更新。请一次只运行一个投屏应用。

## 特斯拉浏览器模式（开发中）

- **安卓手机**：运行 TiPlay 并开启 Wi-Fi 热点。从特斯拉浏览器访问手机需要 root：浏览器拦截 192.168.x.x、10.x.x.x 等私有地址，所以提供了一个可选设置，给热点网卡加一个 100.64.0.0/10 内的地址。
- **iPhone**：通过蓝牙连接特斯拉并设为主电话，用于声音和通话；同时以无线 CarPlay 连接 TiPlay。
- **特斯拉**：连接手机热点，在浏览器中打开 TiPlay 页面（`https://ujzk.github.io/DiPlay/`）。页面将从公网 HTTPS 地址加载一次，之后使用浏览器缓存。
- **目前的实测**：在 Model Y（Chromium 148）上，探测页能硬解 1080p60 的 H.264 和 HEVC，挂 D 挡时 H.264 保持 60 fps。特斯拉浏览器中的 HTTPS 页面已能通过 HTTP 访问热点上的 100.64 地址。CarPlay 本身还没有在特斯拉上跑通。
- **设置**：发布页面、配对、其他浏览器和实车测试清单见 [Tesla browser](docs/TESLA_BROWSER.md)（英文）。

## 车机模式（继承自 DiPlay 0.2.13）

请安装在允许 APK 安装的 Android 9+（API 28+）车机上。无需越狱、转接盒、账户或认证服务器。有线及无线核心连接不要求 ADB；可选车辆数据及车辆控制需要支持的固件和已授权网络 ADB。

Wi-Fi Direct 现支持 Android 9 的旧版建组路径，使用系统返回的真实凭据。首选信道依赖固件 API；Android 9 无法读回协商频率，所以请求信道在诊断中标为未经验证，系统默认为信道 0。Android 10+ 保留频率验证。也可使用车机内置热点、USB 或[现有 Wi-Fi／同一局域网](docs/EXISTING_WIFI.md)；同一局域网模式由车机和 iPhone 自行连接外部路由器，TiPlay 不替你修改默认路由。[Android 9 Wi-Fi Direct 限制](docs/ANDROID9_WIFI_DIRECT.md)说明清理及持久配置边界。

### DiPlay 0.2.13 新增与修正

- **连接与恢复**：热点优先公布当前 IPv4，保留带作用域的 IPv6 回退；5 GHz Wi-Fi 客户端旁的自动信道优先顺序更保守，但不保证固定频段或消除卡顿。无线启动不再依赖未使用的 NSD／USB 服务；蓝牙交接回退仅保留已渲染视频的会话。
- **USB 与权限**：在原活动内安全切换无线与 USB，检查 VPN 等有线前提；逐项核实 ADB 权限配置、保留其他无障碍服务，并取消过期请求。USBMUX 四字节尾部恢复只接受已验证的 TCP 帧并保留有效载荷。
- **布局与画面**：可选固定 Dock／分屏视区及方形画布旋转；只有选中的解码器可通过能力探测。侧边面板为实验选项，默认关闭。DiLink 4 保留原生投射模式、支持连接前校准及实时仪表画面调整。
- **仪表与车辆**：无线会话替换时在过期窗口内保留最近转向卡片，真实到达、手动结束及有线断开仍清除。新增 125% 流尺寸以显示更细的地图；DiLink 3 按已检查的完整／半屏顺序进入，失败立即尝试恢复。增加电池协议备用属性及符合条件的方向盘服务恢复。
- **音频与触摸**：针对已捕获的 24 kHz Siri 流修正 RTP 时间戳；通话及未经验证的 Opus 格式继续使用 48 kHz。触摸事件通道启用 TCP_NODELAY。相关效果仍依赖实际车机、网络和 iOS。
- **默认关闭的实验功能**：DiLink 3 通话键及仪表通话卡片为两个独立开关；车机写入必须先获得已初始化观察进程的就绪确认，并保留失败恢复所有权。AAC-LC 缓冲音乐需要主动启用并重连，不是无损音频。它们仍需实际通话、麦克风、恢复及音乐中断测试反馈。
- **热点自动加入修复**：只在符合要求的 Android 13+、用户配置的 5 GHz 热点上提供明确的检查／应用／恢复操作；要求热点关闭、无活动会话、支持的 API 和已授权本地 ADB，并保留完整私有备份。打开设置或启动 CarPlay 不会自动修改热点。
- **界面与语言**：修正全尺寸多窗口主页、设置可见性及预览保存；主设置页面可修改 CarPlay 返回车辆按钮，等待画面跟随 CarPlay 昼夜模式。应用及发布网站新增繁体中文（台湾），共七种语言。

[DiPlay 0.2.13 完整说明](docs/RELEASE-NOTES-0.2.13.md)包含全部 35 项贡献及验证边界，最终发布检查见[验证记录](docs/VALIDATION.md)。可选功能请停车后测试。不宣称所有秦／海豹／汉／唐固件、一般卡顿、Siri 或旧版 iOS 问题均已解决。

### 报告问题

TiPlay 的问题请报告到本仓库，不要报告到 DiPlay。

1. 用最新的 TiPlay 构建复现问题并记录发生时间。开机／自动启动问题发生后，可手动打开 TiPlay 导出。
2. 打开“**设置 → 诊断 → 保存诊断报告**”。Android 10+ 通常保存到 **Downloads/TiPlay**；Android 9 使用文件选择器，也可点“选择保存位置”。如选择器或公共存储不可用，应用会使用专用外部或私有目录，并在确认中说明目的地。
3. 使用确认中的**查看报告／分享**；没有分享应用时，可在报告视图中选择并复制文本。检查 `.txt` 并删除隐私信息，再附到匹配的[现有问题](https://github.com/uJZk/DiPlay/issues)，或[新建问题](https://github.com/uJZk/DiPlay/issues/new/choose)。报告不会自动上传，请勿公开热点密码或私有认证文件。
4. 注明车型／车机、DiLink/Android/完整固件版本、iPhone/iOS、USB／车机热点／Wi-Fi Direct／同一局域网、相关设置、复现步骤、预期与实际结果及故障时间。

[从源码构建](docs/BUILD.md)：主应用请选择 `mobile` 模块。`maphost` 是地图演示应用，构建步骤和 APK 路径见说明。

历史记录：[DiPlay 0.2.12](docs/RELEASE-NOTES-0.2.12.md)、[DiPlay 0.2.11](docs/RELEASE-NOTES-0.2.11.md)、[安装与连接](docs/INSTALL.md)。

TiPlay **未经 Apple 认证**。APK 使用从公开 Carlinkit 固件中提取的既有实验性配件身份，并非为 DiPlay 或 TiPlay 新签发的 MFi 身份；其中的私钥可被提取，未来 iOS 是否继续接受及其公开分发适用性尚未确定。Android 签名密钥和配件身份不进入 Git 或源代码压缩包；普通源代码/CI 构建默认不配置身份。部分车机仍可能卡顿或无法应用图标大小设置。

标准导航小组件需要支持 Android 小组件的启动器；比亚迪内置主页不接受任意小组件。悬浮地图和嵌入地图需要启用“CarPlay 仪表地图”。应用及发布网站支持英语、简体中文、繁体中文（台湾）、阿拉伯语、俄语、乌克兰语和西班牙语。应用的香港／澳门及 Hant 选择使用台湾译文，不宣称提供独立地区翻译。源代码、构建说明及许可证随版本提供。

## 来源与致谢

TiPlay 是 shihabal3amri 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）的修改版本，于 2026 年 10 月改名。DiPlay 基于 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0）；主页／设置界面和网站改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0），许可证见 `docs/licenses`。分发修改版时请保留这些声明。DiPlay 的更新日志和版本说明保留原名。Tesla 是 Tesla, Inc. 的商标；CarPlay 及其图标归 Apple Inc. 所有。
