#!/usr/bin/env bash
set -euo pipefail
script="${1:-app/src/main/assets/ldfa-host.sh}"

bash -n "$script"
required=(
  'VERSION="0.9.0"'
  'LINUX_IMAGE="debian:12"'
  'install_help="$(proot-distro install --help 2>&1 || true)"'
  '[[ "$install_help" == *"--name"* ]]'
  'proot-distro install --name "$id" "$image"'
  'proot-distro install "$legacy_distro" --override-alias "$id"'
  'unset PROOT_NO_SECCOMP'
  'proot-distro login "$id" --bind "$shared:/mnt/android" --'
  'Acquire::Retries=3'
  'ensure_google_chrome()'
  'google-chrome-stable_current_${architecture}.deb'
  'amd64|arm64'
  'dpkg-deb --field "$chrome_package" Package'
  '/usr/local/bin/google-chrome-ldfa'
  '--no-sandbox'
  'google-chrome.desktop'
  'ensure-apps'
  'startxfce4'
  'fcitx5-mozc'
  'x11-utils'
  'command -v xprop'
  '.xinputrc'
  "Desktop/Android共有"
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
  'bootstrap|create|ensure-apps|start|stop|delete|heartbeat|repair'
)
for pattern in "${required[@]}"; do
  grep -q -- "$pattern" "$script"
done

! grep -q 'gnome-session' "$script"

! grep -q 'UBUNTU_IMAGE=' "$script"

! grep -Eq 'proot-distro install --help.*\|.*grep -q' "$script"

! grep -q 'export PROOT_NO_SECCOMP=1' "$script"

! grep -Eq 'apt(-get)?[^\n]*install[^\n]*chromium' "$script"

echo "Debian XFCE host script checks passed"
