#!/bin/bash
# awesome-browser 会话:在 Xvfb :99 上跑 matchbox WM + Chrome(独立 profile,崩溃自动重开)
export DISPLAY=:99
export LD_LIBRARY_PATH=$HOME/.local/opt/matchbox/usr/lib/x86_64-linux-gnu
$HOME/.local/opt/matchbox/usr/bin/matchbox-window-manager &
while true; do
  /usr/bin/google-chrome \
    --no-first-run --no-default-browser-check --disable-session-crashed-bubble \
    --disable-gpu --start-maximized --window-size=1080,1920 --window-position=0,0 \
    --user-data-dir=$HOME/.config/awesome-browser
  sleep 2
done
