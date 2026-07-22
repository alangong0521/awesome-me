# 远程 Chrome 浏览器 (方案A)

独立 Xvfb :99 上跑全屏 Chrome (matchbox 窗口管理), x11vnc 共享, websockify+noVNC 暴露 6081。
与桌面栈并存, 互不影响:

| 栈 | X display | VNC | noVNC 端口 | 内容 |
|---|---|---|---|---|
| 桌面 | :0 | :5900 | 6080 | XFCE 整桌面 |
| 浏览器 | :99 | :5901 | 6081 | 只有 Chrome 全屏 |

VNC 密码与桌面共用 `~/.vnc/passwd` (x11vnc `-rfbauth`)。

## 部署 (server / awesome-me 节点)

```bash
curl -fsSL <repo>/deploy/browser/setup.sh | bash
# 或本地: bash deploy/browser/setup.sh
```

可重复执行。已存在 Chrome/websockify/noVNC/密码则跳过。

## 单元 (systemd user, %h 展开到家目录)

- `awesome-browser-xvfb.service` — Xvfb :99 1080x1920x24 (竖屏, 匹配手机)
- `awesome-browser-session.service` — matchbox + google-chrome (崩溃自动重开)
- `awesome-browser-x11vnc.service` — x11vnc -display :99 -rfbport 5901
- `awesome-browser-novnc.service` — websockify 6081 -> 5901, web 根 ~/novnc-web (noVNC v1.3.0, 兼容老 WebView)

## 客户端

App 新建标签页选「浏览器」, 或浏览器打开:
`http://<tailscale-ip>:6081/vnc.html?autoconnect=true&resize=off`

## 故障排查

- 黑屏: `systemctl --user restart awesome-browser-session` (chrome 崩溃循环中)
- 看 :99 画面: `DISPLAY=:99 xwd -root -silent -out /tmp/x.xwd` 转换查看
- 端口冲突: `ss -tlnp | grep -E '5901|6081'`
- "Restore pages?" 气泡: 点页面任意处即消 (profile 非正常退出导致, 无害)
