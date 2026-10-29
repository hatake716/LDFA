#!/usr/bin/env bash
set -euo pipefail
script="${1:-app/src/main/assets/ldfa-host.sh}"

bash -n "$script"
required=(
  'VERSION="0.9.0"'
  'LINUX_IMAGE="debian:bookworm"'
  'proot-distro install "$distro" --override-alias "$id"'
  'Acquire::Retries=3'
  'startxfce4'
  'fcitx5-mozc'
  'visudo -cf'
  'embedded_x11=1'
  '--shared-tmp'
  'tmux new-session'
  'DEFAULT_DISPLAY_NUMBER=1'
  'detect_active_display()'
  'DISPLAY=":$DISPLAY_NUMBER"'
  'GTK_IM_MODULE=fcitx'
  'QT_IM_MODULE=fcitx'
  'XMODIFIERS=@im=fcitx'
  'cmd_probe()'
  '_NET_SUPPORTING_WM_CHECK'
  '/usr/bin/pgrep -x xfce4-session'
  '/usr/bin/pgrep -x xfwm4'
  'acquire_controller_lock()'
  'bootstrap|create|start|stop|delete|heartbeat|repair'
)
for pattern in "${required[@]}"; do
  grep -q -- "$pattern" "$script"
done

! grep -q 'gnome-session' "$script"

! grep -q 'UBUNTU_IMAGE=' "$script"

echo "Debian XFCE host script checks passed"
