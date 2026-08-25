#!/usr/bin/env bash
set -euo pipefail

controller="${1:-app/src/main/assets/ldfa-host.sh}"
sandbox="$(mktemp -d)"
cleanup() {
  if [[ -f "$sandbox/pulse/socket.pid" ]]; then
    kill "$(cat "$sandbox/pulse/socket.pid")" 2>/dev/null || true
  fi
  rm -rf "$sandbox"
}
trap cleanup EXIT

export HOME="$sandbox/home"
export XDG_DATA_HOME="$sandbox/data"
export PREFIX="$sandbox/prefix"
export SESSION_ROOT="$sandbox/tmux"
export PROOT_TEST_STATE="$sandbox/proot-installed"
export PROOT_INSTALL_ARGS="$sandbox/proot-install-args"
export PULSE_FAKE_ROOT="$sandbox/pulse"
mkdir -p "$HOME/storage/shared" "$PREFIX/tmp/.X11-unix" "$SESSION_ROOT" "$sandbox/bin" "$PULSE_FAKE_ROOT"

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
set -euo pipefail
state="${PROOT_TEST_STATE:?}"
install_args="${PROOT_INSTALL_ARGS:?}"
case "${1:-}" in
  list)
    if [[ -f "$state" ]]; then
      cat "$state"
    fi
    exit 0
    ;;
  install)
    if [[ "${2:-}" == --help ]]; then
      printf '%s\n' '  -n, --name NAME'
      for index in $(seq 1 5000); do
        printf 'modern install help filler %s\n' "$index"
      done
      exit 0
    fi
    printf '%s\n' "$*" > "$install_args"
    if [[ "${2:-}" == --name && -n "${3:-}" && "${4:-}" == debian:12 ]]; then
      printf '%s\n' "$3" > "$state"
      exit 0
    fi
    exit 64
    ;;
  login|kill) exit 0 ;;
  remove)
    rm -f "$state"
    exit 0
    ;;
  *) exit 0 ;;
esac
PROOT

for command in termux-setup-storage termux-wake-lock termux-wake-unlock; do
  cat > "$sandbox/bin/$command" <<'NOOP'
#!/usr/bin/env bash
exit 0
NOOP
  chmod +x "$sandbox/bin/$command"
done

cat > "$sandbox/bin/pgrep" <<'PGREP'
#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *' -x pulseaudio '* ]]; then
  [[ "${PULSE_TEST_PGREP_ERROR:-0}" != 1 ]] || exit 2
  [[ -f "${PULSE_FAKE_ROOT:?}/daemon" ]]
  exit
fi
exit 1
PGREP

cat > "$sandbox/bin/pkill" <<'PKILL'
#!/usr/bin/env bash
set -euo pipefail
root="${PULSE_FAKE_ROOT:?}"
printf '%s\n' "$*" >> "$root/pkill.calls"
if [[ " $* " == *' pulseaudio '* ]]; then
  if [[ -f "$root/socket.pid" ]]; then
    kill "$(cat "$root/socket.pid")" 2>/dev/null || true
  fi
  rm -f \
    "$root/daemon" \
    "$root/modules" \
    "$root/sinks" \
    "$root/socket.pid" \
    "$root/local-control-failure" \
    "$PREFIX/var/run/ldfa-pulse-bridge/native"
fi
PKILL

cat > "$sandbox/bin/pulseaudio" <<'PULSEAUDIO'
#!/usr/bin/env bash
set -euo pipefail
root="${PULSE_FAKE_ROOT:?}"
printf 'PULSE_SERVER=%s %s\n' "${PULSE_SERVER-}" "$*" >> "$root/pulseaudio.calls"
case " ${*:-} " in
  *' --check '*) [[ -f "$root/daemon" ]] ;;
  *' --kill '*)
    [[ ! -f "$root/local-control-failure" ]] || exit 1
    if [[ -f "$root/socket.pid" ]]; then
      kill "$(cat "$root/socket.pid")" 2>/dev/null || true
    fi
    rm -f \
      "$root/daemon" \
      "$root/modules" \
      "$root/sinks" \
      "$root/socket.pid" \
      "$root/local-control-failure" \
      "$PREFIX/var/run/ldfa-pulse-bridge/native"
    ;;
  *' --start '*)
    [[ ! -f "$root/daemon" ]] || { : > "$root/start-collision"; exit 1; }
    : > "$root/daemon"
    printf '1\tOpenSL_ES_sink\tmodule-sles-sink.c\ts16le 2ch 44100Hz\tIDLE\n' > "$root/sinks"
    ;;
  *) exit 0 ;;
esac
PULSEAUDIO

cat > "$sandbox/bin/pactl" <<'PACTL'
#!/usr/bin/env bash
set -euo pipefail
root="${PULSE_FAKE_ROOT:?}"
printf 'PULSE_SERVER=%s %s\n' "${PULSE_SERVER-}" "$*" >> "$root/pactl.calls"
[[ -f "$root/daemon" ]] || exit 1

start_socket() {
  local socket="$1"
  if [[ -f "$root/socket.pid" ]]; then
    kill "$(cat "$root/socket.pid")" 2>/dev/null || true
  fi
  rm -f "$socket"
  python3 - "$socket" <<'PY' >/dev/null 2>&1 &
import os
import socket
import sys

path = sys.argv[1]
os.makedirs(os.path.dirname(path), exist_ok=True)
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
server.listen(1)
while True:
    connection, _ = server.accept()
    connection.close()
PY
  printf '%s\n' "$!" > "$root/socket.pid"
  for _ in $(seq 1 50); do
    [[ -S "$socket" ]] && return 0
    sleep 0.01
  done
  return 1
}

case "${1:-} ${2:-} ${3:-}" in
  info*)
    if [[ "${PULSE_SERVER-}" == unix:* ]]; then
      socket="${PULSE_SERVER#unix:}"
      [[ -S "$socket" ]] && grep -Fq "socket=$socket" "$root/modules"
    elif [[ -f "$root/local-control-failure" ]]; then
      exit 1
    fi
    ;;
  'list short modules')
    [[ "${PULSE_TEST_MODULE_LIST_ERROR:-0}" != 1 ]] || exit 2
    cat "$root/modules" 2>/dev/null || true
    ;;
  'list short sinks')
    [[ "${PULSE_TEST_SINK_LIST_ERROR:-0}" != 1 ]] || exit 2
    cat "$root/sinks" 2>/dev/null || true
    ;;
  'load-module module-native-protocol-unix'*)
    socket=""
    for argument in "$@"; do
      [[ "$argument" == socket=* ]] && socket="${argument#socket=}"
    done
    [[ -n "$socket" ]]
    start_socket "$socket"
    printf '42\tmodule-native-protocol-unix\tsocket=%s auth-anonymous=1\n' "$socket" \
      >> "$root/modules"
    printf '42\n'
    ;;
  'load-module module-aaudio-sink'*)
    [[ "${PULSE_TEST_SINK_LOAD_FAILURE:-0}" != 1 ]]
    printf '43\tmodule-aaudio-sink\t\n' >> "$root/modules"
    printf '2\tAAudio_sink\tmodule-aaudio-sink.c\ts16le 2ch 48000Hz\tIDLE\n' > "$root/sinks"
    printf '43\n'
    ;;
  'load-module module-sles-sink'*)
    [[ "${PULSE_TEST_SINK_LOAD_FAILURE:-0}" != 1 ]]
    printf '44\tmodule-sles-sink\t\n' >> "$root/modules"
    printf '3\tOpenSL_ES_sink\tmodule-sles-sink.c\ts16le 2ch 44100Hz\tIDLE\n' > "$root/sinks"
    printf '44\n'
    ;;
  'unload-module '*)
    [[ "${PULSE_TEST_UNLOAD_ERROR:-0}" != 1 ]] || exit 2
    module_index="${2:-}"
    grep -Ev "^${module_index}[[:space:]]" "$root/modules" > "$root/modules.next" || true
    mv "$root/modules.next" "$root/modules"
    if [[ "$module_index" == 42 && -f "$root/socket.pid" ]]; then
      kill "$(cat "$root/socket.pid")" 2>/dev/null || true
      rm -f "$root/socket.pid"
      rm -f "$PREFIX/var/run/ldfa-pulse-bridge/native"
    fi
    ;;
  'set-default-sink '*) exit 0 ;;
  *) exit 64 ;;
esac
PACTL

chmod +x "$sandbox/bin/pulseaudio" "$sandbox/bin/pactl" "$sandbox/bin/pgrep" "$sandbox/bin/pkill"
chmod +x "$sandbox/bin/tmux" "$sandbox/bin/proot-distro"
export PATH="$sandbox/bin:$PATH"

report="$(bash "$controller" doctor)"
grep -q '^host_ready=1$' <<<"$report"
grep -q '^storage=1$' <<<"$report"
grep -q '^embedded_x11=1$' <<<"$report"
grep -q '^audio_tools=1$' <<<"$report"
grep -q '^version=1.0.2$' <<<"$report"

audio_report="$(bash "$controller" audio-probe)"
grep -q '^audio_server=1$' <<<"$audio_report"
grep -q '^audio_sink=OpenSL_ES_sink$' <<<"$audio_report"
grep -q '^audio_guest=0$' <<<"$audio_report"
grep -Fqx '# LDFA_PULSE_BRIDGE_VERSION=1' \
  "$PREFIX/etc/pulse/default.pa.d/ldfa-audio.pa"
grep -Fq "socket=$PREFIX/var/run/ldfa-pulse-bridge/native auth-anonymous=1" \
  "$PREFIX/etc/pulse/default.pa.d/ldfa-audio.pa"
[[ "$(stat -c '%a' "$PREFIX/var/run/ldfa-pulse-bridge")" == 700 ]]
[[ -S "$PREFIX/var/run/ldfa-pulse-bridge/native" ]]
[[ "$(grep -c 'load-module module-native-protocol-unix' "$PULSE_FAKE_ROOT/pactl.calls")" == 1 ]]
[[ "$(grep -c -- '--start --exit-idle-time=-1' "$PULSE_FAKE_ROOT/pulseaudio.calls")" == 1 ]]
# PRoot cannot pass SHM/memfd descriptors; the daemon must forbid shared memory
# via a daemon.conf.d drop-in so guest playback streams fall back to socket
# transport and reach the Android sink instead of dying after authentication.
grep -Fqx '# LDFA_PULSE_BRIDGE_VERSION=1' \
  "$PREFIX/etc/pulse/daemon.conf.d/99-ldfa-noshm.conf"
grep -Fqx 'enable-shm = no' "$PREFIX/etc/pulse/daemon.conf.d/99-ldfa-noshm.conf"
grep -Fqx 'enable-memfd = no' "$PREFIX/etc/pulse/daemon.conf.d/99-ldfa-noshm.conf"

# A warm daemon and an already-valid bridge must not accumulate modules.
bash "$controller" audio-probe >/dev/null
[[ "$(grep -c 'load-module module-native-protocol-unix' "$PULSE_FAKE_ROOT/pactl.calls")" == 1 ]]
[[ "$(grep -c 'module-native-protocol-unix' "$PULSE_FAKE_ROOT/modules")" == 1 ]]
[[ "$(grep -c -- '--start --exit-idle-time=-1' "$PULSE_FAKE_ROOT/pulseaudio.calls")" == 1 ]]

# A module-inventory timeout is not equivalent to an empty inventory. Degrade
# without unloading modules, unlinking the live socket, or loading a duplicate.
: > "$PULSE_FAKE_ROOT/pactl.calls"
export PULSE_TEST_MODULE_LIST_ERROR=1
if bash "$controller" audio-probe >/dev/null 2>&1; then
  printf '%s\n' 'audio-probe unexpectedly accepted a failed module inventory' >&2
  exit 1
fi
unset PULSE_TEST_MODULE_LIST_ERROR
[[ -S "$PREFIX/var/run/ldfa-pulse-bridge/native" ]]
[[ "$(grep -c 'module-native-protocol-unix' "$PULSE_FAKE_ROOT/modules")" == 1 ]]
! grep -Fq 'unload-module' "$PULSE_FAKE_ROOT/pactl.calls"
! grep -Fq 'load-module' "$PULSE_FAKE_ROOT/pactl.calls"

# A sink-inventory failure must not be mistaken for an empty sink list and
# trigger duplicate Android sink modules.
: > "$PULSE_FAKE_ROOT/pactl.calls"
export PULSE_TEST_SINK_LIST_ERROR=1
if bash "$controller" audio-probe >/dev/null 2>&1; then
  printf '%s\n' 'audio-probe unexpectedly accepted a failed sink inventory' >&2
  exit 1
fi
unset PULSE_TEST_SINK_LIST_ERROR
[[ -S "$PREFIX/var/run/ldfa-pulse-bridge/native" ]]
! grep -Fq ' load-module module-aaudio-sink' "$PULSE_FAKE_ROOT/pactl.calls"
! grep -Fq ' load-module module-sles-sink' "$PULSE_FAKE_ROOT/pactl.calls"

# TermuxService can remove $PREFIX/tmp while PulseAudio survives. The next probe
# must unload the stale module and recreate the exact same private socket.
kill "$(cat "$PULSE_FAKE_ROOT/socket.pid")"
rm -f "$PULSE_FAKE_ROOT/socket.pid" "$PREFIX/var/run/ldfa-pulse-bridge/native"
: > "$PULSE_FAKE_ROOT/pactl.calls"
export PULSE_TEST_UNLOAD_ERROR=1
if bash "$controller" audio-probe >/dev/null 2>&1; then
  printf '%s\n' 'audio-probe unexpectedly replaced a module after unload failed' >&2
  exit 1
fi
unset PULSE_TEST_UNLOAD_ERROR
[[ "$(grep -c 'module-native-protocol-unix' "$PULSE_FAKE_ROOT/modules")" == 1 ]]
! grep -Fq ' load-module module-native-protocol-unix' "$PULSE_FAKE_ROOT/pactl.calls"
: > "$PULSE_FAKE_ROOT/pactl.calls"
bash "$controller" audio-probe >/dev/null
[[ -S "$PREFIX/var/run/ldfa-pulse-bridge/native" ]]
[[ "$(grep -c ' load-module module-native-protocol-unix' "$PULSE_FAKE_ROOT/pactl.calls")" == 1 ]]
[[ "$(grep -c 'unload-module 42' "$PULSE_FAKE_ROOT/pactl.calls")" == 1 ]]
[[ "$(grep -c 'module-native-protocol-unix' "$PULSE_FAKE_ROOT/modules")" == 1 ]]

# If the default SLES sink is absent, the bounded AAudio fallback supplies a
# real Android sink instead of accepting PulseAudio's auto_null sink.
: > "$PULSE_FAKE_ROOT/sinks"
audio_report="$(bash "$controller" audio-probe)"
grep -q '^audio_sink=AAudio_sink$' <<<"$audio_report"
grep -Fq 'load-module module-aaudio-sink' "$PULSE_FAKE_ROOT/pactl.calls"

# If Termux clears its default Pulse runtime socket while the daemon survives,
# local pactl is unusable. A bounded daemon restart restores both control and
# the dedicated bridge instead of leaving the stale pid forever.
: > "$PULSE_FAKE_ROOT/pulseaudio.calls"
: > "$PULSE_FAKE_ROOT/pkill.calls"
: > "$PULSE_FAKE_ROOT/local-control-failure"
audio_report="$(bash "$controller" audio-probe)"
grep -q '^audio_sink=OpenSL_ES_sink$' <<<"$audio_report"
grep -Fq -- '--kill' "$PULSE_FAKE_ROOT/pulseaudio.calls"
grep -Fq -- '-TERM -x pulseaudio' "$PULSE_FAKE_ROOT/pkill.calls"
[[ "$(head -n 1 "$PULSE_FAKE_ROOT/pulseaudio.calls")" == *'--kill'* ]]
[[ "$(grep -c -- '--start --exit-idle-time=-1' "$PULSE_FAKE_ROOT/pulseaudio.calls")" == 1 ]]
[[ ! -f "$PULSE_FAKE_ROOT/start-collision" ]]
[[ "$(grep -c 'module-native-protocol-unix' "$PULSE_FAKE_ROOT/modules")" == 1 ]]

# If pgrep itself times out or fails, process existence is unknown. Never start
# a second daemon in that state; audio degrades without endangering the GUI.
: > "$PULSE_FAKE_ROOT/local-control-failure"
: > "$PULSE_FAKE_ROOT/pulseaudio.calls"
export PULSE_TEST_PGREP_ERROR=1
if bash "$controller" audio-probe >/dev/null 2>&1; then
  printf '%s\n' 'audio-probe unexpectedly accepted indeterminate pgrep state' >&2
  exit 1
fi
unset PULSE_TEST_PGREP_ERROR
[[ ! -s "$PULSE_FAKE_ROOT/pulseaudio.calls" ]]
[[ -f "$PULSE_FAKE_ROOT/daemon" ]]
rm -f "$PULSE_FAKE_ROOT/local-control-failure"

created="$(bash "$controller" create desk-test '仕事用 Debian XFCE')"
[[ "$created" == desk-test ]]
[[ -d "$HOME/storage/shared/LinuxDesktop/desk-test" ]]

record="$(bash "$controller" list)"
IFS=$'\t' read -r id encoded_name state progress encoded_message display created_at alive desktop <<<"$record"
[[ "$id" == desk-test ]]
[[ "$(printf '%s' "$encoded_name" | base64 -d)" == '仕事用 Debian XFCE' ]]
[[ "$state" == queued ]]
[[ "$progress" == 1 ]]
[[ "$display" == 1 ]]
[[ "$alive" == 1 ]]
[[ "$desktop" == xfce ]]
[[ "$created_at" =~ ^[0-9]+$ ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test/distribution")" == debian ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test/image")" == debian:12 ]]

bash "$controller" delete desk-test 0
[[ ! -d "$XDG_DATA_HOME/linux-desktop-for-android/containers/desk-test" ]]
[[ -d "$HOME/storage/shared/LinuxDesktop/desk-test" ]]

created="$(bash "$controller" create personal-test '個人用 Debian')"
[[ "$created" == personal-test ]]
record="$(bash "$controller" list | grep '^personal-test')"
IFS=$'\t' read -r id encoded_name state progress encoded_message display created_at alive desktop <<<"$record"
[[ "$desktop" == xfce ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/personal-test/distribution")" == debian ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/personal-test/image")" == debian:12 ]]
bash "$controller" delete personal-test 1
[[ ! -d "$HOME/storage/shared/LinuxDesktop/personal-test" ]]

created="$(bash "$controller" create pin-test '固定Debian')"
[[ "$created" == pin-test ]]
bash "$controller" worker-install pin-test
[[ "$(cat "$PROOT_INSTALL_ARGS")" == 'install --name pin-test debian:12' ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/pin-test/image")" == debian:12 ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/pin-test/state")" == ready ]]
[[ "$(cat "$XDG_DATA_HOME/linux-desktop-for-android/containers/pin-test/installed")" == 1 ]]
bash "$controller" delete pin-test 1

# --- Timezone sync (HANDOVER-timezone) ---------------------------------------
# getprop is the source of truth for the Android timezone.
cat > "$sandbox/bin/getprop" <<'GETPROP'
#!/usr/bin/env bash
if [[ "${1:-}" == persist.sys.timezone ]]; then
  printf '%s\n' "${GETPROP_TZ-Asia/Tokyo}"
fi
exit 0
GETPROP
chmod +x "$sandbox/bin/getprop"

# A proot-distro login on the timezone hot path would be a regression: when the
# zoneinfo file already exists, ensure_timezone must only readlink/symlink, never
# log in. Record login invocations so the test can assert zero.
cat > "$sandbox/bin/proot-distro" <<'PROOT2'
#!/usr/bin/env bash
set -euo pipefail
state="${PROOT_TEST_STATE:?}"
case "${1:-}" in
  login) printf 'login\n' >> "${PROOT_LOGIN_LOG:?}"; exit 0 ;;
  list) [[ -f "$state" ]] && cat "$state"; exit 0 ;;
  *) exit 0 ;;
esac
PROOT2
chmod +x "$sandbox/bin/proot-distro"
export PROOT_LOGIN_LOG="$sandbox/proot-login.log"

# Load the controller's functions without running main (drop the final dispatch).
tz_lib="$sandbox/ldfa-host-lib.sh"
sed '/^main "\$@"$/d' "$controller" > "$tz_lib"

run_tz_case() {
  # $1 = rootfs base subpath under $PREFIX/var/lib/proot-distro
  local layout="$1" id="tz-$2" rootfs
  rootfs="$PREFIX/var/lib/proot-distro/$layout"
  mkdir -p "$rootfs/etc" "$rootfs/usr/share/zoneinfo/Asia"
  : > "$rootfs/usr/share/zoneinfo/Asia/Tokyo"
  mkdir -p "$(dirname "$(bash -c "source '$tz_lib'; meta_file '$id' x" 2>/dev/null || echo "$XDG_DATA_HOME/linux-desktop-for-android/containers/$id/x")")"
  : > "$PROOT_LOGIN_LOG"
  (
    source "$tz_lib"
    # host_timezone resolves from getprop
    [[ "$(host_timezone)" == "Asia/Tokyo" ]] || { echo "host_timezone FAIL"; exit 1; }
    # rootfs_dir finds this layout
    [[ "$(rootfs_dir "$id")" == "$rootfs" ]] || { echo "rootfs_dir FAIL ($layout)"; exit 1; }
    # Not ready before sync
    timezone_ready "$id" "Asia/Tokyo" && { echo "timezone_ready should be 0"; exit 1; }
    # Sync
    ensure_timezone "$id" >/dev/null 2>&1 || { echo "ensure_timezone FAIL"; exit 1; }
    [[ "$(readlink "$rootfs/etc/localtime")" == "/usr/share/zoneinfo/Asia/Tokyo" ]] || { echo "localtime link FAIL"; exit 1; }
    [[ "$(cat "$rootfs/etc/timezone")" == "Asia/Tokyo" ]] || { echo "/etc/timezone FAIL"; exit 1; }
    timezone_ready "$id" "Asia/Tokyo" || { echo "timezone_ready should be 1"; exit 1; }
  ) || exit 1
  # zoneinfo already present -> ensure_timezone must not have logged in
  [[ ! -s "$PROOT_LOGIN_LOG" ]] || { echo "PRoot login regression ($layout): $(cat "$PROOT_LOGIN_LOG")"; exit 1; }
}

# New layout and legacy layout must both work (HANDOVER §4.4).
run_tz_case "containers/tz-new/rootfs" new
run_tz_case "installed-rootfs/tz-legacy" legacy

# Empty getprop must fall back to the default zone.
GETPROP_TZ="" bash -c "source '$tz_lib'; [[ \"\$(host_timezone)\" == 'Asia/Tokyo' ]]" \
  || { echo "empty-getprop fallback FAIL"; exit 1; }

# The diagnostic command reports the synced state.
mkdir -p "$XDG_DATA_HOME/linux-desktop-for-android/containers/tz-new"
tz_report="$(bash "$controller" timezone tz-new)"
grep -q '^android_timezone=Asia/Tokyo$' <<<"$tz_report"
grep -q '^guest_localtime=/usr/share/zoneinfo/Asia/Tokyo$' <<<"$tz_report"
grep -q '^guest_timezone=Asia/Tokyo$' <<<"$tz_report"
grep -q '^timezone_ready=1$' <<<"$tz_report"

echo "Debian XFCE host controller integration test passed"
