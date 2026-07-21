---
name: remote-terminal
description: 手机/PC 远程终端——安卓 App(Termux terminal-view)或 rt-client.js 经 Tailscale 连 WebSocket-PTY 服务端,远程操控 kimi/claude/tmux/shell。覆盖手机→server、手机→家Mac、server→家Mac、家Mac→server 四场景。APK 公网下载链接+密码见下。
disable-model-invocation: true
---

# 远程终端(remote-terminal)

一套自建远程终端:服务端 = WebSocket-PTY(node-pty + ws),客户端 = 安卓 App 或 PC 端 `rt-client.js`,组网 = Tailscale。

## APK 公网下载(手机装这个)

- 链接: **https://nio-cva.duckdns.org/dl/remote-terminal/app-debug.apk**
- 密码: HTTP Basic 认证(浏览器会弹框),用户名/密码凭证私下分发,不在本仓库公开

手机浏览器打开链接 → 输用户名+密码 → 下载 → 安装(允许未知来源)。覆盖安装即可升级。

## 四场景速查

| 场景 | 客户端 | 目标 host | token |
|---|---|---|---|
| 手机→server(公司) | 安卓 App | `100.101.50.124:7681` | `change-me` |
| 手机→家 Mac | 安卓 App | `100.91.6.109:7681` | Mac 上 `~/remote-terminal/server/config.json` |
| server→家 Mac | `rt-client.js` | `100.91.6.109` | 同上 Mac 的 token |
| 家 Mac→server | `rt-client.js` | `100.101.50.124` | `change-me` |

PC 客户端用法(依赖 `npm i ws`):

```bash
RT_TOKEN=<token> node rt-client.js <host> [-a shell|kimi|claude|tmux] [-s tmux会话名]
```

App 连接后默认 **Shell 直达终端**:进登录 bash,自己敲 `kimi` / `claude` / `opencode` 或自定义 alias 启动任意 CLI;新建标签页也可选 kimi/claude/tmux 专属进程。

## 目录

- `server/` — 服务端源码 + `config.example.json`(apps 白名单含 shell/kimi/claude/tmux,可自行加 opencode 等)
- `client/rt-client.js` — PC 端客户端(Node)
- `docs/` — 原 macOS 方案文档、Linux 移植说明、四场景方案

## 服务端部署(新机器)

```bash
cp -r server ~/remote-terminal/server && cd ~/remote-terminal/server
cp config.example.json config.json   # 改 token + apps 路径
npm install                          # node-pty 原生编译,需 g++/make/python3
node server.js                       # 或配 systemd user service / launchd,见 docs/
```

完整细节见 `docs/README-四场景.md`、`docs/README-Linux.md`、`docs/远程终端方案.md`。
