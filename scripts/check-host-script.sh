#!/usr/bin/env bash
set -euo pipefail
script="${1:-app/src/main/assets/ldfa-host.sh}"

bash -n "$script"
required=(
  'VERSION="0.3.1"'
  'UBUNTU_IMAGE="ubuntu:24.04"'
  'proot-distro install --name "$id" "$UBUNTU_IMAGE"'
  'Acquire::Retries=3'
  'startxfce4'
  'fcitx5-mozc'
  'visudo -cf'
  'embedded_x11=1'
  '--shared-tmp'
  'tmux new-session'
)
for pattern in "${required[@]}"; do
  grep -q -- "$pattern" "$script"
done

! grep -q 'gnome-session' "$script"

echo "Ubuntu XFCE host script checks passed"
