# TODO

## 特斯拉浏览器 CarPlay：声音走车辆蓝牙

背景：安卓手机运行 TeslaPlay 并开热点，特斯拉连接热点后用浏览器显示 CarPlay。
安卓手机只负责视频和触摸。音乐、导航、Siri、电话和麦克风全部走 iPhone 与特斯拉之间的蓝牙。

### 依据：Carlinkit 固件的 `BtAudio` 模式

静态分析对象：[ludwig-v/wireless-carplay-dongle-reverse-engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)
仓库中已解密的 `Firmware/U2AW/_AUTOKIT/2024.01.10.0953/Decrypted/AppleCarPlay`（ARM Thumb）。

- `riddle.conf` 的 `BtAudio`（0/1）是"蓝牙音频"开关，见该仓库的 `Updater-App.md`。
- `0x2cfe8`：读取配置 `BtAudio`，判断是否等于 1。
- 整个二进制只有一处调用：`0x1ae18`，位于生成 `audioFormats` 的属性函数 `0x1adec` 的开头。
  - `BtAudio == 1`：跳到 `0x1b4d0`，返回空且不报错，即 `/info` 中**没有 `audioFormats`**。
  - `BtAudio == 0`：照常生成类型 100/101 的各类音频流（`compatibility`、`default`、`alert` 等）。
- `audioLatencies`、`features`、`bluetoothIDs`、`displays` 不受影响。
- `ARMiPhoneIAP2`、`bluetoothDaemon`、`hfpd` 中只在通用配置键表里出现 `BtAudio`，没有找到按名称读取它的代码。
  如果它们按表格序号读取配置，静态搜索查不出来。

局限：
- 只是静态分析，没有在真机上验证。
- 2025 年 A15W 固件在仓库里没有解密版，无法确认它的逻辑相同。

### TeslaPlay 现状

`AirPlayConfig.disableAudioOutput`（`shared/.../airplay/AirPlayConfig.kt`）目前只在测试中使用，界面上没有开关。它和 Carlinkit 的差别：

| | Carlinkit `BtAudio=1` | TeslaPlay `disableAudioOutput` |
|---|---|---|
| `audioFormats` | 去掉 | 去掉 |
| `audioLatencies` | 保留 | 去掉 |
| `features` 中的音频位（`CARPLAY_AUDIO_FEATURES`） | 保留 | 清除（Bonjour TXT 和 `/info` 都清除） |
| `bluetoothIDs` | 保留（转接盒自身地址） | 保留（安卓手机地址） |

### 待办

- [x] 在 `AirPlayConfig` 新增一个字段（例如 `audioViaCarBluetooth`），在 `AirPlayInfoPlist.build` 中**只去掉 `audioFormats`**，`audioLatencies` 和 `features` 保持不变，与 Carlinkit 一致。
- [x] 保留 `disableAudioOutput` 作为备选变体，实测两者哪个让 iPhone 稳定地把声音交给车辆蓝牙。
  设置里选"开启（备选方式）"即为该变体；两者的对比放在下面的真机验证里。
- [x] 该模式下不启动音频接收、麦克风上行和回声消除，也不请求音频焦点（`AndroidMediaSink`、`MicrophoneUplink`）。
  iPhone 仍然打开的音频流会被拒绝（不放进 SETUP 响应）。
- [ ] 如果真机上 iPhone 在音频 SETUP 被拒绝后断开会话：改为接受但丢弃（绑定端口，不解密、不播放）。
- [x] 按 `AGENTS.md` 加设置项：
  - 放进 Audio 分类。
  - 下次连接时生效，保存后调用 `markReconnectNeeded()`，描述里说明需要重新连接。
  - 所有语言补齐 `settings_*` 文案。
  - 更新 `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`。
- [x] 单元测试：开启该模式时 `/info` 没有 `audioFormats`，但有 `audioLatencies`，且 `features` 不变。
- [x] 诊断日志记录 iPhone 实际打开的音频流（期望为空）。
  报告中的 `airplay /info audioRoute=…`、`airplay audio SETUP …` 和 `airplay audio summary …` 行。
- [ ] 真机验证（前提：iPhone 用蓝牙连特斯拉并设为主电话，同时以无线 CarPlay 连接 TeslaPlay）：
  - [ ] 对比"开启"和"开启（备选方式）"，记录哪个让 iPhone 稳定地把声音交给车辆蓝牙。
  - [ ] 音乐和导航从特斯拉的蓝牙媒体音频出声。
  - [ ] Siri 用特斯拉麦克风收音，回答从车上出声。
  - [ ] 来电和去电走特斯拉免提，CarPlay 通话界面正常。
  - [ ] 记录特斯拉软件版本和 iOS 版本。
- [ ] 跑 `AGENTS.md` 中的 CI 命令。

## 特斯拉浏览器 CarPlay：其余待定事项

- [x] 收集特斯拉浏览器探测报告（报告 `d73a0959`，2026-10-08）。探测页源码只放在会话临时目录，没有提交，需要时重新生成。
  - 车辆：Model Y，AMD Ryzen（GPU：Radeon Vega），Chromium 148，HTTPS 安全上下文。屏幕 1254×784，视口 773×601，DPR 1.53。8 核。
  - 硬解：H.264、HEVC、VP9 都支持。1080p60 实时解码 60 fps，解码延迟 p50 0.9 ms、p95 约 1.5 ms；极限吞吐 210–225 fps。
  - AV1：只有软解，60 fps，延迟 p50 7.4 ms。不作为主路径。
  - HEVC 只能硬解，没有软解。
  - 渲染：WebGL 绘制 p95 0.4 ms，Canvas 2D 0.6 ms。WebGPU API 存在但拿不到 adapter。Worker 比主线程的偶发尖峰小（max 30 ms 对 51 ms）。
  - 挂 D 挡（报告 `8b2fbb80`）：D 挡连续 22 秒，H.264 硬解 + WebGL 稳定 60 fps，页面刷新 60 fps，页面保持可见，Web Audio 持续运行；解码延迟 p50 1.5 ms、p95 3.7 ms。用户目测画面和声音都正常。
  - 私有 IP：`http://10.176.81.135:8080` 打不开，与"拦截 RFC1918"一致。还没排除热点 AP 隔离的可能。
  - API：WebTransport、WebRTC、OffscreenCanvas、WASM SIMD、AudioWorklet 都有；SharedArrayBuffer 没有。
  - 声音：Web Audio 能出声，基础延迟 42.7 ms。
  - 触摸：最多 10 点，移动事件约 306 次/秒。
  - 补测（报告 `a98934e0`）：WSS 可用，到 Cloudflare 往返中位 178 ms（走公网，不代表热点内延迟），下行 96 Mbps；WebRTC 数据通道可用。这次视口为 1256×706，说明浏览器窗口大小会变，需要监听 resize 重新协商分辨率。
  - 私有 IP（报告 `8b2fbb80`）：另一台设备能打开 `http://10.176.81.135:8080`，排除了 AP 隔离，确认是特斯拉浏览器拦截私有地址。
- [x] **前置实测（报告 `305294d4`，2026-10-09）**：热点网卡加 `100.109.220.253/32` 后，特斯拉（热点内地址 10.176.81.89，直连、未经 VPN）：
  - **UDP 能到达手机**：WebRTC 往 `100.109.220.253:8080` 发的 STUN 包全部被手机收到（来源端口 1034）。方案 B 成立。
  - **TCP 也能到达**：公网 HTTPS 页面 `fetch("http://100.109.220.253:8080/…")` 成功并读到响应内容（21 ms）；
    `https://` 打到 8080 端口时手机收到了 TLS 握手；ICE-TCP 连接也到达了。
  - 没有弹出"本地网络访问"授权提示。`ws://` 没有到达手机（被浏览器拦截）。
- [ ] 确认 iPhone 发来的码流格式：在诊断日志中记录配置头的格式标记（`avcC`/`hvcC`/其他）。
- [ ] 视频链路：新增 `WebMediaSink`，不转码，直接把 iPhone 码流（H.264 或 HEVC）通过 HTTP 响应流送给浏览器；浏览器在 Worker 里用 `fetch` 读取，用 WebCodecs 硬解，用 WebGL 画到 OffscreenCanvas；发送端积压时丢帧到下一个关键帧。
  分辨率按浏览器上报的视口 × DPR 协商，60 fps。
- [ ] 网络：按下面"热点地址"和"传输层"的决定实现。
- [ ] 触摸：Pointer Events 多点触控，每次 `requestAnimationFrame` 合并发送一次，换算后调用 `CarPlayController.sendTouch`。

## 已定的决定

- **传输层用 HTTPS 页面 + HTTP fetch 流（方案 D，2026-10-09，取代此前的 WSS 和 WebTransport 方案）**：
  - 原因：WebCodecs 只在安全上下文可用，所以页面必须是 HTTPS；CA 不会给 100.64 地址签证书，`ws://` 又会被混合内容拦截。
    Chrome 对"发往本地 IP 字面量的 `fetch`"豁免混合内容检查，并把 100.64.0.0/10 视为本地地址，特斯拉的拦截名单也不含 100.64，
    所以 HTTPS 页面可以直接 `fetch("http://100.109.220.253:8080/…")`（报告 `305294d4` 实测成功，无授权弹窗）。
    参考：https://developer.chrome.com/blog/local-network-access
  - 页面：静态 HTTPS 页面，托管在国内不开代理也能访问的地址（自定义域名，不用 `workers.dev`），用 Service Worker 缓存，首次加载后不依赖外网。
  - 视频：页面主线程 `fetch("http://100.109.220.253:8080/video?…", { targetAddressSpace: "local" })`，把 `response.body`（可转移的 `ReadableStream`）`postMessage` 转交给解码 Worker，由 Worker 读取；
    这样请求从页面发起，确定享受豁免，读取和解码仍在 Worker 里。手机返回不定长的 HTTP/1.1 响应，
    持续写入帧；每帧带长度前缀和帧头（时间戳、关键帧标记、序号、编码），页面用 `ReadableStream` 读取后重组。
  - 控制和触摸：用短 `fetch` POST（keep-alive），按 `requestAnimationFrame` 合并发送；使用"简单请求"（如 `text/plain`），避免 CORS 预检。
    不用流式上传：Chrome 只在 HTTP/2 以上支持流式请求体。
  - 手机端：普通 HTTP 服务器，监听 8080，开 TCP_NODELAY，发送缓冲设小，积压时丢帧到下一个关键帧并向 iPhone 请求关键帧；
    返回 CORS 响应头（含 `Access-Control-Allow-Private-Network`），以备浏览器发出本地网络预检。
  - 配对：URL 参数带配对码，只允许一个控制端。热点有 WPA 加密，明文 HTTP 的风险可以接受。
  - 不需要任何证书、域名解析或证书服务器。
  - 风险（都不受我们控制）：Chrome 取消或收紧这条混合内容豁免；特斯拉以后开始弹授权提示（只需用户允许一次）；特斯拉的拦截名单加入 100.64。
  - Worker 里直接发起 `fetch` 是否享受豁免：公开资料没有定论（2026-10-09 查证）。LNA 规范说该机制同样适用于 Worker，
    但授权检查依赖关联的 Document；Chrome 文档只说明 Service Worker 和 Shared Worker 需要其来源事先获得授权，未提及 Dedicated Worker。
    规范把 100.64.0.0/10 列为 local。因此采用上面"主线程发起、转交流给 Worker"的做法，绕开这个不确定性。
  - 待实测：长时间响应流是否会被浏览器或特斯拉中途断开。
  - 备选：方案 B（WebTransport + 证书指纹，UDP 已实测可达），代价是证书最长 14 天要轮换、安卓端要集成 HTTP/3。
- **以 DiPlay 为基础开发，不以 [WheelPlay](https://github.com/fython/wheelplay) 为基础**：
  - WheelPlay 基于较旧的 DiPlay 快照，没有共同 git 历史，缺少后来的热点和无线可靠性修复，难以再合并上游修复。
  - 它的主视频路线是 WebRTC → `<video>` → `drawImage`，按其他项目的报告，特斯拉挂挡后 `<video>` 会暂停（推断，未在本车验证）。
  - 它的音频走安卓或浏览器，和"声音走车辆蓝牙"的方案不同。
- [ ] 从 WheelPlay 按需移植以下组件（GPL-3.0，保留署名）：
  - `LanWebServer`：手机端 HTTP 服务器（只提供视频流和控制接口，页面改为公网静态托管）。`LanTls` 不再需要。
  - `QrPairing`、`RememberedBrowsers`、`TouchLease`：扫码配对、记住已配对浏览器、单一控制端。
  - `EncodedVideoSink`、`CompressedVideoFrame`：从媒体层接出不解码的码流，作为 `WebMediaSink` 的基础。
  - 触摸坐标换算，以及浏览器端统计面板（参考 `window.wheelplayStats`）。
  - 不移植：WebRTC 原生代码（`rtc_bridge.cpp`、libdatachannel）、`<video>` 转画布渲染、JPEG 兼容模式、浏览器音频。
- [ ] 增加"手机 + 浏览器"运行模式：跳过 BYD HUD、仪表、ADB、方向盘按键等车机专属功能。
- **热点地址**（2026-10-09）：
  - 设置项"热点地址增加"，默认关闭；关闭时不需要 root。
  - 关闭时显示当前热点 IP；不在 100.64.0.0/10 内时，提示"特斯拉浏览器很可能无法访问"。
    依据：特斯拉浏览器在车内直接拦截 10/8、172.16/12、192.168/16、127/8 的访问（报告 `334dac05`、`b549281c`、`1d728b90`），
    直接访问 IPv6 地址报 `ERR_ACCESS_DENIED`，100.64.0.0/10 未被本地拦截。
  - 开启时需要 root，给热点网卡加 `100.109.220.253/32`（默认值，可修改）；特斯拉浏览器用这个地址打开 CarPlay 页面。
  - 网卡名不要写死 `wlan2`，按当前热点网卡的地址查找。热点重启后地址会消失，需要重新添加。
  - 端口统一用 8080（普通应用可以监听，不需要 iptables 重定向）。视频流和控制都走 HTTP（TCP 8080）。
- 不依赖 root 的备选（没有可用的私有地址绕过方式时）：云端中转，延迟 +100 ms 以上、双向流量约 7 GB/小时（8 Mbps），
  在国内需使用能直接访问的服务器（`workers.dev` 在国内无法直连）。
