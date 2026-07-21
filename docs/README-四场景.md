# 四场景远程终端方案(server ↔ Mac ↔ 手机)

tailnet 全组网后,四个场景共用一套:服务端 = remote-terminal server(WS-PTY),
客户端 = 安卓 App(手机)或 rt-client.js(PC)。

## 拓扑与凭证

| 节点 | tailnet IP | 服务端 | token |
|---|---|---|---|
| server(公司 Linux) | `<server的Tailscale-IP>` | systemd user `remote-terminal` ✅ | `change-me` |
| 家 Mac(my-macbook) | `<家Mac的Tailscale-IP>` | launchd `com.calla.remote-terminal` ✅(原方案已装) | 见 Mac 上 `~/remote-terminal/server/config.json` |
| 手机(my-phone) | Tailscale App 在线即可 | — (纯客户端) | — |

前置:两端 Tailscale 在线(`tailscale status`);Mac 不能睡眠(原方案 §5)。

## 场景 1:手机 → server ✅(已通)

App 填:主机 `<server的Tailscale-IP>` / 端口 `7681` / token `change-me`。

## 场景 2:手机 → 家 Mac(原方案,已具备)

App 填:主机 `<家Mac的Tailscale-IP>` / 端口 `7681` / token = Mac 的 config.json 里那个。
App 里「主机」字段换 IP 即可在两个服务端之间切换。

## 场景 3:server → 家 Mac

```bash
# server 上(client 已在 ~/remote-terminal/client/)
RT_TOKEN=<Mac的token> node ~/remote-terminal/client/rt-client.js <家Mac的Tailscale-IP> -a shell
# 或直接进 Mac 的 tmux 持久会话:
RT_TOKEN=<Mac的token> node ~/remote-terminal/client/rt-client.js <家Mac的Tailscale-IP> -a tmux -s mac-work
```

## 场景 4:家 Mac → server

```bash
# Mac 上首次:拷客户端 + 装 ws 依赖
mkdir -p ~/remote-terminal/client && cd ~/remote-terminal/client
# (从本仓库拷 client/rt-client.js 过来,或 server 上 scp 反向推)
npm install ws
# 之后每次:
RT_TOKEN=change-me node rt-client.js <server的Tailscale-IP> -a shell
```

## 双端同屏(手机↔本机无缝切入)✅ 已验证

需求:claude/tkimi 正在 CLI 里聊天,手机上要看到**一模一样**的上下文并无缝接手。
做法 = **把 CLI 跑在 tmux 会话里,双端 attach 同一会话**:

```bash
# server 本机(日常这样启动工作会话)
tmux new -s work          # 进去后跑 claude / tkimi / 任意 CLI
# 之后任何时候,本机另一个终端:
tmux attach -t work
```

手机端:App → 新建标签 → **tmux** → 会话名填 `work` → 看到的和本机是**同一块屏幕**,
光标/聊天上下文完全一致,哪边打字都行。server 服务端语义是 `tmux new-session -A -s work`
(存在即接入)。已实测:本机写的内容手机可见,手机(客户端)输入本机同步显示 ✓

窗口尺寸:已在本机 `~/.tmux.conf` 开 `aggressive-resize on` —— 手机连着时窗口取两端最小值,
手机断开/切标签后本机**立即恢复全尺寸**,无需手动干预。

要点:
- CLI 必须跑在 tmux 里才可共享(裸进程不行);养成 `tmux new -s work` 起手习惯
- rt-client.js(PC↔PC)同理:`-a tmux -s work` 接入同屏
- 旧习惯 `tmux attach` 不加会话名会开新会话,要显式 `-t work`

## rt-client.js 用法

```
node rt-client.js <host> [-p 端口] [-t token] [-a app] [-s tmux会话名]
```

- `-a` 可选服务端 config.json 里配的任意 app:`shell`(登录 bash,默认)/ `kimi` / `claude` / `tmux`
- 窗口缩放自动同步;`Ctrl+\` 本地强退;`Ctrl+C` 照常发给远端
- token 也可走环境变量 `RT_TOKEN`(推荐,免进 shell 历史)

## 服务端可加新 app

服务端 `config.json` 的 `apps` 是白名单,加一行即多一种可连程序,如:

```json
"opencode": ["/home/user/.local/bin/opencode"]
```

改完 `systemctl --user restart remote-terminal`(server)或 `launchctl kickstart -k gui/$(id -u)/com.calla.remote-terminal`(Mac)。
