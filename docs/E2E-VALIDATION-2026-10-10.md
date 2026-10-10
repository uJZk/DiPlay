# 无设备 E2E 验证 — 2026-10-10

## 本次修复

两套测试启动 Chromium 时显式添加 `--no-proxy-server`。测试只访问本机服务，
包括容器的非 loopback IPv4 地址；系统代理会让 LAN 页面加载失败，并使 MSE 等待解码超时。

## 验证结果

当前工作目录（含本次开始前已有的 H.264 能力探测改动）完成以下验证：

- `tests/web-e2e/run.mjs`：`all checks passed`，退出码 0；包括 MSE 解码、触摸和错误恢复。
- `tests/web-e2e/phone-run.mjs`：`all checks passed`，退出码 0；包括真实 H.264 WebCodecs/MSE 解码、LAN、跨来源、会话恢复和退出。
- `node --test tests/web/*.test.mjs`：48 项通过，无失败、无跳过。
- `AGENTS.md` 要求的 Gradle 单测、lint 和三个 debug 构建：`BUILD SUCCESSFUL`。
- `python3 scripts/check_public_tree.py`：通过。

环境使用已有 Node 22、JDK 25、Chromium、ffmpeg，以及已配置的 SDK/Gradle 缓存。
浏览器测试需要允许本机监听端口。

## 剩余工作

本次只提交系统代理绕过修复及其说明；会话开始前已有的 H.264 能力探测修改
（`phone-run.mjs`、测试 README 和 `docs/todo.md`）保留在工作目录，待单独收尾。
上述测试结果针对含这些修改的工作目录，不代表仅含本次提交的检出版本。

无设备测试不替代真车验收。下一步仍是视频/触摸/蓝牙音频端到端联调，
30 分钟行驶、熄屏、热点重启、离线缓存及 Shizuku/VPN 地址方式实测。
