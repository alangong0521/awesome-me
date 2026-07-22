# 远程桌面(noVNC)部署说明

App 的"桌面"标签通过 `http://<机器>:6080/vnc.html` 访问远程桌面。本文档覆盖两端的服务端部署。

## server(Linux,GNOME + NVIDIA)——已部署,但有已知限制

server 上已配好(systemd user 服务,`systemctl --user status x11vnc novnc`):

- `x11vnc.service`:x11vnc 挂 :1 桌面,localhost:5900,密码文件 `~/.vnc/passwd`(当前密码 `CHANGE_ME_VNC_PASSWORD`)。
  **注意必须用 `-rfbauth` 引用该文件**:`-storepasswd` 写的是标准 VNC 混淆格式,而 `-passwdfile`
  是 x11vnc 增强格式(会把它当明文读,表现为密码永远校验失败——v1.4.1 部署时实测踩过)
- `novnc.service`:用户态 websockify(`~/websockify` 源码包),绑 tailnet `CHANGE_ME_SERVER_TAILNET_IP:6080`,
  web 根目录 `~/novnc-web`(noVNC v1.3.0;1.4+ 需要 Chrome 84+ 的私有类字段,1.3 兼容老 WebView)

**已知限制(重要)**:server 的 GNOME 跑在 X11 + NVIDIA 闭源驱动上,根窗口读回是坏的
(三个 VNC 服务端 x11vnc/gnome-remote-desktop/TigerVNC 实测都只读到黑屏+光标;
窗口级读回 xwd 验证正常)。这是 NVIDIA+X11 的已知硬问题,不是配置错误。
**推荐解法**:下次在 server 物理控制台注销,在 GDM 登录页选 **"GNOME"(Wayland 会话)**,
然后启用 GNOME 自带远程桌面(系统已装 gnome-remote-desktop 42,支持 VNC):

```bash
# 切到 Wayland 会话后执行(server 本机):
systemctl --user stop x11vnc              # 停掉 x11vnc,腾 5900
gsettings set org.gnome.desktop.remote-desktop.vnc enable true
gsettings set org.gnome.desktop.remote-desktop.vnc auth-method 'password'
gsettings set org.gnome.desktop.remote-desktop.vnc view-only false
echo -n 'CHANGE_ME_VNC_PASSWORD' | secret-tool store --label='GNOME Remote Desktop VNC password' \
  xdg:schema org.gnome.RemoteDesktop.VncPassword
systemctl --user start gnome-remote-desktop
# novnc.service(websockify→localhost:5900)不用动,自动生效
```

Wayland 下 gnome-remote-desktop 走 PipeWire 截屏,无 NVIDIA 黑屏问题。

## 家 Mac(macOS)——需手动一次

Mac 的 tailnet(CHANGE_ME_MAC_TAILNET_IP)可 ping 通但 sshd 未开,以下步骤需要在 Mac 上手动做一遍。

### 1. 打开屏幕共享(VNC)

系统设置 → 通用 → 共享 → **屏幕共享** 打开 → 点右侧 ⓘ →
勾选 **"VNC 显示程序可以使用密码控制屏幕"**,设置密码(建议同 `CHANGE_ME_VNC_PASSWORD`)。
此时 Mac 的 VNC 服务在 `localhost:5900`。

### 2. 装 websockify + noVNC

```bash
brew install websockify            # 或:python3 -m pip install --user websockify
cd ~ && curl -sL -o novnc.zip https://github.com/novnc/noVNC/archive/refs/tags/v1.3.0.zip \
  && unzip -q novnc.zip && mv noVNC-1.3.0 novnc-web
```

### 3. launchd 常驻(绑 tailnet IP)

把仓库 `mac/com.calla.novnc.plist` 拷到 `~/Library/LaunchAgents/`,然后:

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.calla.novnc.plist
launchctl kickstart -k gui/$(id -u)/com.calla.novnc
curl http://CHANGE_ME_MAC_TAILNET_IP:6080/vnc.html   # 应返回 200
```

### 4. App 使用

新建标签 → 选"桌面"类型 → 机器选"家里电脑" → 标签加载 `http://CHANGE_ME_MAC_TAILNET_IP:6080/vnc.html`,
首次在 noVNC 页面输一次密码(勾选记住),以后自动连接。
