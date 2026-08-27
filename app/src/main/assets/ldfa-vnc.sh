#!/data/data/com.hatake716.linuxdesktop/files/usr/bin/bash
# Linux Desktop for Android compatibility X11/VNC controller
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail

VERSION="0.8.0"
DISPLAY_NUMBER=2
VNC_PORT=5902
WEB_PORT=6080
BASE="${XDG_DATA_HOME:-$HOME/.local/share}/linux-desktop-for-android"
LOG_ROOT="$BASE/logs"
RUN_ROOT="$BASE/run"
META_ROOT="$BASE/containers"
SESSION="ldfa-vnc"
LOG_FILE="$LOG_ROOT/vnc-server.log"
RUNNER="$RUN_ROOT/vnc-runner.sh"
LOCK_DIR="$RUN_ROOT/vnc-lifecycle.lock"
LOCK_PID="$LOCK_DIR/pid"
PREFIX="${PREFIX:-/data/data/com.hatake716.linuxdesktop/files/usr}"
TMP_ROOT="$PREFIX/tmp"
SOCKET_DIR="$TMP_ROOT/.X11-unix"
SOCKET="$SOCKET_DIR/X${DISPLAY_NUMBER}"
WEB_READY="$TMP_ROOT/.ldfa-vnc-web-ready"

mkdir -p "$LOG_ROOT" "$RUN_ROOT" "$SOCKET_DIR"
chmod 1777 "$SOCKET_DIR" 2>/dev/null || true

say() { printf '%s\n' "$*"; }
die() { printf 'エラー: %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

validate_id() {
    [[ "${1:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "不正なコンテナIDです。"
}

tmux_alive() {
    has tmux && tmux has-session -t "$SESSION" 2>/dev/null
}

socket_alive() { [[ -S "$SOCKET" ]]; }
web_ready_marker() { [[ -s "$WEB_READY" ]]; }

web_http_alive() {
    local id="${1:-}"
    validate_id "$id"
    web_ready_marker || return 1
    proot-distro login "$id" --shared-tmp --user desktop -- /usr/bin/python3 - <<PY >/dev/null 2>&1
import socket
s = socket.create_connection(("127.0.0.1", $WEB_PORT), timeout=2.0)
s.sendall(b"GET /vnc.html HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
data = s.recv(64)
s.close()
raise SystemExit(0 if data.startswith(b"HTTP/") else 1)
PY
}

acquire_lock() {
    local attempt owner=""
    for attempt in $(seq 1 300); do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_PID"
            return 0
        fi
        owner="$(cat "$LOCK_PID" 2>/dev/null || true)"
        if [[ "$owner" =~ ^[0-9]+$ ]] && ! kill -0 "$owner" 2>/dev/null; then
            rm -rf "$LOCK_DIR"
            continue
        fi
        sleep 0.1
    done
    die "別の互換表示制御処理が完了しません。"
}

release_lock() { rm -rf "$LOCK_DIR" 2>/dev/null || true; }
run_locked() {
    acquire_lock
    trap release_lock EXIT INT TERM
    "$@"
    release_lock
    trap - EXIT INT TERM
}

packages_ready() {
    local id="$1"
    proot-distro login "$id" --shared-tmp -- /bin/bash -lc \
        'command -v Xtigervnc >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1 && test -r /usr/share/novnc/vnc.html' \
        >/dev/null 2>&1
}

prepare_shared_tmp() {
    local id="$1"
    mkdir -p "$SOCKET_DIR"
    chmod 1777 "$SOCKET_DIR" 2>/dev/null || true
    rm -f "$SOCKET" "$TMP_ROOT/.X${DISPLAY_NUMBER}-lock" "$WEB_READY"

    proot-distro login "$id" --shared-tmp -- /bin/bash -lc \
        'mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix && rm -f /tmp/.X2-lock /tmp/.X11-unix/X2 /tmp/.ldfa-vnc-web-ready' \
        >/dev/null 2>&1 || true
}

activate_host_display() {
    local id="$1" meta_dir="$META_ROOT/$1" display_file host_session temp
    validate_id "$id"
    [[ -d "$meta_dir" ]] || return 0

    display_file="$meta_dir/display"
    temp="${display_file}.tmp.$$"
    printf '%s' "$DISPLAY_NUMBER" > "$temp"
    mv -f "$temp" "$display_file"

    # If XFCE is still attached to native :1, terminate only this container's host worker. The
    # repository calls host heartbeat immediately after display heartbeat, which recreates it using
    # the stored display=2 value. On initial startup this session does not exist yet.
    host_session="ldfa-run-$id"
    if has tmux && tmux has-session -t "$host_session" 2>/dev/null; then
        printf '[%s] switching XFCE worker to compatibility DISPLAY=:%s\n' \
            "$(date -Iseconds)" "$DISPLAY_NUMBER" >> "$LOG_FILE"
        tmux kill-session -t "$host_session" >/dev/null 2>&1 || true
    fi
}

cmd_prepare() {
    local id="${1:-}"
    validate_id "$id"
    has proot-distro || die "proot-distroが見つかりません。"
    prepare_shared_tmp "$id"

    if ! packages_ready "$id"; then
        say "[互換表示] TigerVNC / noVNCをDebianへ準備しています。"
        proot-distro login "$id" --shared-tmp -- /bin/bash -lc '
            set -Eeuo pipefail
            export DEBIAN_FRONTEND=noninteractive
            APT="apt-get -o Acquire::Retries=4 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Dpkg::Use-Pty=0"

            # Enable extra components (universe for Ubuntu, contrib for Debian)
            comp=""
            if [[ -f /etc/os-release ]]; then
                grep -q "^ID=ubuntu" /etc/os-release && comp="universe"
                grep -q "^ID=debian" /etc/os-release && comp="contrib"
            fi
            if [[ -n "$comp" ]]; then
                for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
                    [[ -f "$f" ]] || continue
                    # Third-party repos (Google Chrome) have no contrib/universe
                    # component; appending one only produces apt warnings.
                    [[ "$f" == *google-chrome* ]] && continue
                    if [[ "$f" == *.sources ]]; then
                        sed -Ei "/^Components:/ { /(^|[[:space:]])$comp([[:space:]]|$)/! s/$/ $comp/; }" "$f"
                    else
                        sed -Ei "/^[[:space:]]*deb[[:space:]]/ { /(^|[[:space:]])$comp([[:space:]]|$)/! s/$/ $comp/; }" "$f"
                    fi
                done
            fi

            dpkg --configure -a || true
            # A previously interrupted guest apt run (app killed mid-install →
            # its proot dies mid-dpkg) leaves packages unpacked-but-unconfigured
            # and every later install fails with "Unmet dependencies"; configure
            # alone cannot fetch the missing dependencies, --fix-broken can.
            $APT update
            $APT install -y --fix-broken || true
            $APT install -y --no-install-recommends \
                python3 \
                tigervnc-standalone-server \
                novnc \
                websockify \
                x11-xserver-utils

            command -v Xtigervnc >/dev/null
            command -v websockify >/dev/null
            command -v python3 >/dev/null
            test -r /usr/share/novnc/vnc.html
        '
    fi

    packages_ready "$id" || die "TigerVNC / noVNCの依存関係を準備できませんでした。Debianのaptログを確認してください。"
    say "version=$VERSION"
    say "vnc_runtime_ready=1"
    say "display=:$DISPLAY_NUMBER"
    say "web_url=http://127.0.0.1:$WEB_PORT/vnc.html"
}

stop_vnc() {
    if tmux_alive; then
        tmux kill-session -t "$SESSION" >/dev/null 2>&1 || true
    fi
    pkill -f "Xtigervnc :${DISPLAY_NUMBER}" >/dev/null 2>&1 || true
    pkill -f "websockify.*${WEB_PORT}" >/dev/null 2>&1 || true
    rm -f "$SOCKET" "$TMP_ROOT/.X${DISPLAY_NUMBER}-lock" "$WEB_READY"
}

write_runner() {
    local id="$1" tz
    # The runner heredoc is unquoted, so $tz is expanded here on the host side.
    # Same IANA-name validation as ldfa-host.sh; fall back to the default zone.
    tz="$(getprop persist.sys.timezone 2>/dev/null || true)"
    [[ "$tz" =~ ^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$ ]] || tz="Asia/Tokyo"
    cat > "$RUNNER" <<RUNNER_SCRIPT
#!/data/data/com.hatake716.linuxdesktop/files/usr/bin/bash
set -Eeuo pipefail
exec >>"$LOG_FILE" 2>&1
printf '[%s] compatibility X11/VNC runner started for %s\n' "\$(date -Iseconds)" "$id"
exec proot-distro login "$id" --shared-tmp --user desktop -- /bin/bash -lc '
set -Eeuo pipefail
export HOME=/home/desktop
export USER=desktop
export LOGNAME=desktop
export TZ=$tz
export DISPLAY=:$DISPLAY_NUMBER
export XDG_RUNTIME_DIR=/tmp/ldfa-runtime-desktop-vnc
mkdir -p "\$XDG_RUNTIME_DIR" /tmp/.X11-unix "\$HOME/.vnc"
chmod 700 "\$XDG_RUNTIME_DIR" "\$HOME/.vnc"
chmod 1777 /tmp/.X11-unix 2>/dev/null || true
rm -f /tmp/.X${DISPLAY_NUMBER}-lock /tmp/.X11-unix/X${DISPLAY_NUMBER} /tmp/.ldfa-vnc-web-ready

# cmd_start has already stopped the previous tmux generation before this shell is created.
# Do not use pkill -f here: bash -lc carries the complete runner source in its own command line,
# including the later Xtigervnc/websockify commands, so those patterns can terminate this runner.

cleanup() {
    set +e
    [[ -n "\${web_pid:-}" ]] && kill "\$web_pid" >/dev/null 2>&1 || true
    [[ -n "\${x_pid:-}" ]] && kill "\$x_pid" >/dev/null 2>&1 || true
    rm -f /tmp/.X${DISPLAY_NUMBER}-lock /tmp/.X11-unix/X${DISPLAY_NUMBER} /tmp/.ldfa-vnc-web-ready
}
trap cleanup EXIT INT TERM

printf "[%s] starting Xtigervnc display :$DISPLAY_NUMBER\n" "\$(date -Iseconds)"
Xtigervnc :$DISPLAY_NUMBER \
    -ac \
    -nolisten tcp \
    -localhost yes \
    -SecurityTypes None \
    -rfbport $VNC_PORT \
    -geometry 1280x720 \
    -depth 24 \
    -AlwaysShared &
x_pid=\$!

ready=0
for n in \$(seq 1 120); do
    if [[ -S /tmp/.X11-unix/X${DISPLAY_NUMBER} ]]; then
        ready=1
        break
    fi
    kill -0 "\$x_pid" 2>/dev/null || break
    sleep 0.25
done
[[ "\$ready" == 1 ]] || { echo "Xtigervnc did not create X11 socket" >&2; wait "\$x_pid"; exit 41; }

printf "[%s] starting noVNC websocket on 127.0.0.1:$WEB_PORT\n" "\$(date -Iseconds)"
websockify --web=/usr/share/novnc 127.0.0.1:$WEB_PORT 127.0.0.1:$VNC_PORT &
web_pid=\$!

web_ready=0
for n in \$(seq 1 120); do
    if ! kill -0 "\$web_pid" 2>/dev/null; then
        break
    fi
    if python3 - <<PY >/dev/null 2>&1
import socket
s = socket.create_connection(("127.0.0.1", $WEB_PORT), timeout=1.0)
s.sendall(b"GET /vnc.html HTTP/1.0\\r\\nHost: 127.0.0.1\\r\\n\\r\\n")
data = s.recv(64)
s.close()
raise SystemExit(0 if data.startswith(b"HTTP/") else 1)
PY
    then
        web_ready=1
        printf "%s\n" "\$(date -Iseconds)" > /tmp/.ldfa-vnc-web-ready
        break
    fi
    sleep 0.25
done
[[ "\$web_ready" == 1 ]] || { echo "noVNC HTTP endpoint did not become ready" >&2; wait "\$web_pid"; exit 42; }

printf "[%s] compatibility display ready on :$DISPLAY_NUMBER\n" "\$(date -Iseconds)"
while kill -0 "\$x_pid" 2>/dev/null && kill -0 "\$web_pid" 2>/dev/null; do
    sleep 2
done
if ! kill -0 "\$web_pid" 2>/dev/null; then
    echo "websockify exited while compatibility display was active" >&2
    exit 43
fi
wait "\$x_pid"
'
RUNNER_SCRIPT
    chmod 700 "$RUNNER"
}

cmd_start() {
    local id="${1:-}" attempt stable=0
    validate_id "$id"
    has tmux || die "tmuxが見つかりません。"
    has proot-distro || die "proot-distroが見つかりません。"
    packages_ready "$id" || cmd_prepare "$id"

    stop_vnc
    prepare_shared_tmp "$id"
    : > "$LOG_FILE"
    write_runner "$id"
    printf '[%s] starting compatibility X server on :%s\n' "$(date -Iseconds)" "$DISPLAY_NUMBER" >> "$LOG_FILE"
    tmux new-session -d -s "$SESSION" "$RUNNER"

    for attempt in $(seq 1 200); do
        if tmux_alive && socket_alive && web_ready_marker; then
            stable=$((stable + 1))
            if (( stable >= 6 )); then
                if web_http_alive "$id"; then
                    activate_host_display "$id"
                    say "vnc_started=1"
                    say "display=:$DISPLAY_NUMBER"
                    say "web_url=http://127.0.0.1:$WEB_PORT/vnc.html"
                    return 0
                fi
                stable=0
            fi
        else
            stable=0
        fi
        tmux_alive || break
        sleep 0.25
    done

    {
        printf '\n===== LDFA compatibility display diagnostics =====\n'
        printf 'time=%s\n' "$(date -Iseconds)"
        printf 'display=:%s\n' "$DISPLAY_NUMBER"
        printf 'tmux_alive=%s\n' "$(tmux_alive && echo 1 || echo 0)"
        printf 'socket_alive=%s\n' "$(socket_alive && echo 1 || echo 0)"
        printf 'web_ready_marker=%s\n' "$(web_ready_marker && echo 1 || echo 0)"
        printf 'web_http_alive=%s\n' "$(web_http_alive "$id" && echo 1 || echo 0)"
        printf 'socket_dir_mode=%s\n' "$(stat -c '%a' "$SOCKET_DIR" 2>/dev/null || echo unknown)"
        printf '%s\n' '--- compatibility log ---'
        tail -n 260 "$LOG_FILE" 2>/dev/null || true
        printf '===== end diagnostics =====\n'
    } >&2
    stop_vnc
    die "互換X11サーバーを起動できませんでした。"
}

cmd_probe() {
    local id="${1:-}" attempt
    validate_id "$id"
    tmux_alive || die "互換X11サーバープロセスが停止しています。"
    socket_alive || die "互換X11ソケットがありません。"
    web_http_alive "$id" || die "noVNC HTTPエンドポイントへ接続できません。"

    for attempt in $(seq 1 40); do
        if proot-distro login "$id" --shared-tmp --user desktop -- \
            /usr/bin/env DISPLAY=":$DISPLAY_NUMBER" XAUTHORITY=/dev/null \
            /usr/bin/xset q >/dev/null 2>&1; then
            say "vnc_probe=1"
            say "display=:$DISPLAY_NUMBER"
            return 0
        fi
        sleep 0.5
    done
    die "Debianから互換X11サーバーへ接続できませんでした。"
}

cmd_heartbeat() {
    local id="${1:-}"
    validate_id "$id"
    if ! tmux_alive || ! socket_alive || ! web_http_alive "$id"; then
        cmd_start "$id" >/dev/null
    fi
    cmd_probe "$id" >/dev/null
    say "vnc_alive=1"
}

cmd_status() {
    local alive=0 socket=0 web=0
    tmux_alive && alive=1
    socket_alive && socket=1
    web_ready_marker && web=1
    say "version=$VERSION"
    say "tmux_alive=$alive"
    say "socket_ready=$socket"
    say "web_ready_marker=$web"
    say "display=:$DISPLAY_NUMBER"
    say "web_url=http://127.0.0.1:$WEB_PORT/vnc.html"
    say "log=$LOG_FILE"
}

cmd_logs() {
    local lines="${1:-300}"
    [[ "$lines" =~ ^[0-9]+$ ]] || lines=300
    (( lines > 3000 )) && lines=3000
    [[ -f "$LOG_FILE" ]] && tail -n "$lines" "$LOG_FILE"
}

cmd_stop() {
    stop_vnc
    say "vnc_stopped=1"
}

usage() {
    cat <<USAGE
Usage: ldfa-vnc <command> [arguments]
Commands:
  prepare <container-id>
  start <container-id>
  probe <container-id>
  heartbeat <container-id>
  status
  logs [lines]
  stop
USAGE
}

main() {
    local command="${1:-}"
    [[ -n "$command" ]] || { usage; exit 2; }
    shift || true
    case "$command" in
        prepare) run_locked cmd_prepare "$@" ;;
        start) run_locked cmd_start "$@" ;;
        probe) run_locked cmd_probe "$@" ;;
        heartbeat) run_locked cmd_heartbeat "$@" ;;
        status) cmd_status "$@" ;;
        logs) cmd_logs "$@" ;;
        stop) run_locked cmd_stop "$@" ;;
        *) usage; die "未知の互換表示操作: $command" ;;
    esac
}

main "$@"
