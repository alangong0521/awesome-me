#!/bin/bash
# 方案A:远程 Chrome 浏览器栈 (Xvfb :99 + matchbox + google-chrome + x11vnc :5901 + noVNC :6081)
# 与桌面栈并存:桌面 noVNC :6080 -> Xvfb :0 (RO), 浏览器 noVNC :6081 -> Xvfb :99
# 可重复执行。用法: curl -fsSL <repo-raw-url>/deploy/browser/setup.sh | bash
set -e
REPO_BASE="${REPO_BASE:-https://raw.githubusercontent.com/alangong0521/awesome-me/main/server/deploy/browser}"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
VNC_PASSWORD="${VNC_PASSWORD:-}"

echo "=== [1/6] 依赖 (Chrome 若无则下载解包; Xvfb/matchbox 下载解包到 ~/.local/opt) ==="
if ! command -v google-chrome >/dev/null 2>&1 && [ ! -x /opt/google/chrome/google-chrome ]; then
  cd /tmp
  wget -q https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
  dpkg -x google-chrome-stable_current_amd64.deb "$HOME/.local/opt/chrome" 2>/dev/null || true
  # dpkg -x 不解决依赖; 若无 sudo, 假定系统已有 chrome 依赖 (xfce 桌面栈已装过 X 库)
  mkdir -p "$HOME/.local/bin"
  ln -sf "$HOME/.local/opt/chrome/opt/google/chrome/google-chrome" "$HOME/.local/bin/google-chrome"
fi
command -v google-chrome >/dev/null 2>&1 || export PATH="$HOME/.local/bin:$PATH"
mkdir -p "$HOME/.local/opt/matchbox" "$HOME/.local/opt/xvfb"
cd /tmp
if [ ! -x "$HOME/.local/opt/matchbox/usr/bin/matchbox-window-manager" ]; then
  apt-get download matchbox-window-manager libmatchbox1 libstartup-notification0 2>/dev/null
  for f in matchbox-window-manager_*.deb libmatchbox1_*.deb libstartup-notification0_*.deb; do
    [ -f "$f" ] && dpkg -x "$f" "$HOME/.local/opt/matchbox"
  done
fi
if [ ! -x "$HOME/.local/opt/xvfb/usr/bin/Xvfb" ] && [ ! -x /usr/bin/Xvfb ]; then
  apt-get download xvfb 2>/dev/null
  for f in xvfb_*.deb; do [ -f "$f" ] && dpkg -x "$f" "$HOME/.local/opt/xvfb"; done
fi

echo "=== [2/6] 校验 websockify/noVNC (复用桌面栈的; 缺则装) ==="
if ! PYTHONPATH="$HOME/websockify" python3 -c "import websockify" 2>/dev/null; then
  pip3 install --user websockify 2>/dev/null || {
    cd /tmp && wget -q https://github.com/novnc/websockify/archive/refs/tags/v0.13.0.tar.gz \
      && tar xzf v0.13.0.tar.gz -C "$HOME" && mv "$HOME/websockify-0.13.0" "$HOME/websockify"; }
fi
if [ ! -f "$HOME/novnc-web/vnc.html" ]; then
  cd /tmp && wget -q https://github.com/novnc/noVNC/archive/refs/tags/v1.3.0.tar.gz \
    && tar xzf v1.3.0.tar.gz -C "$HOME" && mv "$HOME/noVNC-1.3.0" "$HOME/novnc-web"
fi

echo "=== [3/6] VNC 密码 (与桌面共用 ~/.vnc/passwd) ==="
mkdir -p "$HOME/.vnc"
if [ ! -s "$HOME/.vnc/passwd" ]; then
  [ -z "$VNC_PASSWORD" ] && read -rsp "VNC 密码: " VNC_PASSWORD && echo
  python3 - "$VNC_PASSWORD" > "$HOME/.vnc/passwd" <<'PYEOF'
import sys
p = sys.argv[1][:8].encode().ljust(8, b'\0')
k = bytes([0xE8,0x4A,0xD6,0x60,0xC9,0xAE,0x1C,0x1C])
s = bytes(a ^ b for a, b in zip(p, k))
out = bytearray()
for i in range(0, 8, 2):
    v = (s[i] << 8) | s[i+1]; v = ((v * 0x0101) & 0xFFFF) % 0xFFF1
    out += bytes([v >> 8, v & 0xFF])
print(out.hex())
PYEOF
fi
chmod 600 "$HOME/.vnc/passwd"

echo "=== [4/6] 安装 run-browser.sh + systemd user 单元 ==="
mkdir -p "$HOME/awesome-browser" "$SYSTEMD_USER_DIR"
curl -fsSL "$REPO_BASE/run-browser.sh" -o "$HOME/awesome-browser/run-browser.sh"
chmod +x "$HOME/awesome-browser/run-browser.sh"
for u in awesome-browser-xvfb awesome-browser-session awesome-browser-x11vnc awesome-browser-novnc; do
  curl -fsSL "$REPO_BASE/$u.service" -o "$SYSTEMD_USER_DIR/$u.service"
done
# 单元里的 CHANGE_ME_TAILSCALE_IP 占位符替换为本机 tailnet IP(novnc 只绑 tailnet 网卡)
TAILSCALE_IP=$(tailscale ip -4 2>/dev/null | head -1 || true)
if [ -n "$TAILSCALE_IP" ]; then
  sed -i "s/CHANGE_ME_TAILSCALE_IP/$TAILSCALE_IP/g" "$SYSTEMD_USER_DIR"/awesome-browser-*.service
else
  echo "!! 未取到 tailscale IP, 请手工把单元里 CHANGE_ME_TAILSCALE_IP 改成本机 tailnet IP"
fi

echo "=== [5/6] 启动 (先停掉可能冲突的旧 desktop-lite 栈) ==="
systemctl --user stop awesome-desktop-lite awesome-browser-x11vnc awesome-desktop-x11vnc awesome-websockify awesome-novnc 2>/dev/null || true
systemctl --user daemon-reload
for u in awesome-browser-xvfb awesome-browser-session awesome-browser-x11vnc awesome-browser-novnc; do
  systemctl --user enable --now "$u"
done

echo "=== [6/6] 状态 ==="
sleep 2
TAILSCALE_IP=$(tailscale ip -4 2>/dev/null | head -1 || echo "<tailscale-ip>")
systemctl --user --no-pager status awesome-browser-xvfb awesome-browser-session awesome-browser-x11vnc awesome-browser-novnc 2>&1 | grep -E '●|Active:' || true
echo ""
echo "验证: curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:6081/vnc.html  (期望 200)"
echo "App: 新建标签页 -> 浏览器, 地址 http://$TAILSCALE_IP:6081/vnc.html?autoconnect=true&resize=off"
