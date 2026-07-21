# awesome me — 手机远程终端

一套自建的远程终端方案:**安卓 App 经 Tailscale 加密组网连接 WebSocket-PTY 服务端**,从手机(或另一台 PC)远程操控服务器/Mac 上的 `kimi` / `claude` / `tmux` / 登录 shell。

- 服务端:Node.js + node-pty + ws,每个连接一个独立 PTY,token 鉴权
- 客户端:安卓 App(Kotlin + Termux terminal-view,多标签)或 PC 端 `rt-client.js`
- 组网:Tailscale(两端登录同一账号即可,无需公网 IP / 端口映射)

特性:

- **后台保活**:App 前台服务保持连接,切后台/锁屏不断线
- **tmux 命名会话双端接力**:服务端语义 `tmux new-session -A -s <名字>`,手机与本机 attach 同一会话,看到同一块屏幕,哪边打字都行;断开会话仍存活,重连继续
- **文件推送/下载**:App 支持手机 ↔ 服务端互传文件
- 多标签页、横竖屏切换、终端鼠标上报(SGR 1006)、双指缩放字号

## 目录结构

```
android-app/   安卓 App 源码(Gradle 工程,含 gradlew 与 debug.keystore;
               keystore 故意入库,钉死签名,任何机器重建的 APK 可直接覆盖安装)
server/        服务端 server.js + package.json + config.example.json
client/        rt-client.js —— PC 端客户端(Node.js)
docs/          方案文档:远程终端方案.md(macOS 原版)、README-Linux.md(Linux 移植)、
               README-四场景.md(server↔Mac↔手机四场景)、SKILL.md(速查)
mac/           com.calla.remote-terminal.plist(launchd 自启动)+ env.sh(Mac 端构建环境)
```

## APK 下载

编译好的 APK 在本仓库的 [**Releases**](https://github.com/alangong0521/awesome-me/releases) 页面,
下载 `app-debug.apk` 到手机安装(允许"安装未知来源应用"),覆盖安装即可升级。

也可以自行构建,见下文「自行构建 APK」。

## 服务端安装 — server / Linux

```bash
mkdir -p ~/remote-terminal
cp -r server ~/remote-terminal/
cd ~/remote-terminal/server
cp config.example.json config.json   # 改 token:openssl rand -hex 24;按需改 apps 路径
npm install                          # 编译 node-pty,需 build-essential python3
node server.js                       # 或配 systemd user service 开机自启
```

完整步骤(含 systemd user service 配置、健康检查)见 [docs/README-Linux.md](docs/README-Linux.md)。

## 服务端安装 — Mac

```bash
mkdir -p ~/remote-terminal
cp -r server ~/remote-terminal/
cd ~/remote-terminal/server
cp config.example.json config.json   # 改 token,apps 路径改成 Mac 上的实际路径
npm install
```

配置 launchd 开机自启(plist 见 [mac/com.calla.remote-terminal.plist](mac/com.calla.remote-terminal.plist),
先按实际用户名/路径修改其中的 `/Users/yourname/...`):

```bash
cp mac/com.calla.remote-terminal.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.calla.remote-terminal.plist
# 重启 / 停用:
launchctl kickstart -k gui/$(id -u)/com.calla.remote-terminal
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.calla.remote-terminal.plist
```

注意 **Mac 不能睡眠**(系统设置 → 电源 → 接通电源时防止自动睡眠),否则外出连不上。
细节见 [docs/远程终端方案.md](docs/远程终端方案.md) §5。

## 手机 App 使用

1. 安装 APK,手机装 Tailscale 并登录与服务端同一账号
2. 启动页填连接参数:
   - **主机**:服务端的 tailnet IP(`100.x.x.x`,服务端机器上 `tailscale ip -4` 可查);同局域网也可直接填局域网 IP
   - **端口**:`7681`
   - **Token**:服务端 `~/remote-terminal/server/config.json` 里的 token
3. 先点「测试连接」,成功后选程序进入终端
4. 程序选择:
   - **Shell 直达**:登录 bash,自己敲 `kimi` / `claude` 或任意命令
   - **kimi / claude**:各开一个全新进程
   - **tmux**:填会话名 —— 填已有会话名即**接入本机正在用的会话**(双端同屏接力);新名字则建持久会话,断开后仍存活;另有 **resume** 入口接入已有会话
5. 顶部标签栏「+」新建标签(kimi/claude/tmux/shell 任选),点按切换,长按关闭;后台标签连接保持存活

## PC 客户端用法

依赖 `npm install ws`,然后:

```bash
RT_TOKEN=<token> node client/rt-client.js <host> [-p 端口] [-a shell|kimi|claude|tmux] [-s tmux会话名]
# 例:接入远端的 tmux 工作会话(与手机/本机同屏)
RT_TOKEN=<token> node client/rt-client.js 100.x.x.x -a tmux -s work
```

token 建议走环境变量 `RT_TOKEN`,免进 shell 历史。窗口缩放自动同步;`Ctrl+\` 本地强退。

## 自行构建 APK

```bash
cd android-app
./gradlew assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

签名固定使用仓库内的 `debug.keystore`,任何机器构建出的 APK 签名一致,可直接覆盖安装。
Mac 端命令行构建环境(JDK/SDK/Gradle 路径)参考 [mac/env.sh](mac/env.sh)。

## 安全说明

- **token 即全部鉴权**:`config.json` 含明文 token,本仓库只收录 `config.example.json`,真实 `config.json` 不要提交
- 文档中出现的 `change-me` 均为占位符,实际部署请用 `openssl rand -hex 24` 生成自己的 token
- tailnet IP(`100.x.x.x`)仅在同一 Tailscale 网络内可达
