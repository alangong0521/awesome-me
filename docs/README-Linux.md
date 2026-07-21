# Linux 移植说明(server / Ubuntu,GNOME)

原方案(见 `远程终端方案.md`)面向 macOS。Linux 差异如下,其余全通用。

## 与 macOS 版的差异

| 项 | macOS | Linux(server 实测) |
|---|---|---|
| 自启动 | launchd plist(`com.calla.remote-terminal.plist`) | systemd user service(见下) |
| CLI 路径 | `/Users/yourname/...` | kimi=`~/.kimi-code/bin/kimi`,claude=`~/.local/bin/claude`,tmux=`/usr/bin/tmux` |
| tmux 复制绑定 | `pbcopy` | 不需要,server 已有 autocutsel 同步 PRIMARY↔CLIPBOARD |
| 防睡眠 | 需设置 | server 常开,不适用 |
| node-pty | 预编译 | 原生编译,需 `build-essential python3` |

## 安装(server 已执行)

```bash
mkdir -p ~/remote-terminal
cp -r server ~/remote-terminal/
cd ~/remote-terminal/server
cp config.example.json config.json   # 改 token: openssl rand -hex 24
npm install                          # 编译 node-pty
```

systemd user service `~/.config/systemd/user/remote-terminal.service`:

```ini
[Unit]
Description=remote-terminal WebSocket-PTY server
After=network.target

[Service]
WorkingDirectory=%h/remote-terminal/server
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now remote-terminal
curl "http://127.0.0.1:7681/health?token=<token>"   # 应回 ok
```

手机端:同内网直接填 `192.168.1.10:7681`,外出走 Tailscale(两端装同账号)。
