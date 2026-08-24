#!/data/data/com.termux/files/usr/bin/bash
# Linux Desktop for Android X11 diagnostics / Debian connectivity controller
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail

VERSION="1.0.0"
DISPLAY_NUMBER=1
PACKAGE_ID="com.termux"
BASE="${XDG_DATA_HOME:-$HOME/.local/share}/linux-desktop-for-android"
LOG_ROOT="$BASE/logs"
LOG_FILE="$LOG_ROOT/x11-server.log"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TMP_ROOT="$PREFIX/tmp"
SOCKET_DIR="$TMP_ROOT/.X11-unix"
SOCKET="$SOCKET_DIR/X${DISPLAY_NUMBER}"
X_LOCK="$TMP_ROOT/.X${DISPLAY_NUMBER}-lock"

mkdir -p "$LOG_ROOT" "$SOCKET_DIR"
chmod 1777 "$SOCKET_DIR" 2>/dev/null || true

say() { printf '%s\n' "$*"; }
die() { printf 'エラー: %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

validate_id() {
    [[ "${1:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "不正なコンテナIDです。"
}

service_pid() {
    pidof "${PACKAGE_ID}:x11" 2>/dev/null | awk '{print $1}'
}

service_alive() {
    local pid
    pid="$(service_pid)"
    [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

socket_alive() { [[ -S "$SOCKET" ]]; }

find_xkb_root() {
    local candidate
    for candidate in \
        "$PREFIX/share/X11/xkb" \
        "$PREFIX/share/xkeyboard-config-2"; do
        if [[ -r "$candidate/rules/evdev" || -r "$candidate/rules/base" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

ensure_xkb() {
    local xkb_root=""
    xkb_root="$(find_xkb_root || true)"
    if [[ -n "$xkb_root" ]]; then
        printf '%s' "$xkb_root"
        return 0
    fi

    has pkg || die "Termuxパッケージ管理コマンドが見つかりません。"
    say "[X11] Termux X11 repositoryを有効化します。" >&2
    pkg install -y x11-repo >&2
    pkg update -y >&2
    say "[X11] xkeyboard-configをインストールします。" >&2
    pkg install -y xkeyboard-config >&2

    xkb_root="$(find_xkb_root || true)"
    [[ -n "$xkb_root" ]] || die "xkeyboard-configのXKB rulesを準備できませんでした。"
    printf '%s' "$xkb_root"
}

relevant_log_tail() {
    local pid
    [[ -f "$LOG_FILE" ]] && tail -n 180 "$LOG_FILE" || true
    pid="$(service_pid)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && has logcat; then
        logcat -d --pid "$pid" -t 220 2>/dev/null | grep -E \
            'Lorie|Xlorie|X11|EGL|AImageReader|AChoreographer|Fatal|FATAL|ERROR|Error|failed|Failed|socket|Surface' \
            | tail -n 160 || true
    fi
}

dump_failure() {
    local reason="${1:-X11 failure}"
    {
        printf '\n===== LDFA embedded X11 diagnostics =====\n'
        printf 'reason=%s\n' "$reason"
        printf 'time=%s\n' "$(date -Iseconds)"
        printf 'kernel=%s\n' "$(uname -a)"
        printf 'android_sdk=%s\n' "$(getprop ro.build.version.sdk 2>/dev/null || true)"
        printf 'service_pid=%s\n' "$(service_pid || true)"
        printf 'service_alive=%s\n' "$(service_alive && echo 1 || echo 0)"
        printf 'socket_alive=%s\n' "$(socket_alive && echo 1 || echo 0)"
        printf 'socket=%s\n' "$SOCKET"
        printf 'lock=%s\n' "$X_LOCK"
        printf '%s\n' '--- endpoint directory ---'
        ls -la "$SOCKET_DIR" 2>&1 || true
        printf '%s\n' '--- embedded X11 log ---'
        relevant_log_tail
        printf '%s\n' '===== end diagnostics ====='
    } >&2
}

cmd_prepare() {
    local xkb_root
    xkb_root="$(ensure_xkb)"
    mkdir -p "$SOCKET_DIR"
    chmod 1777 "$SOCKET_DIR" 2>/dev/null || true
    say "version=$VERSION"
    say "xkb_root=$xkb_root"
    say "socket=$SOCKET"
    say "x11_runtime_ready=1"
}

cmd_probe() {
    local id="${1:-}" attempt
    validate_id "$id"
    has proot-distro || die "proot-distroが見つかりません。"
    service_alive || { dump_failure "X11 service missing before Debian probe"; die "X11サービスが停止しています。"; }
    socket_alive || { dump_failure "X11 socket missing before Debian probe"; die "X11ソケットがありません。"; }

    for attempt in $(seq 1 40); do
        if proot-distro login "$id" --shared-tmp --user desktop -- \
            /usr/bin/env DISPLAY=":$DISPLAY_NUMBER" XAUTHORITY=/dev/null \
            /usr/bin/xset q >/dev/null 2>&1; then
            say "x11_probe=1"
            say "display=:$DISPLAY_NUMBER"
            return 0
        fi
        service_alive && socket_alive || break
        sleep 0.5
    done

    dump_failure "Debian xset probe failed"
    die "Debianから内蔵X11サービスへ接続できませんでした。"
}

# This must run only after Binder, Surface and EGL are ready. xrefresh maps a
# temporary window and forces visible clients to repaint; unlike xsetroot it is
# not hidden behind xfdesktop after XFCE starts.
#
# The caller (startAndVerifyNativeX11) always runs the standalone `probe` first,
# which already established Debian->Xserver reachability with a full 40x xset
# retry. Repeating cmd_probe here paid a SECOND guest PRoot login (seconds on
# ARM) to re-confirm the same reachability. xrefresh itself opens a client
# connection to the X server, so its success re-confirms reachability anyway;
# we keep only the cheap host-side service/socket liveness checks and drop the
# redundant xset login. If xrefresh fails we still run the probe once to produce
# a precise diagnostic (reachability vs draw) before dying.
cmd_draw_probe() {
    local id="${1:-}"
    validate_id "$id"
    service_alive || { dump_failure "X11 service missing before draw probe"; die "X11サービスが停止しています。"; }
    socket_alive || { dump_failure "X11 socket missing before draw probe"; die "X11ソケットがありません。"; }
    if ! proot-distro login "$id" --shared-tmp --user desktop -- \
        /usr/bin/env DISPLAY=":$DISPLAY_NUMBER" XAUTHORITY=/dev/null \
        /usr/bin/xrefresh -solid '#303030' >/dev/null 2>&1; then
        # Distinguish "X unreachable" from "X reachable but draw failed" for logs.
        cmd_probe "$id" >/dev/null 2>&1 || true
        dump_failure "Debian draw probe failed"
        die "DebianからX11描画プローブを実行できませんでした。"
    fi
    say "x11_draw_probe=1"
    say "display=:$DISPLAY_NUMBER"
}

cmd_heartbeat() {
    local id="${1:-}"
    cmd_probe "$id" >/dev/null
    say "x11_alive=1"
}

cmd_status() {
    local alive=0 socket=0
    service_alive && alive=1
    socket_alive && socket=1
    say "version=$VERSION"
    say "service_alive=$alive"
    say "socket_ready=$socket"
    say "display=:$DISPLAY_NUMBER"
    say "pid=$(service_pid || true)"
    say "log=$LOG_FILE"
}

cmd_logs() {
    local lines="${1:-300}" pid
    [[ "$lines" =~ ^[0-9]+$ ]] || lines=300
    (( lines > 3000 )) && lines=3000
    [[ -f "$LOG_FILE" ]] && tail -n "$lines" "$LOG_FILE"
    pid="$(service_pid)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && has logcat; then
        printf '\n--- Android :x11 process logcat ---\n'
        logcat -d --pid "$pid" -t "$lines" 2>/dev/null || true
    fi
}

usage() {
    cat <<USAGE
Usage: ldfa-x11 <command> [arguments]
Commands:
  prepare
  probe <container-id>
  draw-probe <container-id>
  heartbeat <container-id>
  status
  logs [lines]

X11 service start/stop is intentionally owned by the Android application process.
USAGE
}

main() {
    local command="${1:-}"
    [[ -n "$command" ]] || { usage; exit 2; }
    shift || true
    case "$command" in
        prepare) cmd_prepare "$@" ;;
        probe) cmd_probe "$@" ;;
        draw-probe) cmd_draw_probe "$@" ;;
        heartbeat) cmd_heartbeat "$@" ;;
        status) cmd_status "$@" ;;
        logs) cmd_logs "$@" ;;
        *) usage; die "未知のX11操作: $command" ;;
    esac
}

main "$@"
