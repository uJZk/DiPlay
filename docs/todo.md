# TODO

## 特斯拉浏览器 CarPlay：声音走车辆蓝牙

背景：安卓手机运行 DiPlay 并开热点，特斯拉连接热点后用浏览器显示 CarPlay。
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

### DiPlay 现状

`AirPlayConfig.disableAudioOutput`（`shared/.../airplay/AirPlayConfig.kt`）目前只在测试中使用，界面上没有开关。它和 Carlinkit 的差别：

| | Carlinkit `BtAudio=1` | DiPlay `disableAudioOutput` |
|---|---|---|
| `audioFormats` | 去掉 | 去掉 |
| `audioLatencies` | 保留 | 去掉 |
| `features` 中的音频位（`CARPLAY_AUDIO_FEATURES`） | 保留 | 清除（Bonjour TXT 和 `/info` 都清除） |
| `bluetoothIDs` | 保留（转接盒自身地址） | 保留（安卓手机地址） |

### 待办

- [ ] 在 `AirPlayConfig` 新增一个字段（例如 `audioViaCarBluetooth`），在 `AirPlayInfoPlist.build` 中**只去掉 `audioFormats`**，`audioLatencies` 和 `features` 保持不变，与 Carlinkit 一致。
- [ ] 保留 `disableAudioOutput` 作为备选变体，实测两者哪个让 iPhone 稳定地把声音交给车辆蓝牙。
- [ ] 该模式下不启动音频接收、麦克风上行和回声消除，也不请求音频焦点（`AndroidMediaSink`、`MicrophoneUplink`）。
- [ ] 按 `AGENTS.md` 加设置项：
  - 放进 Audio 分类。
  - 下次连接时生效，保存后调用 `markReconnectNeeded()`，描述里说明需要重新连接。
  - 所有语言补齐 `settings_*` 文案。
  - 更新 `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`。
- [ ] 单元测试：开启该模式时 `/info` 没有 `audioFormats`，但有 `audioLatencies`，且 `features` 不变。
- [ ] 诊断日志记录 iPhone 实际打开的音频流（期望为空）。
- [ ] 真机验证（前提：iPhone 用蓝牙连特斯拉并设为主电话，同时以无线 CarPlay 连接 DiPlay）：
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
  - 挂 D 挡：用户目测画面和声音都正常。报告里没有 D 挡标记，所以这一项没有数据佐证。
  - 私有 IP：`http://10.176.81.135:8080` 打不开，与"拦截 RFC1918"一致。还没排除热点 AP 隔离的可能。
  - API：WebTransport、WebRTC、OffscreenCanvas、WASM SIMD、AudioWorklet 都有；SharedArrayBuffer 没有。
  - 声音：Web Audio 能出声，基础延迟 42.7 ms。
  - 触摸：最多 10 点，移动事件约 306 次/秒。
  - WSS 往返和 WebRTC 数据通道没有测。
- [ ] 确认私有 IP 被拦截不是 AP 隔离造成的：从另一台设备访问同一个地址；再用 root 把热点改成 100.64.0.0/10 网段，复测。
- [ ] 确认 iPhone 发来的码流格式：在诊断日志中记录配置头的格式标记（`avcC`/`hvcC`/其他）。
- [ ] 视频链路：新增 `WebMediaSink`，不转码，直接把 iPhone 码流（H.264 或 HEVC）通过 WSS 送给浏览器；浏览器在 Worker 里用 WebCodecs 硬解，用 WebGL 画到 OffscreenCanvas；发送端积压时丢帧到下一个关键帧。
  分辨率按浏览器上报的视口 × DPR 协商，60 fps。
- [ ] 网络（手机已 root）：手机自己提供页面，用自有域名加 Let's Encrypt（DNS-01）证书；如果特斯拉拦截 RFC1918 私有地址，热点改用 100.64.0.0/10 网段。
- [ ] 触摸：Pointer Events 多点触控，每次 `requestAnimationFrame` 合并发送一次，换算后调用 `CarPlayController.sendTouch`。
