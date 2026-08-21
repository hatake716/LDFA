#!/usr/bin/env bash
set -euo pipefail

controller="${1:-app/src/main/assets/ldfa-host.sh}"
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT

export HOME="$sandbox/home"
export XDG_DATA_HOME="$sandbox/data"
export PREFIX="$sandbox/prefix"
export SESSION_ROOT="$sandbox/tmux"
mkdir -p "$HOME/storage/shared" "$PREFIX/tmp/.X11-unix" "$SESSION_ROOT" "$sandbox/bin"

cat > "$sandbox/bin/tmux" <<'TMUX'
#!/usr/bin/env bash
set -euo pipefail
root="${SESSION_ROOT:?}"
case "${1:-}" in
  has-session)
    name="${3:-}"
    [[ -f "$root/$name" ]]
    ;;
  new-session)
    name=""
    while (($#)); do
      if [[ "$1" == -s ]]; then name="$2"; shift 2; else shift; fi
    done
    [[ -n "$name" ]]
    : > "$root/$name"
    ;;
  kill-session)
    name="${3:-}"
    rm -f "$root/$name"
    ;;
  *) exit 0 ;;
esac
TMUX

cat > "$sandbox/bin/proot-distro" <<'PROOT'
#!/usr/bin/env bash
case "${1:-}" in
  list) exit 0 ;;
  kill|remove) exit 0 ;;
  *) exit 0 ;;
esac
PROOT

for command in termux-setup-storage termux-wake-lock termux-wake-unlock pkill pulseaudio; do
  cat > "$sandbox/bin/$command" <<'NOOP'
#!/usr/bin/env bash
exit 0
NOOP
  chmod +x "$sandbox/bin/$command"
done
chmod +x "$sandbox/bin/tmux" "$sandbox/bin/proot-distro"
export PATH="$sandbox/bin:$PATH"

report="$(bash "$controller" doctor)"
grep -q '^host_ready=1$' <<<"$report"
grep -q '^storage=1$' <<<"$report"
grep -q '^embedded_x11=1$' <<<"$report"
grep -q '^version=0.3.1$' <<<"$report"

created="$(bash "$controller" create desk-test '仕事用 Ubuntu XFCE')"
[[ "$created" == desk-test ]]
[[ -d "$HOME/storage/shared/LinuxDesktop/desk-test" ]]

record="$(bash "$controller" list)"
IFS=$'\t' read -r id encoded_name state progress encoded_message display created_at alive desktop <<<"$record"
[[ "$id" == desk-test ]]
[[ "$(printf '%s' "$encoded_name" | base64 -d)" == '仕事用 Ubuntu XFCE' ]]
[[ "$state" == queued ]]
[[ "$progress" == 1 ]]
[[ "$display" == 1 ]]
[[ "$alive" == 1 ]]
[[ "$desktop" == xfce ]]
[[ "$created_at" =~ ^[0-9]+$ ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test/distribution")" == ubuntu ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test/image")" == ubuntu:24.04 ]]

bash "$controller" delete desk-test 0
[[ ! -d "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test" ]]
[[ -d "$HOME/storage/shared/LinuxDesktop/desk-test" ]]

created="$(bash "$controller" create personal-test '個人用 Ubuntu')"
[[ "$created" == personal-test ]]
record="$(bash "$controller" list | grep '^personal-test')"
IFS=$'\t' read -r id encoded_name state progress encoded_message display created_at alive desktop <<<"$record"
[[ "$desktop" == xfce ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/personal-test/distribution")" == ubuntu ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/personal-test/image")" == ubuntu:24.04 ]]
bash "$controller" delete personal-test 1
[[ ! -d "$HOME/storage/shared/LinuxDesktop/personal-test" ]]

echo "Ubuntu XFCE host controller integration test passed"
