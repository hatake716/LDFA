#!/data/data/com.termux/files/usr/bin/bash
# Linux Desktop for Android unified host controller (Robust Debian Edition)
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail

VERSION="0.9.0"
LINUX_IMAGE="debian:12"
BASE="${XDG_DATA_HOME:-$HOME/.local/share}/linux-desktop-for-android"
BIN_DIR="$BASE/bin"
META_ROOT="$BASE/containers"
LOG_ROOT="$BASE/logs"
RUN_ROOT="$BASE/run"
CONTROLLER_LOCK_DIR="$RUN_ROOT/host-controller.lock"
CONTROLLER_LOCK_PID="$CONTROLLER_LOCK_DIR/pid"
SHARED_ROOT="$HOME/storage/shared/LinuxDesktop"
SELF="$BIN_DIR/ldfa-host"
BOOTSTRAP_LOG="$LOG_ROOT/bootstrap.log"
CHROME_LAUNCHER_MARKER="# LDFA_CHROME_LAUNCHER_VERSION=8"
DESKTOP_RUNTIME_MARKER="# LDFA_SESSION_RUNTIME_VERSION=27"
AUDIO_CLIENT_MARKER="# LDFA_AUDIO_CLIENT_VERSION=3"
PULSE_BRIDGE_MARKER="# LDFA_PULSE_BRIDGE_VERSION=1"
# Modern Node.js runtime provisioned into the guest so Node-based CLIs (Claude
# Code, Codex, and other npm tools) install and run out of the box. Debian 12's
# apt Node is 18.x — too old for tools that now require Node >= 22 — so LDFA
# installs the official upstream static build into /usr/local instead. The build
# is glibc-based and self-contained (no apt dependencies) and runs cleanly under
# PRoot. SHA-256 sums are the upstream SHASUMS256.txt values, pinned per arch.
NODEJS_MARKER="# LDFA_NODEJS_VERSION=5"
NODEJS_VERSION="v22.23.2"
NODEJS_SHA256_x64="d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307"
NODEJS_SHA256_arm64="fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8"
# The bridge socket directory must live OUTSIDE $PREFIX/tmp. proot-distro's
# --shared-tmp binds the whole $PREFIX/tmp into every guest as /tmp, and proot's
# per-session housekeeping (link2symlink/kill-on-exit teardown) races with, and
# intermittently deletes, a socket directory that sits under $PREFIX/tmp — even
# while the PulseAudio daemon keeps running. Keeping the socket in $PREFIX/var/run
# and exposing it to the guest through an explicit --bind isolates it from that
# churn, so the Debian client always finds a live socket.
PULSE_HOST_DIR="$PREFIX/var/run/ldfa-pulse-bridge"
# PulseAudio also defaults its own runtime dir to $TMPDIR/pulse-<machine-id>,
# i.e. inside $PREFIX/tmp. Pin it outside the shared bind for the same reason and
# so every pulseaudio/pactl invocation below agrees on one daemon.
PULSE_RUNTIME_PATH="$PREFIX/var/run/ldfa-pulse-rt"
export PULSE_RUNTIME_PATH
PULSE_HOST_SOCKET="$PULSE_HOST_DIR/native"
PULSE_HOST_SERVER="unix:$PULSE_HOST_SOCKET"
# Guest-visible path is unchanged (clients and the desktop session still use
# unix:/tmp/ldfa-pulse/native); the explicit bind below maps the host bridge dir
# onto it independently of --shared-tmp.
PULSE_GUEST_DIR="/tmp/ldfa-pulse"
PULSE_GUEST_SERVER="unix:$PULSE_GUEST_DIR/native"
PULSE_GUEST_BIND="$PULSE_HOST_DIR:$PULSE_GUEST_DIR"
PULSE_CONFIG_DROP_IN="$PREFIX/etc/pulse/default.pa.d/ldfa-audio.pa"
PULSE_DAEMON_DROP_IN="$PREFIX/etc/pulse/daemon.conf.d/99-ldfa-noshm.conf"
DEFAULT_DISPLAY_NUMBER=1
DISPLAY_NUMBER="${LDFA_DISPLAY_NUMBER:-$DEFAULT_DISPLAY_NUMBER}"
X11_SOCKET="$PREFIX/tmp/.X11-unix/X${DISPLAY_NUMBER}"

mkdir -p "$BIN_DIR" "$META_ROOT" "$LOG_ROOT" "$RUN_ROOT"

say() { printf '%s\n' "$*"; }
die() { printf 'エラー: %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

acquire_controller_lock() {
    local attempt owner=""
    for attempt in $(seq 1 300); do
        if mkdir "$CONTROLLER_LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$CONTROLLER_LOCK_PID"
            return 0
        fi
        owner="$(cat "$CONTROLLER_LOCK_PID" 2>/dev/null || true)"
        if [[ "$owner" =~ ^[0-9]+$ ]] && ! kill -0 "$owner" 2>/dev/null; then
            rm -rf "$CONTROLLER_LOCK_DIR"
            continue
        fi
        # Allow the winning process time to write its pid before considering an
        # ownerless directory stale.
        if (( attempt > 20 )) && [[ -z "$owner" ]]; then
            rmdir "$CONTROLLER_LOCK_DIR" 2>/dev/null || true
        fi
        sleep 0.1
    done
    die "別のLinuxデスクトップ制御処理が完了しません。"
}

release_controller_lock() {
    local owner=""
    owner="$(cat "$CONTROLLER_LOCK_PID" 2>/dev/null || true)"
    if [[ "$owner" == "$$" ]]; then
        rm -rf "$CONTROLLER_LOCK_DIR"
    fi
}

validate_id() {
    [[ "${1:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "不正なコンテナIDです。"
}

validate_display_number() {
    [[ "${1:-}" =~ ^[1-9][0-9]?$ ]] || die "不正なDISPLAY番号です: ${1:-empty}"
}

meta_dir() { printf '%s/%s' "$META_ROOT" "$1"; }
meta_file() { printf '%s/%s' "$(meta_dir "$1")" "$2"; }
log_file() { printf '%s/%s.log' "$LOG_ROOT" "$1"; }
stop_file() { printf '%s/%s.stop' "$RUN_ROOT" "$1"; }
active_file() { printf '%s/active' "$RUN_ROOT"; }
install_session() { printf 'ldfa-install-%s' "$1"; }
run_session() { printf 'ldfa-run-%s' "$1"; }
shared_path() { printf '%s/%s' "$SHARED_ROOT" "$1"; }

write_file() {
    local destination="$1" value="${2-}" temp
    mkdir -p "$(dirname "$destination")"
    temp="${destination}.tmp.$$"
    printf '%s' "$value" > "$temp"
    mv -f "$temp" "$destination"
}

write_meta() { write_file "$(meta_file "$1" "$2")" "${3-}"; }
read_meta() {
    local file
    file="$(meta_file "$1" "$2")"
    if [[ -f "$file" ]]; then cat "$file"; else printf '%s' "${3-}"; fi
}

detect_active_display() {
    local id="$1" x1=0 x2=0 remembered
    [[ -S "$PREFIX/tmp/.X11-unix/X1" ]] && x1=1
    [[ -S "$PREFIX/tmp/.X11-unix/X2" ]] && x2=1
    if [[ "$x1" == 1 && "$x2" == 1 ]]; then
        die "DISPLAY :1 と :2 が同時に使用されています。表示サーバーを安全に切り替えられません。"
    fi
    if [[ "$x2" == 1 ]]; then printf '2'; return 0; fi
    if [[ "$x1" == 1 ]]; then printf '1'; return 0; fi
    remembered="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
    validate_display_number "$remembered"
    printf '%s' "$remembered"
}

set_status() {
    local id="$1" state="$2" progress="$3" message="$4"
    write_meta "$id" state "$state"
    write_meta "$id" progress "$progress"
    write_meta "$id" message "$message"
}

encode() {
    if has base64; then
        printf '%s' "${1-}" | base64 | tr -d '\n'
    else
        printf '%s' "${1-}" | openssl base64 -A
    fi
}

tmux_alive() {
    has tmux && tmux has-session -t "$1" 2>/dev/null
}

container_exists() {
    local id="$1"
    has proot-distro || return 1
    if proot-distro list -q >/dev/null 2>&1; then
        proot-distro list -q 2>/dev/null | grep -Fxq "$id"
    else
        proot-distro login "$id" -- /bin/true >/dev/null 2>&1
    fi
}

storage_linked() {
    # Report whether ~/storage/shared is a correct symlink into Android shared
    # storage WITHOUT traversing into it. A Termux/app-shell-spawned process may
    # lack a traversable FUSE view of /storage/emulated/0 (the storage grant can
    # race the fork, or the process may not carry the storage GIDs), so a bare
    # `-d` on the symlink stats the resolved target and can be false forever even
    # though the link is correct. Reading the link value only ($? of readlink)
    # never touches the target, so this reflects that the link is established.
    local link="$1" target
    if [[ -L "$link" ]]; then
        target="$(readlink "$link" 2>/dev/null || true)"
        [[ "$target" == /storage/emulated/0 || "$target" == /storage/self/primary ]] && return 0
    fi
    # Fallback: a real directory we can actually stat (bind mount, or a process
    # that does hold traversal permission). Never a false positive when unlinked.
    [[ -d "$link" ]]
}

ensure_storage() {
    mkdir -p "$HOME/storage"
    if ! storage_linked "$HOME/storage/shared"; then
        termux-setup-storage >/dev/null 2>&1 || true
    fi
    if [[ ! -e "$HOME/storage/shared" ]] && [[ -d /storage/emulated/0 ]]; then
        ln -s /storage/emulated/0 "$HOME/storage/shared" 2>/dev/null || true
    fi
    storage_linked "$HOME/storage/shared" || \
        die "Android共有ストレージへアクセスできません。アプリのストレージ権限を確認してください。"
    # Creating LinuxDesktop/ requires traversing into shared storage, which this
    # exact process may not yet be able to do; do not abort setup on it.
    mkdir -p "$SHARED_ROOT" 2>/dev/null || true
}

retry_command() {
    local attempts="$1" delay_seconds="$2"
    shift 2
    local attempt rc=0
    for ((attempt=1; attempt<=attempts; attempt++)); do
        "$@" && return 0
        rc=$?
        printf '[%s] command failed (attempt %s/%s, exit=%s): %q\n' \
            "$(date -Iseconds)" "$attempt" "$attempts" "$rc" "$*" >&2
        (( attempt < attempts )) && sleep "$delay_seconds"
    done
    return "$rc"
}

pulse_module_loaded() {
    local wanted="$1" index="" name="" arguments="" modules=""
    modules="$(env -u PULSE_SERVER timeout 1s pactl list short modules 2>/dev/null)" || \
        return 2
    while read -r index name arguments; do
        [[ "$name" == "$wanted" ]] && return 0
    done <<< "$modules"
    return 1
}

pulse_bridge_module_indexes() {
    local index="" name="" arguments="" modules=""
    modules="$(env -u PULSE_SERVER timeout 1s pactl list short modules 2>/dev/null)" || \
        return 2
    while read -r index name arguments; do
        [[ "$name" == module-native-protocol-unix ]] || continue
        [[ "$arguments" == *"socket=$PULSE_HOST_SOCKET"* ]] || continue
        [[ "$index" =~ ^[0-9]+$ ]] && printf '%s\n' "$index"
    done <<< "$modules"
    return 0
}

pulse_real_sink() {
    local index="" name="" rest="" sinks=""
    sinks="$(
        PULSE_SERVER="$PULSE_HOST_SERVER" timeout 1s pactl list short sinks 2>/dev/null
    )" || return 2
    while read -r index name rest; do
        [[ -n "$name" && "$name" != auto_null ]] || continue
        printf '%s' "$name"
        return 0
    done <<< "$sinks"
    return 1
}

ensure_audio_bridge_config() {
    local config daemon_config
    mkdir -p "$PULSE_HOST_DIR" "$PULSE_RUNTIME_PATH" \
        "$(dirname "$PULSE_CONFIG_DROP_IN")" \
        "$(dirname "$PULSE_DAEMON_DROP_IN")"
    chmod 700 "$PULSE_HOST_DIR" "$PULSE_RUNTIME_PATH"
    config="$PULSE_BRIDGE_MARKER
load-module module-native-protocol-unix socket=$PULSE_HOST_SOCKET auth-anonymous=1
"
    if [[ ! -f "$PULSE_CONFIG_DROP_IN" ]] || \
        [[ "$(cat "$PULSE_CONFIG_DROP_IN" 2>/dev/null || true)"$'\n' != "$config" ]]; then
        write_file "$PULSE_CONFIG_DROP_IN" "$config"
    fi

    # PRoot cannot pass SHM/memfd descriptors across the guest boundary, so the
    # app-owned daemon must never negotiate shared-memory transport with a Debian
    # client. pulseaudio honours a daemon.conf.d drop-in, which holds even when
    # "pulseaudio --start" re-execs the daemon without our command-line flags.
    daemon_config="$PULSE_BRIDGE_MARKER
enable-shm = no
enable-memfd = no
"
    if [[ ! -f "$PULSE_DAEMON_DROP_IN" ]] || \
        [[ "$(cat "$PULSE_DAEMON_DROP_IN" 2>/dev/null || true)"$'\n' != "$daemon_config" ]]; then
        write_file "$PULSE_DAEMON_DROP_IN" "$daemon_config"
    fi
}

pulse_process_state() {
    local rc=0
    timeout 1s pgrep -x pulseaudio >/dev/null 2>&1 || rc=$?
    case "$rc" in
        0) printf 'present' ;;
        1) printf 'absent' ;;
        *)
            printf '[%s] PulseAudio process state is indeterminate: pgrep exit=%s\n' \
                "$(date -Iseconds)" "$rc" >&2
            return 2
            ;;
    esac
}

start_or_recover_pulseaudio() {
    local deadline="${1:-$((SECONDS + 12))}" attempt process_state=""

    # Reuse a healthy daemon before asking PulseAudio to start. TermuxService can
    # clear $PREFIX/tmp while an old daemon survives; starting first in that state
    # races a second daemon against the stale process and still does not recreate
    # its native control socket.
    env -u PULSE_SERVER timeout 1s pactl info >/dev/null 2>&1 && return 0
    (( SECONDS < deadline )) || return 1

    process_state="$(pulse_process_state)" || return 1
    (( SECONDS < deadline )) || return 1
    if [[ "$process_state" == present ]]; then
        printf '[%s] PulseAudio control socket is stale; restarting the app-owned daemon\n' \
            "$(date -Iseconds)" >&2
        if ! env -u PULSE_SERVER timeout 1s pulseaudio --kill >/dev/null 2>&1; then
            timeout 1s pkill -TERM -x pulseaudio >/dev/null 2>&1 || true
        fi
        for attempt in $(seq 1 10); do
            process_state="$(pulse_process_state)" || return 1
            [[ "$process_state" == absent ]] && break
            (( SECONDS < deadline )) || return 1
            sleep 0.1
        done
        process_state="$(pulse_process_state)" || return 1
        (( SECONDS < deadline )) || return 1
        if [[ "$process_state" == present ]]; then
            timeout 1s pkill -KILL -x pulseaudio >/dev/null 2>&1 || true
            for attempt in $(seq 1 5); do
                process_state="$(pulse_process_state)" || return 1
                [[ "$process_state" == absent ]] && break
                (( SECONDS < deadline )) || return 1
                sleep 0.1
            done
        fi
        process_state="$(pulse_process_state)" || return 1
        [[ "$process_state" == absent ]] || return 1
    fi

    (( SECONDS < deadline )) || return 1
    rm -f "$PULSE_HOST_SOCKET"
    # PRoot's syscall emulation cannot pass SHM/memfd file descriptors across the
    # guest boundary. When the daemon offers shared-memory transport, a Debian
    # client authenticates over the Unix socket but its playback stream dies during
    # the SHM/srbchannel handshake ("Connection died"), so the Android sink never
    # leaves IDLE and no sound is heard. The client-side drop-in refuses SHM, and
    # ensure_audio_bridge_config also disables it daemon-side via daemon.conf.d so
    # the fallback to plain socket transport holds even if pulseaudio --start
    # re-execs the daemon without inheriting a command-line flag.
    env -u PULSE_SERVER timeout 2s pulseaudio --start --exit-idle-time=-1 || return 1
    for attempt in 1 2 3; do
        env -u PULSE_SERVER timeout 1s pactl info >/dev/null 2>&1 && return 0
        (( SECONDS < deadline )) || return 1
        sleep 0.1
    done
    return 1
}

ensure_audio_bridge() {
    local attempt bridge_ready=0 module_index="" sink="" query_status=0
    local bridge_module_output="" deadline=$((SECONDS + 12))
    local -a bridge_indexes=()
    has pulseaudio || { printf '[%s] PulseAudio server command is missing\n' "$(date -Iseconds)" >&2; return 1; }
    has pactl || { printf '[%s] PulseAudio control command is missing\n' "$(date -Iseconds)" >&2; return 1; }

    ensure_audio_bridge_config
    if ! start_or_recover_pulseaudio "$deadline"; then
        printf '[%s] PulseAudio daemon did not start\n' "$(date -Iseconds)" >&2
        return 1
    fi

    (( SECONDS < deadline )) || return 1
    if ! bridge_module_output="$(pulse_bridge_module_indexes)"; then
        printf '[%s] PulseAudio module inventory is unavailable; refusing bridge mutation\n' \
            "$(date -Iseconds)" >&2
        return 1
    fi
    if [[ -n "$bridge_module_output" ]]; then
        mapfile -t bridge_indexes <<< "$bridge_module_output"
    fi
    if [[ "${#bridge_indexes[@]}" == 1 ]] && [[ -S "$PULSE_HOST_SOCKET" ]] && \
        PULSE_SERVER="$PULSE_HOST_SERVER" timeout 1s pactl info >/dev/null 2>&1; then
        bridge_ready=1
    fi
    if [[ "$bridge_ready" != 1 ]]; then
        for module_index in "${bridge_indexes[@]}"; do
            (( SECONDS < deadline )) || return 1
            if ! env -u PULSE_SERVER timeout 1s pactl unload-module "$module_index" \
                >/dev/null 2>&1; then
                printf '[%s] PulseAudio bridge module %s could not be unloaded; refusing replacement\n' \
                    "$(date -Iseconds)" "$module_index" >&2
                return 1
            fi
        done
        rm -f "$PULSE_HOST_SOCKET"
        module_index="$(
            env -u PULSE_SERVER timeout 2s pactl load-module module-native-protocol-unix \
                "socket=$PULSE_HOST_SOCKET" auth-anonymous=1 2>/dev/null || true
        )"
        [[ "$module_index" =~ ^[0-9]+$ ]] || \
            printf '[%s] dedicated PulseAudio Unix module was not loaded directly; probing config result\n' \
                "$(date -Iseconds)" >&2
    fi

    for attempt in 1 2; do
        if [[ -S "$PULSE_HOST_SOCKET" ]] && \
            PULSE_SERVER="$PULSE_HOST_SERVER" timeout 1s pactl info >/dev/null 2>&1; then
            bridge_ready=1
            break
        fi
        (( SECONDS < deadline )) || return 1
        sleep 0.1
    done
    [[ "$bridge_ready" == 1 ]] || {
        printf '[%s] dedicated PulseAudio Unix socket is unavailable: %s\n' \
            "$(date -Iseconds)" "$PULSE_HOST_SOCKET" >&2
        return 1
    }

    (( SECONDS < deadline )) || return 1
    if sink="$(pulse_real_sink)"; then
        :
    else
        query_status=$?
        [[ "$query_status" == 1 ]] || return 1
        sink=""
    fi
    if [[ -z "$sink" ]]; then
        if pulse_module_loaded module-aaudio-sink; then
            :
        else
            query_status=$?
            [[ "$query_status" == 1 ]] || return 1
            (( SECONDS < deadline )) || return 1
            env -u PULSE_SERVER timeout 2s pactl load-module module-aaudio-sink \
                >/dev/null 2>&1 || true
            if sink="$(pulse_real_sink)"; then
                :
            else
                query_status=$?
                [[ "$query_status" == 1 ]] || return 1
                sink=""
            fi
        fi
    fi
    if [[ -z "$sink" ]]; then
        if pulse_module_loaded module-sles-sink; then
            :
        else
            query_status=$?
            [[ "$query_status" == 1 ]] || return 1
            (( SECONDS < deadline )) || return 1
            env -u PULSE_SERVER timeout 2s pactl load-module module-sles-sink \
                >/dev/null 2>&1 || true
            if sink="$(pulse_real_sink)"; then
                :
            else
                query_status=$?
                [[ "$query_status" == 1 ]] || return 1
                sink=""
            fi
        fi
    fi
    [[ -n "$sink" ]] || {
        printf '[%s] PulseAudio is running but Android audio sink is unavailable\n' \
            "$(date -Iseconds)" >&2
        return 1
    }
    PULSE_SERVER="$PULSE_HOST_SERVER" timeout 1s pactl set-default-sink "$sink" \
        >/dev/null 2>&1 || true
    printf '[%s] PulseAudio bridge ready: server=%s sink=%s\n' \
        "$(date -Iseconds)" "$PULSE_GUEST_SERVER" "$sink"
}

guest_audio_ready() {
    local id="$1" attempt
    # A freshly started daemon can still be binding its dedicated socket and
    # loading the Unix module when the first guest login lands, so a single probe
    # races cold start and reports a false negative. Retry a few times; each PRoot
    # login already takes ~1-2s, which also paces the daemon settling window.
    for attempt in 1 2 3; do
        if timeout 6s proot-distro login "$id" --shared-tmp \
            --bind "$PULSE_GUEST_BIND" --user desktop -- \
            /bin/bash -c '
                test -S /tmp/ldfa-pulse/native || exit 1
                if command -v pactl >/dev/null 2>&1; then
                    PULSE_SERVER=unix:/tmp/ldfa-pulse/native pactl info >/dev/null 2>&1
                else
                    grep -Fq "default-server = unix:/tmp/ldfa-pulse/native" \
                        /etc/pulse/client.conf.d/99-ldfa.conf
                fi
            ' >/dev/null 2>&1; then
            return 0
        fi
        (( attempt < 3 )) && sleep 0.5
    done
    return 1
}

desktop_session_script() {
    cat <<'SESSION'
#!/bin/bash
# LDFA_SESSION_RUNTIME_VERSION=27
# Hardened LDFA Session Script
set -Eeuo pipefail

# Clear environment inherited from Android/Termux before entering the desktop.
unset LD_PRELOAD
unset LD_LIBRARY_PATH
unset SESSION_MANAGER

export LANG=ja_JP.UTF-8
export LANGUAGE=ja_JP:ja
export LC_ALL=ja_JP.UTF-8
export DISPLAY="${DISPLAY:-:1}"
export XDG_SESSION_TYPE=x11
export XDG_SESSION_DESKTOP=xfce
export XDG_CURRENT_DESKTOP=XFCE
export DESKTOP_SESSION=xfce
export GDK_BACKEND=x11
export QT_QPA_PLATFORM=xcb
export GTK_IM_MODULE=fcitx
export QT_IM_MODULE=fcitx
export XMODIFIERS=@im=fcitx
export PULSE_SERVER=unix:/tmp/ldfa-pulse/native

# PRoot has no usable MIT-SHM and Pixel GPUs are not directly exposed to Debian.
export _MITSHM=0
export QT_X11_NO_MITSHM=1
export GDK_RENDERING=image
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

export G_SLICE=always-malloc
export MALLOC_CHECK_=0
export NO_AT_BRIDGE=1

# Electron/Chromium apps (Claude Desktop, VS Code, Slack, ...) cannot establish
# their normal sandbox inside Android PRoot: the SUID chrome-sandbox helper needs
# a real root transition and the namespace sandbox needs unprivileged user
# namespaces, and PRoot provides neither (the guest's real uid stays the Android
# app uid regardless of the fake root). Without a way out they abort at startup —
# exactly the "installs but won't launch" symptom. Electron reads this variable
# and appends --no-sandbox (electron_main_delegate.cc:
# HasVar(ELECTRON_DISABLE_SANDBOX) -> AppendSwitch kNoSandbox). This is enough
# for apps that never call app.enableSandbox() (e.g. Claude Desktop). It is NOT
# enough for hardened builds that DO call it (e.g. the OpenAI ChatGPT app): that
# API runs RemoveNoSandboxSwitch() and re-forces the sandbox, so the env var is
# undone and the app still zygote-crashes. Those need --no-sandbox on the real
# command line, which the scan_and_fix_electron sweep below injects per app via a
# user-level .desktop override. Keep this export as belt-and-suspenders (harmless
# where the flag also applies, and it covers terminal launches before the sweep
# has run). Treat neither PRoot nor these apps as a security boundary.
export ELECTRON_DISABLE_SANDBOX=1

# --- LDFA whole-desktop scale ---------------------------------------------
# LDFA_SCALE is a percent (100/125/150/175/200) injected on the launch line
# from the container's stored preference. Derive the env every launched app
# reads at startup here; the xsettings/xfconf keys (panel/icon/font sizes) are
# applied just below, before xfsettingsd starts. Everything degrades to 100%.
LDFA_SCALE="${LDFA_SCALE:-100}"
case "$LDFA_SCALE" in 100|125|150|175|200|225|250) : ;; *) LDFA_SCALE=100 ;; esac
_ldfa_factor="$(awk "BEGIN{printf \"%.2f\", $LDFA_SCALE/100}")"
_ldfa_dpi=$(( LDFA_SCALE * 96 / 100 ))
_ldfa_cursor=$(( LDFA_SCALE * 24 / 100 ))
_ldfa_panel=$(( LDFA_SCALE * 28 / 100 ))
_ldfa_icon=$(( LDFA_SCALE * 48 / 100 ))
# One font-DPI lever per toolkit — do NOT combine GDK_DPI_SCALE with the
# xsettings /Xft/DPI below (GTK multiplies them: 150% would become 2.25x), and
# do NOT combine QT_FONT_DPI with QT_SCALE_FACTOR (same double-apply for Qt).
# GTK fonts are owned by /Xft/DPI (applied via xsettings + xrdb in
# apply_desktop_scale); Qt whole-UI scale is owned by QT_SCALE_FACTOR.
export QT_SCALE_FACTOR="$_ldfa_factor"
export XCURSOR_SIZE="$_ldfa_cursor"
# GDK_SCALE is integer-only: use the crisp 2x path at 200%, plain 1 otherwise
# (a fractional GDK_SCALE blurs and half-positions windows).
if [ "$LDFA_SCALE" = 200 ]; then export GDK_SCALE=2; else export GDK_SCALE=1; fi

export XDG_RUNTIME_DIR="/tmp/runtime-desktop"
mkdir -p \
    "$XDG_RUNTIME_DIR" \
    "$HOME/Desktop" \
    "$HOME/.cache/sessions" \
    "$HOME/.config" \
    "${XDG_STATE_HOME:-$HOME/.local/state}/ldfa"
chmod 700 "$XDG_RUNTIME_DIR"

# Do not restore a killed XFCE session. Chrome has its own bounded crash restore.
rm -f "$HOME/.cache/sessions"/xfce4-session-* 2>/dev/null || true

DBUS_PID_FILE="$XDG_RUNTIME_DIR/dbus.pid"
DBUS_SOCK="$XDG_RUNTIME_DIR/bus"
DBUS_ADDRESS_FILE="$XDG_RUNTIME_DIR/dbus_address"
if [[ -f "$DBUS_PID_FILE" ]]; then
    dbus_pid="$(cat "$DBUS_PID_FILE" 2>/dev/null || true)"
    dbus_name=""
    if [[ "$dbus_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$dbus_pid/comm" ]]; then
        dbus_name="$(cat "/proc/$dbus_pid/comm" 2>/dev/null || true)"
    fi
    if [[ ! "$dbus_pid" =~ ^[0-9]+$ ]] || \
        ! kill -0 "$dbus_pid" 2>/dev/null || \
        [[ "$dbus_name" != dbus-daemon ]] || \
        [[ ! -S "$DBUS_SOCK" ]] || \
        [[ ! -s "$DBUS_ADDRESS_FILE" ]]; then
        rm -f "$DBUS_PID_FILE" "$DBUS_SOCK" "$DBUS_ADDRESS_FILE"
    fi
fi

if [[ ! -f "$DBUS_PID_FILE" ]]; then
    dbus-daemon --session --fork --print-address 5 --print-pid 6 \
        --address="unix:path=$DBUS_SOCK" \
        5> "$DBUS_ADDRESS_FILE" 6> "$DBUS_PID_FILE"
    # The forked daemon writes its bus address to fd 5 once it is ready. Poll for
    # that file to become non-empty instead of a flat 0.5s sleep; the ceiling
    # (25 * 0.02s = 0.5s) keeps the original worst case so a genuinely stuck
    # daemon still cannot hang startup, but the common case returns in tens of ms.
    for _dbus_wait in $(seq 1 25); do
        [[ -s "$DBUS_ADDRESS_FILE" ]] && break
        sleep 0.02
    done
fi
export DBUS_SESSION_BUS_ADDRESS="$(cat "$DBUS_ADDRESS_FILE")"

# xfce4-session depends on ICE hard-link locking, which PRoot cannot provide.
# Starting the XFCE components directly avoids its repeated 8-second auth
# retries and omits desktop-only daemons that compete with Chrome for Android's
# child-process budget. Preserve the user's complete panel file before removing
# only plugins that are non-functional inside LDFA. Debian Bookworm assigns
# plugin 8 to PulseAudio, so keep it available for volume and mute control.
PANEL_CONFIG_DIR="$HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
PANEL_CONFIG="$PANEL_CONFIG_DIR/xfce4-panel.xml"
PANEL_MOBILE_V1_MARKER="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/panel-mobile-v1"
PANEL_MOBILE_MARKER="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/panel-mobile-v2"
mkdir -p "$PANEL_CONFIG_DIR"
if [[ ! -f "$PANEL_CONFIG" ]] && [[ -f /etc/xdg/xfce4/panel/default.xml ]]; then
    cp /etc/xdg/xfce4/panel/default.xml "$PANEL_CONFIG"
fi
if [[ -f "$PANEL_CONFIG" ]] && [[ ! -f "$PANEL_MOBILE_MARKER" ]]; then
    plugin_id=""
    plugin_name=""
    if [[ ! -f "$PANEL_MOBILE_V1_MARKER" ]]; then
        if [[ ! -f "$PANEL_CONFIG.ldfa-before-mobile-optimization" ]]; then
            cp -p "$PANEL_CONFIG" "$PANEL_CONFIG.ldfa-before-mobile-optimization"
        fi
        for plugin_spec in \
            '9:power-manager-plugin' \
            '10:notification-plugin' \
            '14:actions'; do
            plugin_id="${plugin_spec%%:*}"
            plugin_name="${plugin_spec#*:}"
            if grep -Fq \
                "<property name=\"plugin-$plugin_id\" type=\"string\" value=\"$plugin_name\"" \
                "$PANEL_CONFIG"; then
                sed -i -E "/<value type=\"int\" value=\"$plugin_id\"\/>/d" "$PANEL_CONFIG"
            fi
        done
    fi

    # v1 accidentally removed plugin 8 from panel-1. Restore only the exact
    # Bookworm PulseAudio definition, and only when that panel does not already
    # contain the ID; never rewrite the user's whole panel configuration.
    if grep -Fq '<property name="plugin-8" type="string" value="pulseaudio"' \
        "$PANEL_CONFIG" && ! awk '
            /<property name="panel-1" type="empty">/ { in_panel = 1 }
            in_panel && /<property name="plugin-ids" type="array">/ { in_ids = 1 }
            in_ids && /<value type="int" value="8"\/>/ { found = 1 }
            in_ids && /<\/property>/ { exit }
            END { exit(found ? 0 : 1) }
        ' "$PANEL_CONFIG"; then
        panel_temporary="$PANEL_CONFIG.ldfa-audio.$$"
        if awk '
            /<property name="panel-1" type="empty">/ { in_panel = 1 }
            in_panel && /<property name="plugin-ids" type="array">/ { in_ids = 1 }
            in_ids && /<value type="int"/ && indent == "" {
                match($0, /^[[:space:]]*/)
                indent = substr($0, RSTART, RLENGTH)
            }
            in_ids && /<\/property>/ && ! inserted {
                if (indent == "") indent = "        "
                print indent "<value type=\"int\" value=\"8\"/>"
                inserted = 1
                in_ids = 0
            }
            { print }
            END { exit(inserted ? 0 : 1) }
        ' "$PANEL_CONFIG" > "$panel_temporary"; then
            mv -f "$panel_temporary" "$PANEL_CONFIG"
        else
            rm -f "$panel_temporary"
        fi
    fi
    : > "$PANEL_MOBILE_MARKER"
fi

setxkbmap -layout jp >/dev/null 2>&1 || true
# These three run before xfsettingsd/xfwm4 exist, so xfconfd D-Bus-autoactivates
# and may create a fresh backing store — the exact stall the apply_desktop_scale
# comment warns about, where `|| true` does NOT cap a hung command. Bound each
# with `timeout 3` so a stalled xfconfd cannot wedge startup here either.
timeout 3 xfconf-query -c xsettings -p /Net/ThemeName -s Adwaita 2>/dev/null || true
timeout 3 xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null || true
timeout 3 xfconf-query -c xfwm4 -p /general/sync_to_vblank -s false 2>/dev/null || true

# Whole-desktop scale is applied AFTER the XFCE components are launched (see the
# apply_desktop_scale call after launch_settings below), never here on the
# critical path. Writing the xfce4-panel/xfce4-desktop channels before their
# daemons exist forces xfconfd to D-Bus-autoactivate and create a brand-new
# backing store; on a real device that create can stall, and `|| true` does NOT
# cap a command that never returns — it only rewrites a non-zero EXIT. Each
# write is therefore `timeout`-bounded, and the whole apply runs backgrounded
# after the panel/desktop channels already exist (so the plain `-s` succeeds and
# the slow `-n` create path is never taken). This keeps startup unblockable.
ldfa_xfconf_set() {
    # Create-with-value first (-n -t int -s), then fall back to updating an
    # existing property (-s). Order matters: a plain `-t int -s` on a MISSING
    # property does NOT store the value (it leaves an empty/typeless entry that
    # never reaches xsettings.xml), so the scale silently had no effect. `-n`
    # writes a real typed value on first run; the `-s` fallback updates it on
    # later runs when the property already exists.
    timeout 3 xfconf-query -c "$1" -p "$2" -n -t int -s "$3" 2>/dev/null ||
        timeout 3 xfconf-query -c "$1" -p "$2" -s "$3" 2>/dev/null || true
}
apply_desktop_scale() {
    # xrdb: put Xft.dpi into RESOURCE_MANAGER so clients that IGNORE xsettings —
    # notably Chrome/Electron and libXft/Qt apps — still scale. xfsettingsd only
    # feeds GTK via the XSETTINGS protocol; the X resource is a separate channel
    # nothing in LDFA populated before, which is the main reason scaling looked
    # like "nothing happened". Must run before the components launch so they
    # inherit it; timeout-bounded so it can never stall startup.
    printf 'Xft.dpi: %s\nXft.hinting: 1\nXft.autohint: 0\n' "$_ldfa_dpi" |
        timeout 3 xrdb -merge 2>/dev/null || true
    ldfa_xfconf_set xsettings     /Xft/DPI                 "$_ldfa_dpi"
    ldfa_xfconf_set xsettings     /Gtk/CursorThemeSize     "$_ldfa_cursor"
    ldfa_xfconf_set xfce4-panel   /panels/panel-1/size     "$_ldfa_panel"
    ldfa_xfconf_set xfce4-desktop /desktop-icons/icon-size "$_ldfa_icon"
    if [ "$LDFA_SCALE" = 200 ]; then
        ldfa_xfconf_set xsettings /Gdk/WindowScalingFactor 2
    else
        ldfa_xfconf_set xsettings /Gdk/WindowScalingFactor 1
    fi
}

fcitx5 -d --replace >/dev/null 2>&1 || true

# --- LDFA Electron sandbox auto-fix ---------------------------------------
# Electron/Chromium GUI apps cannot establish their sandbox under Android PRoot.
# ELECTRON_DISABLE_SANDBOX (exported above) only helps apps that never call
# app.enableSandbox(); hardened builds such as the OpenAI ChatGPT app strip the
# env-var-injected switch and re-force the sandbox, then die at the Chromium
# zygote with a "Broken pipe" before any window appears. The only reliable lever
# is --no-sandbox on the real command line. This sweep runs on every desktop
# start, so an Electron app the user installed BY HAND after provisioning is
# fixed on the next launch with no user action. It is idempotent and reversible
# (overrides live only in the user's own applications dir).
LDFA_ELECTRON_STAMP="# LDFA_ELECTRON_FIX=1"

# Is the package directory of an Exec program token an Electron app? Detected by
# fingerprinting the directory rather than parsing the launcher script, because
# vendor wrappers (e.g. ChatGPT's) compute their target path at runtime from $0,
# so there is no static path to follow. Requires BOTH a Chromium .pak AND an
# Electron asar (or the icudtl+v8-snapshot pair) so ordinary GTK/Qt apps, which
# have neither, are never matched.
ldfa_electron_pkgdir() {
    local prog="$1" resolved dir d
    case "$prog" in
        /*) resolved="$prog" ;;
        *)  resolved="$(command -v "$prog" 2>/dev/null || true)" ;;
    esac
    [[ -n "$resolved" ]] || return 1
    resolved="$(readlink -f "$resolved" 2>/dev/null || printf '%s' "$resolved")"
    dir="$(dirname "$resolved")"
    for d in "$dir" "$dir/.."; do
        [[ -d "$d" ]] || continue
        if [[ ( -f "$d/resources.pak" || -f "$d/chrome_100_percent.pak" ) &&
              ( -f "$d/resources/app.asar" || -f "$d/resources/electron.asar" ||
                ( -f "$d/icudtl.dat" && -f "$d/v8_context_snapshot.bin" ) ) ]]; then
            return 0
        fi
    done
    return 1
}

scan_and_fix_electron() {
    local out_dir="$HOME/.local/share/applications" src name exec_line prog
    install -d -m 0755 "$out_dir"
    for src in /usr/share/applications/*.desktop; do
        [[ -f "$src" ]] || continue
        name="$(basename "$src")"
        exec_line="$(grep -m1 '^Exec=' "$src" 2>/dev/null | sed 's/^Exec=//')"
        [[ -n "$exec_line" ]] || continue
        # Already unsandboxed, or Chrome (LDFA ships its own --no-sandbox chrome
        # launcher already): leave untouched.
        case "$exec_line" in
            *--no-sandbox*|*google-chrome*|*/opt/google/chrome/*) continue ;;
        esac
        # Program token = first word, skipping an env prefix / VAR=val assignments.
        set -- $exec_line
        prog="$1"
        while [[ "$prog" == env || "$prog" == *=* ]] && [[ $# -gt 1 ]]; do
            shift; prog="$1"
        done
        ldfa_electron_pkgdir "$prog" || continue
        local dst="$out_dir/$name"
        # The stamp encodes the current scale, so the override is regenerated when
        # the user changes the display scale (a plain LDFA_ELECTRON_STAMP match
        # would keep a stale --force-device-scale-factor forever). Skip only when
        # the stamp AND the scale already match.
        local stamp="$LDFA_ELECTRON_STAMP scale=$LDFA_SCALE"
        if [[ -f "$dst" ]] && grep -Fqx "$stamp" "$dst" 2>/dev/null; then
            continue
        fi
        # Extra Chromium flags: Electron apps ignore XSETTINGS/Xft.dpi, so the
        # ONLY way to zoom their whole UI is --force-device-scale-factor. Add it
        # (and --no-sandbox) after the program token, preserving %U/%F field
        # codes. A user-level .desktop shadows the system one (XDG precedence).
        local extra="--no-sandbox"
        [[ "$LDFA_SCALE" != 100 ]] && extra="$extra --force-device-scale-factor=$_ldfa_factor"
        {
            printf '%s\n' "$stamp"
            awk -v extra="$extra" '
                /^Exec=/ {
                    rest = substr($0, 6); n = index(rest, " ")
                    if (n == 0) { print "Exec=" rest " " extra; next }
                    print "Exec=" substr(rest, 1, n - 1) " " extra substr(rest, n)
                    next
                }
                { print }
            ' "$src"
        } > "$dst.tmp.$$" && mv -f "$dst.tmp.$$" "$dst" || rm -f "$dst.tmp.$$"
    done
}

# NOTE: the sweep itself is invoked AFTER the window manager is up (see the
# backgrounded call following wait_for_wm), not here. It only rewrites user-level
# .desktop overrides that the launcher reads when an app is started by hand, so
# nothing on the critical path to a usable desktop depends on it having finished.
# Running it foreground here spent dozens of in-PRoot spawns (grep/sed/readlink
# per .desktop) before the first frame; deferring it removes that from startup.
# --- end Electron sandbox auto-fix ----------------------------------------

chrome_running() {
    pgrep -x chrome >/dev/null 2>&1 || \
        pgrep -x google-chrome >/dev/null 2>&1 || \
        pgrep -x google-chrome-stable >/dev/null 2>&1
}

visible_xfce_client() {
    local wanted="$1" window
    for window in $(
        xprop -root _NET_CLIENT_LIST 2>/dev/null |
            grep -oE '0x[[:xdigit:]]+' || true
    ); do
        if xprop -id "$window" WM_CLASS 2>/dev/null | grep -Fqi "$wanted" && \
            LC_ALL=C xwininfo -id "$window" 2>/dev/null | \
                grep -Fq 'Map State: IsViewable'; then
            return 0
        fi
    done
    return 1
}

# Verify both sides of EWMH's supporting-WM handshake. The root property can
# briefly retain the dead xfwm4 window ID after Android trims that process, so
# checking the referenced window prevents us from treating stale X11 state as
# a newly usable window manager.
wm_ready() {
    local root_property wm_property wm_window
    root_property="$(xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null)" || return 1
    [[ "$root_property" =~ 0x[[:xdigit:]]+ ]] || return 1
    wm_window="${BASH_REMATCH[0]}"
    wm_property="$(
        xprop -id "$wm_window" _NET_SUPPORTING_WM_CHECK 2>/dev/null
    )" || return 1
    [[ "${wm_property,,}" == *"${wm_window,,}"* ]]
}

# The launcher leaves this marker only while Chrome is running or after an
# abnormal process-group kill. Restore as soon as the replacement WM is real;
# waiting for the panel and wallpaper to map needlessly leaves Chrome a full
# second behind XFCE. Full desktop health checks remain stricter below.
restore_chrome_after_wm_ready() {
    local marker="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/chrome-running" attempt
    [[ -f "$marker" ]] || return 0
    for attempt in $(seq 1 60); do
        # wm_ready performs a live X11 round trip and validates the referenced
        # xfwm4 window. Running xset and pgrep as well only creates extra
        # Android-visible children while the device is already under pressure.
        if wm_ready; then
            if ! chrome_running; then
                printf '[%s] restoring Google Chrome after interrupted desktop session\n' \
                    "$(date -Iseconds)"
                /usr/local/bin/google-chrome-ldfa \
                    --restore-last-session \
                    --disable-session-crashed-bubble \
                    >"$XDG_RUNTIME_DIR/chrome-restore.log" 2>&1 &
            fi
            return 0
        fi
        sleep 0.25
    done
}

COMPONENT_LOG="$XDG_RUNTIME_DIR/xfce-components.log"
: > "$COMPONENT_LOG"

# Clean only volatile desktop components from a partially killed generation.
# User applications and the persistent Chrome profile are not touched here.
# The 0.25s settle only matters when we ACTUALLY signalled a lingering component
# (a restart of an interrupted generation); on the common first-open of the day
# nothing matches, pkill returns non-zero for every component, and the sleep is
# pure dead time. pkill exits 0 only when >=1 process matched, so keying the
# sleep on that preserves the original behaviour exactly.
killed_any=0
for component in xfce4-session xfwm4 xfsettingsd xfce4-panel xfdesktop Thunar xfce4-notifyd; do
    pkill -TERM -x "$component" >/dev/null 2>&1 && killed_any=1 || true
done
[[ "$killed_any" == 1 ]] && sleep 0.25 || true

launch_settings() {
    xfsettingsd --disable-wm-check --replace >>"$COMPONENT_LOG" 2>&1 &
    settings_pid=$!
    printf '%s\n' "$settings_pid" > "$XDG_RUNTIME_DIR/xfsettingsd.pid"
}

launch_wm() {
    xfwm4 --compositor=off --replace >>"$COMPONENT_LOG" 2>&1 &
    wm_pid=$!
}

launch_panel() {
    xfce4-panel --disable-wm-check >>"$COMPONENT_LOG" 2>&1 &
    panel_pid=$!
}

launch_desktop() {
    xfdesktop --disable-wm-check >>"$COMPONENT_LOG" 2>&1 &
    desktop_pid=$!
}

pid_is_live() {
    local pid="${1:-}"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
}

component_pid_running() {
    local pid="${1:-}" stat_pid="" comm="" state="" parent_pid=""
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    [[ -r "/proc/$pid/stat" ]] || return 1
    IFS=' ' read -r stat_pid comm state parent_pid _ < "/proc/$pid/stat" || return 1
    [[ "$stat_pid" == "$pid" ]] && [[ "$state" != Z ]] && [[ "$parent_pid" == "$$" ]]
}

wait_for_wm() {
    local attempt
    for attempt in $(seq 1 30); do
        if wm_ready; then
            return 0
        fi
        sleep 0.1
    done
    return 1
}

# Apply the display scale BEFORE the components launch, so xfsettingsd
# broadcasts the right XSETTINGS DPI from the first frame, the xrdb Xft.dpi
# resource is present before Chrome/Electron/panel/xfdesktop start (they read it
# only at launch), and the panel/icon SIZES are set before the panel and
# xfdesktop read them. Every write is timeout-bounded so this cannot stall
# startup. Runs foreground so the values are in place when the daemons come up.
#
# Fast path: at the default 100% the xrdb/xfconf writes here produce the stock
# 96 DPI / default sizes — identical to a guest that was never scaled — yet they
# still cost several in-session xfconf spawns plus a possible first-run xfconfd
# autoactivation on the critical path. Skip the apply ONLY when the current scale
# is 100 AND the last applied scale was already 100 (recorded in a persisted
# marker). The first ever run, and every transition (including any change back
# to 100, which must undo a previous non-100), still runs the full apply and then
# records the value. Any non-100 scale always applies.
LDFA_APPLIED_SCALE_MARKER="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/applied-scale"
if [ "$LDFA_SCALE" = 100 ] && \
   [ "$(cat "$LDFA_APPLIED_SCALE_MARKER" 2>/dev/null || true)" = 100 ]; then
    printf '[%s] display scale already 100%%; skipping xfconf/xrdb apply\n' \
        "$(date -Iseconds)"
else
    apply_desktop_scale
    printf '%s' "$LDFA_SCALE" > "$LDFA_APPLIED_SCALE_MARKER" 2>/dev/null || true
fi
launch_settings
launch_wm
launch_panel
launch_desktop
wait_for_wm || {
    printf '[%s] xfwm4 did not publish a root window manager\n' "$(date -Iseconds)" >&2
    exit 71
}

# Now that the desktop is usable, run the Electron sandbox/scale .desktop sweep
# off the critical path. It is fire-and-forget: it only rewrites user-level
# launcher overrides for the NEXT time an Electron app is started by hand, so a
# freshly opened desktop never waits on it. The component supervisor loop below
# tolerates this extra background child (its no-arg `wait -n` handles the reap).
scan_and_fix_electron \
    >>"${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/electron-fix.log" 2>&1 &

CHROME_RESTORE_REQUEST="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/chrome-restore-request"
if [[ -f "${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/chrome-running" ]]; then
    : > "$CHROME_RESTORE_REQUEST"
fi

# Do not poll with xset, ps, cat or sleep: every external command becomes an
# Android-visible child under PRoot. Bash 5's wait -n blocks on child exit
# events and wakes immediately when Android trims any component. Do not pass
# the remembered component PIDs to wait: one wait -n can reap several jobs
# while reporting only one of them, and a later call with those stale PIDs can
# otherwise block forever on the sole surviving child.
restore_helper_pid=""
failure_window_started=$SECONDS
failure_count=0

# An interrupted Chrome from the previous Android process generation must also
# be restored on an otherwise healthy, freshly launched XFCE session.
if [[ -f "$CHROME_RESTORE_REQUEST" ]]; then
    rm -f "$CHROME_RESTORE_REQUEST"
    restore_chrome_after_wm_ready &
    restore_helper_pid=$!
fi

while true; do
    exited_component_pid=""
    wait -n -p exited_component_pid || true

    # Android can deliver a batch of SIGKILLs a few milliseconds apart. Let
    # that burst settle before sampling all four direct children so the first
    # notification cannot race the remaining deaths. This one-shot sleep runs
    # only after a child exits; there is no steady-state polling process.
    sleep 0.05
    recovery_timestamp="$(date -Iseconds)"

    # Several children can be SIGKILLed in one Android trim pass while wait -n
    # reports only one PID. Read procfs with Bash builtins: a surviving direct
    # child must still have this shell as PPID and must not be a zombie. This is
    # reliable for both one-process and all-process trims and spawns no checker.

    recovered_component=0
    recovered_wm=0

    if ! component_pid_running "$settings_pid"; then
        printf '[%s] restarting xfsettingsd\n' "$recovery_timestamp"
        launch_settings
        recovered_component=1
    fi
    if ! component_pid_running "$wm_pid"; then
        printf '[%s] restarting xfwm4\n' "$recovery_timestamp"
        launch_wm
        recovered_wm=1
        recovered_component=1
    fi
    if ! component_pid_running "$panel_pid"; then
        printf '[%s] restarting xfce4-panel\n' "$recovery_timestamp"
        launch_panel
        recovered_component=1
    fi
    if ! component_pid_running "$desktop_pid"; then
        printf '[%s] restarting xfdesktop\n' "$recovery_timestamp"
        launch_desktop
        recovered_component=1
    fi

    # A completed Chrome restore helper also wakes the no-argument wait -n.
    # Count only actual XFCE replacements toward the crash-loop threshold.
    if [[ "$recovered_component" == 1 ]]; then
        if (( SECONDS - failure_window_started > 5 )); then
            failure_window_started=$SECONDS
            failure_count=0
        fi
        failure_count=$((failure_count + 1))
        if (( failure_count >= 12 )); then
            printf '[%s] XFCE components repeatedly exited; leaving component supervisor\n' \
                "$recovery_timestamp" >&2
            exit 72
        fi
        if [[ -f "${XDG_STATE_HOME:-$HOME/.local/state}/ldfa/chrome-running" ]]; then
            : > "$CHROME_RESTORE_REQUEST"
        fi
    fi
    if [[ -f "$CHROME_RESTORE_REQUEST" ]]; then
        rm -f "$CHROME_RESTORE_REQUEST"
        if ! pid_is_live "$restore_helper_pid"; then
            restore_chrome_after_wm_ready &
            restore_helper_pid=$!
        fi
    fi
    # Panel, desktop and Chrome all use WM-independent startup paths. Let them
    # initialize in parallel with the replacement xfwm4, then validate the WM;
    # serializing these launches added almost a second to visible recovery.
    if [[ "$recovered_wm" == 1 ]]; then
        wait_for_wm || true
    fi
done
SESSION
}

desktop_runtime_ready() {
    local id="$1"
    timeout 4s proot-distro login "$id" -- /bin/bash -c \
        'test -x /usr/local/bin/ldfa-session &&
         grep -Fqx "$1" /usr/local/bin/ldfa-session &&
         # fish users need the /etc/fish/conf.d snippet too (fish ignores the bash
         # rc files); require it at the same marker so a container missing it
         # re-provisions.
         test -f /etc/fish/conf.d/00-ldfa.fish &&
         grep -Fqx "$1" /etc/fish/conf.d/00-ldfa.fish' \
        _ "$DESKTOP_RUNTIME_MARKER" \
        >/dev/null 2>&1
}

ensure_desktop_runtime() {
    local id="$1"
    validate_id "$id"
    desktop_runtime_ready "$id" && return 0

    unset PROOT_NO_SECCOMP
    desktop_session_script | proot-distro login "$id" -- /bin/bash -c '
        set -Eeuo pipefail
        install -d -m 0755 /usr/local/bin
        temporary="/usr/local/bin/.ldfa-session.$$"
        trap '\''rm -f "$temporary"'\'' EXIT HUP INT TERM
        cat > "$temporary"
        chmod 0755 "$temporary"
        mv -f "$temporary" /usr/local/bin/ldfa-session

        # The session script exports ELECTRON_DISABLE_SANDBOX for everything the
        # desktop launches (panel, menus, .desktop entries). Also add it to the
        # desktop user shell rc files so an Electron app started by hand from the
        # XFCE terminal (e.g. `claude-desktop`) inherits it too — .bashrc for the
        # non-login interactive shells xfce4-terminal opens, .profile for login
        # shells. Idempotent, and guest-owned so the user can override it.
        for shell_rc in /home/desktop/.profile /home/desktop/.bashrc; do
            [[ -f "$shell_rc" ]] || { : > "$shell_rc"; chown desktop:desktop "$shell_rc"; }
            if ! grep -Fq "ELECTRON_DISABLE_SANDBOX" "$shell_rc"; then
                printf '\''\n# LDFA: Electron/Chromium apps cannot sandbox under PRoot; run unsandboxed\nexport ELECTRON_DISABLE_SANDBOX=1\n'\'' >> "$shell_rc"
            fi
        done

        # fish is a non-POSIX shell that reads NEITHER .profile NOR .bashrc, so
        # none of the PATH/env lines above reach a user who set their login shell
        # to fish (a common choice). fish DOES source every /etc/fish/conf.d/*.fish
        # on startup for all users and all modes (login, interactive, script), so
        # a single system snippet there covers fish completely. We create the
        # directory even when fish is not installed yet, so it applies the moment
        # the user installs fish. The snippet mirrors what the bash/.profile PATH
        # lines and the Electron env do:
        #   - ~/.local/bin   where vendor curl installers (the Claude Code
        #                    install.sh) put their launcher — the exact directory
        #                    missing from the fish default PATH that makes claude
        #                    "not found".
        #   - ~/.npm-global/bin  legacy compat for older LDFA npm-global installs.
        #   - ELECTRON_DISABLE_SANDBOX=1  so Electron apps launched from a fish
        #                    terminal run unsandboxed like everywhere else.
        # fish_add_path -g keeps this out of universal variables (no persisted
        # side effects); -p prepends so ~/.local/bin wins, matching bash.
        install -d -m 0755 /etc/fish/conf.d
        cat > /etc/fish/conf.d/00-ldfa.fish <<'"'"'LDFA_FISH'"'"'
# LDFA_SESSION_RUNTIME_VERSION=27
# Managed by LDFA. fish ignores ~/.profile and ~/.bashrc, so the PATH and env
# LDFA sets for bash are re-applied here for fish users. conf.d is sourced in
# every fish mode (login, interactive, script), so no status guard is needed.
fish_add_path -g -p $HOME/.local/bin $HOME/.npm-global/bin
set -gx ELECTRON_DISABLE_SANDBOX 1
LDFA_FISH
        chmod 0644 /etc/fish/conf.d/00-ldfa.fish
        trap - EXIT HUP INT TERM
    '
}

audio_client_ready() {
    local id="$1"
    # dpkg-query with -f="${Status}\n" leaves the \n literal when this string is
    # passed through the nested proot/bash -c layers, which produced empty output
    # and made this check always fail (forcing a needless apt run every start).
    # Query each package individually with no newline in the format instead.
    timeout 8s proot-distro login "$id" -- /bin/bash -c \
        'for package in pulseaudio-utils libasound2-plugins; do
             [ "$(dpkg-query -W -f='"'"'${Status}'"'"' "$package" 2>/dev/null)" = \
                 "install ok installed" ] || exit 1
         done
         test -f /etc/pulse/client.conf.d/99-ldfa.conf &&
         grep -Fqx "$1" /etc/pulse/client.conf.d/99-ldfa.conf &&
         grep -Fq "default-server = unix:/tmp/ldfa-pulse/native" \
             /etc/pulse/client.conf.d/99-ldfa.conf &&
         test -f /etc/alsa/conf.d/99-ldfa-pulse.conf &&
         grep -Fqx "$1" /etc/alsa/conf.d/99-ldfa-pulse.conf &&
         grep -Fq "type pulse" /etc/alsa/conf.d/99-ldfa-pulse.conf' \
        _ "$AUDIO_CLIENT_MARKER" \
        >/dev/null 2>&1
}

ensure_audio_client() {
    local id="$1"
    validate_id "$id"
    if audio_client_ready "$id"; then
        say "Debian音声クライアントは設定済みです。"
        return 0
    fi

    unset PROOT_NO_SECCOMP
    proot-distro login "$id" -- /bin/bash -s <<'AUDIO_CLIENT_SETUP'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
APT=(
    apt-get
    -o Acquire::Retries=1
    -o Acquire::http::Timeout=5
    -o Acquire::https::Timeout=5
    -o Dpkg::Lock::Timeout=3
    -o Dpkg::Use-Pty=0
)

# Write app-owned system drop-ins before any optional network migration. User
# PulseAudio and ALSA files remain untouched and may override these defaults;
# the desktop session's explicit PULSE_SERVER remains the canonical route.
install -d -m 0755 /etc/pulse/client.conf.d /etc/alsa/conf.d
cat > /etc/pulse/client.conf.d/99-ldfa.conf <<'PULSE_CLIENT'
# LDFA_AUDIO_CLIENT_VERSION=3
default-server = unix:/tmp/ldfa-pulse/native
autospawn = no
# PRoot cannot pass SHM/memfd descriptors across the guest boundary. Without
# these, a playback stream authenticates but dies during the SHM/srbchannel
# handshake and the Android sink stays IDLE (no audio). Force socket transport.
enable-shm = no
enable-memfd = no
PULSE_CLIENT

cat > /etc/alsa/conf.d/99-ldfa-pulse.conf <<'ALSA_PULSE'
# LDFA_AUDIO_CLIENT_VERSION=3
pcm.!default {
    type pulse
}
ctl.!default {
    type pulse
}
ALSA_PULSE
chmod 0644 \
    /etc/pulse/client.conf.d/99-ldfa.conf \
    /etc/alsa/conf.d/99-ldfa-pulse.conf

# A short-lived development build wrote these two files before the system
# drop-in design was finalized. Remove only byte-for-byte LDFA v1 content; an
# arbitrary user configuration is never deleted or rewritten.
legacy_pulse_content=$'# LDFA_AUDIO_CLIENT_VERSION=1\ndefault-server = unix:/tmp/ldfa-pulse/native\nautospawn = no'
legacy_alsa_content=$'# LDFA_AUDIO_CLIENT_VERSION=1\npcm.!default {\n    type pulse\n}\nctl.!default {\n    type pulse\n}'
if [[ -f /home/desktop/.config/pulse/client.conf ]] && \
    cmp -s /home/desktop/.config/pulse/client.conf \
        <(printf '%s\n' "$legacy_pulse_content"); then
    rm -f /home/desktop/.config/pulse/client.conf
fi
if [[ -f /home/desktop/.asoundrc ]] && \
    cmp -s /home/desktop/.asoundrc <(printf '%s\n' "$legacy_alsa_content"); then
    rm -f /home/desktop/.asoundrc
fi

packages_ready=1
for package in pulseaudio-utils libasound2-plugins; do
    dpkg-query -W -f='${Status}\n' "$package" 2>/dev/null | \
        grep -Fxq 'install ok installed' || packages_ready=0
done
if [[ "$packages_ready" != 1 ]]; then
    printf '\n[%s] Debian音声クライアントを準備しています\n' "$(date -Iseconds)"
    # Keep existing-container startup responsive when the network is offline.
    # Only the update/download phase is interruptible; after all packages are
    # cached, the small local dpkg transaction is allowed to finish safely.
    if ! timeout --signal=INT --kill-after=2s 15s /bin/bash -c '
        set -Eeuo pipefail
        APT=(
            apt-get
            -o Acquire::Retries=1
            -o Acquire::http::Timeout=5
            -o Acquire::https::Timeout=5
            -o Dpkg::Lock::Timeout=3
            -o Dpkg::Use-Pty=0
        )
        "${APT[@]}" update
        "${APT[@]}" --download-only install -y --no-install-recommends \
            pulseaudio-utils \
            libasound2-plugins
    '; then
        printf '[%s] 音声補助パッケージの取得を15秒で中断しました。次回起動時に再試行します。\n' \
            "$(date -Iseconds)" >&2
        exit 75
    fi
    "${APT[@]}" --no-download install -y --no-install-recommends \
        pulseaudio-utils \
        libasound2-plugins
fi

command -v pactl >/dev/null
compgen -G '/usr/lib/*/alsa-lib/libasound_module_pcm_pulse.so' >/dev/null
AUDIO_CLIENT_SETUP
}

google_chrome_ready() {
    local id="$1"
    proot-distro login "$id" -- /bin/bash -c \
        'test -x /usr/bin/google-chrome-stable &&
         test -x /usr/local/bin/google-chrome-ldfa &&
         test -f /home/desktop/.local/share/applications/google-chrome.desktop &&
         grep -Fqx "$1" /usr/local/bin/google-chrome-ldfa' \
        _ "$CHROME_LAUNCHER_MARKER" \
        >/dev/null 2>&1
}

ensure_google_chrome() {
    local id="$1"
    validate_id "$id"
    if google_chrome_ready "$id"; then
        say "Google Chromeはインストール済みです。"
        return 0
    fi

    # Google currently publishes stable Linux packages for both 64-bit Debian
    # architectures used by LDFA. Download the matching official package at
    # provisioning time so Chrome remains independently updateable and the APK
    # does not vendor a stale browser binary.
    unset PROOT_NO_SECCOMP
    proot-distro login "$id" -- /bin/bash -s <<'CHROME_SETUP'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
APT=(apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0)

architecture="$(dpkg --print-architecture)"
case "$architecture" in
    amd64|arm64) ;;
    *)
        printf 'Google Chromeの公式Linuxパッケージは%sへ対応していません。Debian XFCEの設定は継続します。\n' \
            "$architecture" >&2
        exit 0
        ;;
esac

if [[ ! -x /usr/bin/google-chrome-stable ]]; then
    printf '\n[%s] Google Chrome stable (%s)を準備しています\n' "$(date -Iseconds)" "$architecture"
    "${APT[@]}" update
    "${APT[@]}" install -y --no-install-recommends ca-certificates wget

    chrome_package="$(mktemp /tmp/google-chrome-stable.XXXXXX.deb)"
    cleanup_chrome_package() { rm -f "$chrome_package"; }
    trap cleanup_chrome_package EXIT INT TERM
    wget --https-only --tries=3 --timeout=30 --progress=dot:giga \
        -O "$chrome_package" \
        "https://dl.google.com/linux/direct/google-chrome-stable_current_${architecture}.deb"

    [[ "$(dpkg-deb --field "$chrome_package" Package)" == google-chrome-stable ]]
    [[ "$(dpkg-deb --field "$chrome_package" Architecture)" == "$architecture" ]]
    "${APT[@]}" install -y --no-install-recommends "$chrome_package"
    rm -f "$chrome_package"
    trap - EXIT INT TERM
fi

install -d -m 0755 /usr/local/bin
cat > /usr/local/bin/google-chrome-ldfa <<'CHROME_LAUNCHER'
#!/bin/sh
# LDFA_CHROME_LAUNCHER_VERSION=8
# Chromium's namespace/setuid sandbox cannot establish its normal privilege
# boundary inside Android PRoot. Run Chrome as the unprivileged desktop user
# with the PRoot-compatible flags required by this environment. Keep a small,
# bounded renderer pool instead of single-process/forced-low-end modes: Google
# sign-in remains compatible while Android, Gboard and XFCE have more headroom.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa"
running_marker="$state_dir/chrome-running"
xdg_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$state_dir"
mkdir -p "$xdg_app_dir"
[ -f "$xdg_app_dir/mimeapps.list" ] || : > "$xdg_app_dir/mimeapps.list"
: > "$running_marker"

# Reproduce the vendor wrapper's required environment, but invoke the browser
# binary directly. Google's wrapper keeps two `cat` pipe relays alive for the
# lifetime of Chrome; avoiding only those relays preserves Chrome's normal
# multi-process fault isolation while staying below Android's child-process cap.
export CHROME_WRAPPER=/opt/google/chrome/google-chrome
export CHROME_VERSION_EXTRA=stable
export GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME

chrome_running() {
    pgrep -x chrome >/dev/null 2>&1 || \
        pgrep -x google-chrome >/dev/null 2>&1 || \
        pgrep -x google-chrome-stable >/dev/null 2>&1
}

restart_attempt=0
while :; do
    if [ "$restart_attempt" -eq 0 ]; then
        /opt/google/chrome/chrome \
            --no-sandbox \
            --disable-dev-shm-usage \
            --disable-background-mode \
            --disable-breakpad \
            --disable-crash-reporter \
            --disable-extensions \
            --disable-component-extensions-with-background-pages \
            --disable-gpu \
            --no-zygote \
            --ozone-platform=x11 \
            --password-store=basic \
            --renderer-process-limit=2 \
            "$@"
    else
        /opt/google/chrome/chrome \
            --no-sandbox \
            --disable-dev-shm-usage \
            --disable-background-mode \
            --disable-breakpad \
            --disable-crash-reporter \
            --disable-extensions \
            --disable-component-extensions-with-background-pages \
            --disable-gpu \
            --no-zygote \
            --ozone-platform=x11 \
            --password-store=basic \
            --renderer-process-limit=2 \
            --restore-last-session \
            --disable-session-crashed-bubble \
            "$@"
    fi
    status=$?

    # If Android trims only Chrome while this lightweight launcher survives,
    # retry once after the old helper processes disappear. Repeated crashes are
    # left to the Activity-resume/supervisor recovery path instead of looping.
    if [ "$status" -eq 0 ] || [ "$restart_attempt" -ge 1 ]; then
        break
    fi
    restart_attempt=$((restart_attempt + 1))
    for wait_attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        chrome_running || break
        sleep 0.1
    done
done

# A second launcher invocation returns while the original browser is still
# alive. Remove the marker only after a clean exit and after every Chrome
# process is actually gone; SIGKILL/LMK therefore leaves recoverable state.
if [ "$status" -eq 0 ] && ! chrome_running; then
    rm -f "$running_marker"
fi
exit "$status"
CHROME_LAUNCHER
chmod 0755 /usr/local/bin/google-chrome-ldfa
ln -sfn google-chrome-ldfa /usr/local/bin/google-chrome
ln -sfn google-chrome-ldfa /usr/local/bin/google-chrome-stable

install -d -m 0755 -o desktop -g desktop /home/desktop/.local/share/applications
cat > /home/desktop/.local/share/applications/google-chrome.desktop <<'CHROME_DESKTOP'
[Desktop Entry]
Version=1.0
Name=Google Chrome
Comment=Googleのウェブブラウザ
Exec=/usr/local/bin/google-chrome-ldfa %U
Terminal=false
Type=Application
Icon=google-chrome
Categories=Network;WebBrowser;
MimeType=text/html;text/xml;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;
StartupNotify=true
StartupWMClass=Google-chrome
CHROME_DESKTOP
chown desktop:desktop /home/desktop/.local/share/applications/google-chrome.desktop

# Debian's bottom-panel Web Browser launcher delegates to exo-open. Select the
# bundled LDFA launcher without replacing any terminal or file-manager choice
# the user may already have made.
install -d -m 0755 -o desktop -g desktop /home/desktop/.config/xfce4
helpers_file=/home/desktop/.config/xfce4/helpers.rc
touch "$helpers_file"
if grep -q '^WebBrowser=' "$helpers_file"; then
    sed -i 's/^WebBrowser=.*/WebBrowser=google-chrome/' "$helpers_file"
else
    printf 'WebBrowser=google-chrome\n' >> "$helpers_file"
fi
chown desktop:desktop "$helpers_file"

/usr/bin/google-chrome-stable --version
apt-get clean
rm -rf /var/lib/apt/lists/*
CHROME_SETUP
}

nodejs_ready() {
    local id="$1"
    proot-distro login "$id" -- /bin/bash -c \
        'test -x /opt/nodejs/bin/node &&
         test -x /opt/nodejs/bin/npm &&
         test -x /usr/local/bin/node &&
         test -x /usr/local/bin/npm &&
         # Vendor curl installers (Claude Code'"'"'s install.sh, rustup, ...) run
         # curl INSIDE the guest. Without Debian'"'"'s own curl the container PATH
         # falls through to Termux'"'"'s curl, which resolves DNS against Android
         # rather than the container and can fail. Require the real /usr/bin/curl
         # so a container missing it re-provisions and installs it.
         test -x /usr/bin/curl &&
         test -f /opt/nodejs/ldfa-nodejs-version &&
         grep -Fqx "$1" /opt/nodejs/ldfa-nodejs-version &&
         # Global npm installs must land in /usr/local/bin (on every shell PATH);
         # the builtin npm config layer carries that default.
         grep -Fqx "prefix=/usr/local" /opt/nodejs/lib/node_modules/npm/npmrc &&
         # Vendor curl installers (Claude Code'"'"'s install.sh, pip --user, ...) put
         # launchers in ~/.local/bin, so it must be on PATH in every shell.
         grep -Fq ".local/bin" /home/desktop/.bashrc &&
         grep -Fq ".local/bin" /home/desktop/.profile &&
         # Legacy-compat PATH for ~/.npm-global installs from older LDFA versions.
         grep -Fq ".npm-global/bin" /home/desktop/.bashrc &&
         /opt/nodejs/bin/node -e "process.exit(parseInt(process.versions.node) >= 22 ? 0 : 1)"' \
        _ "$NODEJS_MARKER" \
        >/dev/null 2>&1
}

ensure_nodejs() {
    local id="$1"
    validate_id "$id"
    if nodejs_ready "$id"; then
        say "Node.jsランタイムはインストール済みです。"
        return 0
    fi

    # Debian 12's apt Node.js is 18.x, which is too old for current Node CLIs
    # (Claude Code and others require Node >= 22). Install the official upstream
    # static build into a dedicated /opt/nodejs directory and symlink node/npm/npx
    # into /usr/local/bin, so tools installed with `npm install -g` find a modern,
    # glibc-based, PRoot-compatible runtime. The tarball is verified against the
    # pinned upstream SHA-256 before extraction.
    unset PROOT_NO_SECCOMP
    NODEJS_VERSION="$NODEJS_VERSION" \
    NODEJS_SHA256_x64="$NODEJS_SHA256_x64" \
    NODEJS_SHA256_arm64="$NODEJS_SHA256_arm64" \
    NODEJS_MARKER="$NODEJS_MARKER" \
    proot-distro login "$id" -- /usr/bin/env \
        NODEJS_VERSION="$NODEJS_VERSION" \
        NODEJS_SHA256_x64="$NODEJS_SHA256_x64" \
        NODEJS_SHA256_arm64="$NODEJS_SHA256_arm64" \
        NODEJS_MARKER="$NODEJS_MARKER" \
        /bin/bash -s <<'NODEJS_SETUP'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
APT=(apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0)

# Map the Debian architecture to the upstream Node.js download arch. Only the
# two 64-bit architectures LDFA targets are supported; on anything else Node is
# skipped and the desktop still starts.
architecture="$(dpkg --print-architecture)"
case "$architecture" in
    amd64) node_arch="x64";   node_sha256="$NODEJS_SHA256_x64" ;;
    arm64) node_arch="arm64"; node_sha256="$NODEJS_SHA256_arm64" ;;
    *)
        printf 'Node.jsの自動導入は%sへ対応していません。Debian XFCEの設定は継続します。\n' \
            "$architecture" >&2
        exit 0
        ;;
esac

# Skip the download+extract when the runtime is already the pinned version. This
# lets a marker bump that only changes shell configuration (e.g. adding the PATH
# to .bashrc for existing installs) run cheaply without re-fetching ~30 MB.
if [[ -x /opt/nodejs/bin/node ]] && \
    [[ "$(/opt/nodejs/bin/node --version 2>/dev/null)" == "$NODEJS_VERSION" ]]; then
    printf '\n[%s] Node.js %s は導入済みです。設定のみ更新します\n' \
        "$(date -Iseconds)" "$NODEJS_VERSION"
    # Runtime already current, but an existing container may predate the guest
    # curl requirement. Install Debian's own curl when /usr/bin/curl is absent.
    # This is NOT swallowed: nodejs_ready now requires /usr/bin/curl, so a failure
    # here leaves the marker unrecorded and the next start retries — exactly what
    # we want when the network was briefly unavailable.
    if [[ ! -x /usr/bin/curl ]]; then
        "${APT[@]}" update
        "${APT[@]}" install -y --no-install-recommends ca-certificates curl
    fi
else
    printf '\n[%s] Node.js %s (%s)を準備しています\n' \
        "$(date -Iseconds)" "$NODEJS_VERSION" "$node_arch"
    "${APT[@]}" update
    # curl is not needed by this script (it uses wget), but vendor install
    # scripts users run afterwards — including Claude Code's own
    # `curl -fsSL https://claude.ai/install.sh | bash` — assume a real curl in
    # the guest. Without it PATH falls through to Termux's curl, which resolves
    # against Android rather than the container.
    "${APT[@]}" install -y --no-install-recommends \
        ca-certificates wget xz-utils curl

    node_tarball="$(mktemp /tmp/nodejs.XXXXXX.tar.xz)"
    cleanup_node_tarball() { rm -f "$node_tarball"; }
    trap cleanup_node_tarball EXIT INT TERM

    node_basename="node-${NODEJS_VERSION}-linux-${node_arch}"
    wget --https-only --tries=3 --timeout=30 --progress=dot:giga \
        -O "$node_tarball" \
        "https://nodejs.org/dist/${NODEJS_VERSION}/${node_basename}.tar.xz"

    # Verify the pinned upstream checksum before touching the filesystem.
    printf '%s  %s\n' "$node_sha256" "$node_tarball" | sha256sum -c - >/dev/null

    # Extract into a dedicated, always-empty directory. Unpacking straight into
    # /usr/local fails under PRoot: tar cannot utime pre-existing directories
    # (EPERM) and Node's top-level README/LICENSE collide with other packages'
    # files. A clean target sidesteps both and makes upgrades/removal trivial.
    # --no-same-owner and --no-same-permissions avoid chown/chmod PRoot rejects.
    rm -rf /opt/nodejs
    mkdir -p /opt/nodejs
    tar -xJf "$node_tarball" -C /opt/nodejs --strip-components=1 \
        --no-same-owner --no-same-permissions
    rm -f "$node_tarball"
    trap - EXIT INT TERM
fi

# Expose the runtime on the default PATH via symlinks in /usr/local/bin.
install -d -m 0755 /usr/local/bin
for tool in node npm npx corepack; do
    if [[ -e "/opt/nodejs/bin/$tool" ]]; then
        ln -sfn "/opt/nodejs/bin/$tool" "/usr/local/bin/$tool"
    fi
done

# Point npm's global prefix at /usr/local via npm's BUILTIN config layer. With
# this, `npm install -g` places launchers directly into /usr/local/bin — already
# on the default PATH of every shell, login or not — so `claude`/`codex` work in
# the XFCE terminal with no shell-rc dependency at all. Under PRoot the desktop
# user can write there (the same real Android uid owns the whole rootfs), so no
# sudo is needed either. The builtin layer is what distributions use for this;
# a user's own ~/.npmrc still overrides it if they want a different prefix.
install -d -m 0755 /usr/local/lib/node_modules
printf 'prefix=/usr/local\n' > /opt/nodejs/lib/node_modules/npm/npmrc

# Earlier LDFA versions steered installs to ~/.npm-global via ~/.npmrc, which
# was fragile (the PATH line lived in shell rc files the terminal did not always
# read). Remove that file only when it is byte-for-byte ours; a user-authored
# ~/.npmrc is never touched. Keep the PATH lines below so anything already
# installed under ~/.npm-global keeps working.
if [[ -f /home/desktop/.npmrc ]] && \
    cmp -s /home/desktop/.npmrc <(printf 'prefix=/home/desktop/.npm-global\n'); then
    rm -f /home/desktop/.npmrc
fi

# Two more PATH entries, in .profile (login shells) and .bashrc (the interactive
# non-login shells xfce4-terminal opens), both idempotent:
#
#   ~/.local/bin    the XDG/systemd user bin dir. Vendor curl installers put
#                   their launcher here — Claude Code's own
#                   `curl -fsSL https://claude.ai/install.sh | bash` runs
#                   `claude install`, which lands in ~/.local/bin. LDFA replaces
#                   Debian's stock .profile, which would otherwise have added
#                   this directory, so without re-adding it those installers
#                   succeed but leave a "command not found" shell.
#   ~/.npm-global/bin  legacy compat for global installs made by older LDFA
#                   versions, before the npm prefix moved to /usr/local.
install -d -m 0755 -o desktop -g desktop /home/desktop/.local/bin
for shell_rc in /home/desktop/.profile /home/desktop/.bashrc; do
    [[ -f "$shell_rc" ]] || { : > "$shell_rc"; chown desktop:desktop "$shell_rc"; }
    if ! grep -Fq '.local/bin' "$shell_rc"; then
        printf '\n# LDFA: expose user-installed CLIs (curl installers, pip --user) on PATH\n%s\n' \
            'export PATH="$HOME/.local/bin:$PATH"' >> "$shell_rc"
    fi
    if ! grep -Fq '.npm-global/bin' "$shell_rc"; then
        printf '\n# LDFA: expose npm global CLIs installed by older versions on PATH\n%s\n' \
            'export PATH="$HOME/.npm-global/bin:$PATH"' >> "$shell_rc"
    fi
done

# Verify with absolute paths so a minimal rootfs PATH cannot make this fail, then
# stamp the version marker only after node and npm both run.
/opt/nodejs/bin/node --version
/opt/nodejs/bin/node /opt/nodejs/bin/npm --version
install -d -m 0755 /opt/nodejs
printf '%s\n' "$NODEJS_MARKER" > /opt/nodejs/ldfa-nodejs-version
NODEJS_SETUP
}

mark_chrome_for_restore_if_running() {
    local id="$1"
    timeout 4s proot-distro login "$id" --user desktop -- /bin/bash -c '
        state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa"
        if pgrep -x chrome >/dev/null 2>&1 ||
            pgrep -x google-chrome >/dev/null 2>&1 ||
            pgrep -x google-chrome-stable >/dev/null 2>&1; then
            mkdir -p "$state_dir"
            : > "$state_dir/chrome-running"
        fi
    ' >/dev/null 2>&1 || true
}

clear_chrome_restore_marker() {
    local id="$1"
    timeout 4s proot-distro login "$id" --user desktop -- /bin/rm -f \
        /home/desktop/.local/state/ldfa/chrome-running \
        >/dev/null 2>&1 || true
}

request_chrome_restore_if_needed() {
    local id="$1" result settings_pid="" settings_name="" parent_pid=""
    local -a parent_args=()
    result="$(timeout 4s proot-distro login "$id" --user desktop -- /bin/bash -c '
        state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/ldfa"
        marker="$state_dir/chrome-running"
        request="$state_dir/chrome-restore-request"
        if [[ -f "$marker" ]] &&
            ! pgrep -x chrome >/dev/null 2>&1 &&
            ! pgrep -x google-chrome >/dev/null 2>&1 &&
            ! pgrep -x google-chrome-stable >/dev/null 2>&1; then
            : > "$request"
            printf "restore_needed=1\n"
        fi
    ' 2>/dev/null)" || return 1
    [[ "$result" == *"restore_needed=1"* ]] || return 0

    # Wake wait -n without adding a watcher process. xfsettingsd owns no desktop
    # window, so replacing only this direct child leaves WM, panel and wallpaper
    # visible while the same supervisor event consumes the Chrome request.
    [[ -r "$PREFIX/tmp/runtime-desktop/xfsettingsd.pid" ]] && \
        IFS= read -r settings_pid < "$PREFIX/tmp/runtime-desktop/xfsettingsd.pid"
    if [[ "$settings_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$settings_pid/status" ]]; then
        while IFS=$'\t ' read -r key value _; do
            [[ "$key" == Name: ]] && settings_name="$value"
        done < "/proc/$settings_pid/status"
    fi
    if [[ "$settings_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$settings_pid/stat" ]]; then
        IFS=' ' read -r _ _ _ parent_pid _ < "/proc/$settings_pid/stat" || true
    fi
    if [[ "$parent_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$parent_pid/cmdline" ]]; then
        mapfile -d '' -t parent_args < "/proc/$parent_pid/cmdline" || true
    fi
    [[ "$settings_name" == xfsettingsd ]] && \
        [[ " ${parent_args[*]} " == *" /usr/local/bin/ldfa-session "* ]] && \
        kill -0 "$settings_pid" 2>/dev/null && \
        kill -TERM "$settings_pid" 2>/dev/null
}

stop_one() {
    local id="$1" preserve_chrome_restore="${2:-0}" session active="" worker_pid="" attempt
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || return 0

    if [[ "$preserve_chrome_restore" == 1 ]]; then
        mark_chrome_for_restore_if_running "$id"
    else
        clear_chrome_restore_marker "$id"
    fi

    session="$(run_session "$id")"
    set_status "$id" stopping 100 "Linuxデスクトップを停止しています…"
    touch "$(stop_file "$id")"

    if tmux_alive "$session"; then
        worker_pid="$(tmux list-panes -t "$session" -F '#{pane_pid}' 2>/dev/null | head -n 1 || true)"
        tmux kill-session -t "$session" >/dev/null 2>&1 || true
        if [[ "$worker_pid" =~ ^[0-9]+$ ]]; then
            for attempt in $(seq 1 40); do
                kill -0 "$worker_pid" 2>/dev/null || break
                sleep 0.05
            done
        fi
    fi

    if has proot-distro; then
        proot-distro kill "$id" >/dev/null 2>&1 || \
            pkill -f "proot.*${id}" >/dev/null 2>&1 || true
    fi

    [[ -f "$(active_file)" ]] && active="$(cat "$(active_file)" 2>/dev/null || true)"
    if [[ "$active" == "$id" ]]; then
        rm -f "$(active_file)"
    fi

    set_status "$id" ready 100 "Linuxデスクトップを起動できます"
}

stop_other_desktops() {
    local keep="$1" dir id state
    shopt -s nullglob
    for dir in "$META_ROOT"/*; do
        [[ -d "$dir" ]] || continue
        id="$(basename "$dir")"
        [[ "$id" == "$keep" ]] && continue
        state="$(read_meta "$id" state unknown)"
        if [[ "$state" == running || "$state" == starting ]] || tmux_alive "$(run_session "$id")"; then
            stop_one "$id"
        fi
    done
    shopt -u nullglob
}

cmd_doctor() {
    local tmux_ok=0 proot_ok=0 storage_ok=0 audio_tools_ok=0 host_ok=0
    has tmux && tmux_ok=1
    has proot-distro && proot_ok=1
    has pulseaudio && has pactl && audio_tools_ok=1
    storage_linked "$HOME/storage/shared" && storage_ok=1
    [[ $tmux_ok -eq 1 && $proot_ok -eq 1 && $storage_ok -eq 1 && \
        $audio_tools_ok -eq 1 ]] && host_ok=1

    say "version=$VERSION"
    say "host_ready=$host_ok"
    say "tmux=$tmux_ok"
    say "proot_distro=$proot_ok"
    say "embedded_x11=1"
    say "audio_tools=$audio_tools_ok"
    say "storage=$storage_ok"
    say "shared_directory=$SHARED_ROOT"
}

cmd_audio_probe() {
    local id="${1:-}" sink="" guest_ready=0
    ensure_audio_bridge || die "Android音声出力を初期化できませんでした。"
    sink="$(pulse_real_sink)"
    if [[ -n "$id" ]]; then
        validate_id "$id"
        container_exists "$id" || die "Debian環境が見つかりません。"
        guest_audio_ready "$id" && guest_ready=1
        write_meta "$id" audio_ready "$guest_ready"
    fi
    say "audio_server=1"
    say "audio_socket=$PULSE_HOST_SOCKET"
    say "audio_sink=$sink"
    say "audio_guest=$guest_ready"
    [[ -z "$id" || "$guest_ready" == 1 ]] || \
        die "DebianからAndroid音声出力へ接続できませんでした。"
}

cmd_bootstrap() {
    local requested_version="${1:-$VERSION}"
    : > "$BOOTSTRAP_LOG"
    exec >>"$BOOTSTRAP_LOG" 2>&1

    say "[$(date -Iseconds)] 内蔵Linux基盤を準備しています（$requested_version）…"
    has pkg || die "内蔵ターミナルの初期展開が完了していません。"

    export DEBIAN_FRONTEND=noninteractive
    say "[$(date -Iseconds)] Termuxパッケージ一覧を更新します。"
    retry_command 3 3 pkg update -y

    say "[$(date -Iseconds)] PRoot Distroと監視ツールをインストールします。"
    retry_command 3 3 pkg install -y \
        proot \
        proot-distro \
        tmux \
        coreutils \
        procps \
        pulseaudio \
        termux-tools

    ensure_storage
    chmod 700 "$SELF" 2>/dev/null || true
    termux-wake-lock >/dev/null 2>&1 || true
    say "[$(date -Iseconds)] 内蔵Linux基盤の準備が完了しました。"
    cmd_doctor
}

cmd_list() {
    local dir id name state progress message display created alive
    shopt -s nullglob
    for dir in "$META_ROOT"/*; do
        [[ -d "$dir" ]] || continue
        id="$(basename "$dir")"
        name="$(read_meta "$id" name "$id")"
        state="$(read_meta "$id" state unknown)"
        progress="$(read_meta "$id" progress 0)"
        message="$(read_meta "$id" message '')"
        display="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
        created="$(read_meta "$id" created_at 0)"
        alive=0
        if tmux_alive "$(install_session "$id")" || tmux_alive "$(run_session "$id")"; then
            alive=1
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$id" "$(encode "$name")" "$state" "$progress" "$(encode "$message")" \
            "$display" "$created" "$alive" "xfce"
    done
    shopt -u nullglob
}

start_install_worker() {
    local id="$1" session
    validate_id "$id"
    session="$(install_session "$id")"
    tmux_alive "$session" && return 0
    rm -f "$(stop_file "$id")"
    tmux new-session -d -s "$session" "$SELF" worker-install "$id"
}

cmd_create() {
    local id="${1:-}" name="${2:-Linux Desktop}" dir
    validate_id "$id"
    has tmux || die "Linux基盤が未準備です。先にセットアップを実行してください。"
    has proot-distro || die "proot-distroが未インストールです。"
    ensure_storage

    dir="$(meta_dir "$id")"
    [[ ! -e "$dir" ]] || die "同じIDの環境がすでにあります。"

    mkdir -p "$dir" "$(shared_path "$id")"
    write_meta "$id" name "$name"
    write_meta "$id" desktop "xfce"
    write_meta "$id" distribution "debian"
    write_meta "$id" image "$LINUX_IMAGE"
    write_meta "$id" display "$DEFAULT_DISPLAY_NUMBER"
    write_meta "$id" created_at "$(date +%s)"
    write_meta "$id" installed 0
    set_status "$id" queued 1 "Debian XFCEのインストールを開始します…"
    : > "$(log_file "$id")"
    start_install_worker "$id"
    say "$id"
}

worker_failed() {
    local id="$1" rc="$2" line="$3"
    trap - ERR INT TERM
    set +e
    printf '\n[%s] worker failed: exit=%s line=%s\n' \
        "$(date -Iseconds)" "$rc" "$line" >> "$(log_file "$id")"
    set_status "$id" failed "$(read_meta "$id" progress 0)" \
        "処理に失敗しました。リアルタイムログを確認して再試行できます。"
    termux-wake-unlock >/dev/null 2>&1 || true
    exit "$rc"
}

install_container() {
    local id="$1" attempt image="$LINUX_IMAGE" legacy_distro="${LINUX_IMAGE%%:*}" install_help
    install_help="$(proot-distro install --help 2>&1 || true)"
    for attempt in 1 2 3; do
        printf '[%s] Installing %s as %s (attempt %s/3)\n' \
            "$(date -Iseconds)" "$image" "$id" "$attempt"
        # PRoot-Distro 5 accepts OCI image references directly. Keep the old
        # plugin name only for the legacy CLI, where tags are not supported.
        if [[ "$install_help" == *"--name"* ]]; then
            if proot-distro install --name "$id" "$image"; then
                return 0
            fi
        else
            if proot-distro install "$legacy_distro" --override-alias "$id"; then
                return 0
            fi
        fi
        proot-distro remove "$id" >/dev/null 2>&1 || true
        (( attempt < 3 )) && sleep 5
    done
    return 1
}

worker_install() {
    local id="$1" shared log expected_image current_image
    validate_id "$id"
    shared="$(shared_path "$id")"
    log="$(log_file "$id")"
    expected_image="$LINUX_IMAGE"
    current_image="$(read_meta "$id" image '')"
    mkdir -p "$shared"

    exec >>"$log" 2>&1
    trap 'worker_failed "$id" "$?" "$LINENO"' ERR
    trap 'worker_failed "$id" 130 "$LINENO"' INT
    trap 'worker_failed "$id" 143 "$LINENO"' TERM

    printf '\n[%s] Linux Desktop installation worker started: %s\n' "$(date -Iseconds)" "$id"
    printf '[%s] target image: %s\n' "$(date -Iseconds)" "$expected_image"
    termux-wake-lock >/dev/null 2>&1 || true

    if container_exists "$id" && [[ "$(read_meta "$id" installed 0)" != 1 ]]; then
        set_status "$id" installing 3 "以前の未完了環境を削除しています…"
        proot-distro remove "$id" >/dev/null 2>&1 || true
    fi

    if ! container_exists "$id"; then
        set_status "$id" installing 5 "Debianをダウンロードしています…"
        install_container "$id" || die "Debianの取得に失敗しました。ネットワーク接続を確認してください。"
        write_meta "$id" image "$expected_image"
    fi

    set_status "$id" installing 18 "パッケージ一覧を更新しています…"
    # Keep PRoot's syscall-trace acceleration enabled in Android app processes.
    # Forcing it off exposes guest syscalls to the app seccomp policy as ENOSYS.
    unset PROOT_NO_SECCOMP
    proot-distro login "$id" --bind "$shared:/mnt/android" -- \
        /bin/bash -s <<'CONTAINER_SETUP'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
APT=(apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0)

step() { printf '\n[%s] %s\n' "$(date -Iseconds)" "$*"; }

step "パッケージソースを確認しています"
sed -i 's/deb\.debian\.org/ftp.jp.debian.org/g' /etc/apt/sources.list || true

step "パッケージ一覧を更新しています"
"${APT[@]}" update

step "基本パッケージをインストールしています"
"${APT[@]}" install -y --no-install-recommends \
    ca-certificates \
    locales \
    sudo \
    dbus-x11 \
    procps \
    psmisc \
    xdg-user-dirs \
    x11-utils \
    x11-xserver-utils \
    mesa-utils \
    libgl1-mesa-dri \
    pulseaudio-utils \
    libasound2-plugins

step "XFCEデスクトップをインストールしています"
"${APT[@]}" install -y --no-install-recommends \
    xfce4 \
    xfce4-terminal \
    xfce4-notifyd \
    thunar \
    mousepad \
    ristretto \
    adwaita-icon-theme

step "日本語フォントとFcitx5/Mozcをインストールしています"
"${APT[@]}" install -y --no-install-recommends \
    fonts-noto-cjk \
    fonts-noto-color-emoji \
    fcitx5 \
    fcitx5-mozc \
    fcitx5-config-qt \
    im-config

command -v xset >/dev/null
command -v xrefresh >/dev/null
command -v xprop >/dev/null

step "APTキャッシュを整理しています"
apt-get clean
rm -rf /var/lib/apt/lists/*

step "日本語ロケールを設定しています"
if grep -q '^# *ja_JP.UTF-8 UTF-8' /etc/locale.gen; then
    sed -i 's/^# *ja_JP.UTF-8 UTF-8/ja_JP.UTF-8 UTF-8/' /etc/locale.gen
elif ! grep -q '^ja_JP.UTF-8 UTF-8' /etc/locale.gen; then
    printf 'ja_JP.UTF-8 UTF-8\n' >> /etc/locale.gen
fi
locale-gen ja_JP.UTF-8
update-locale LANG=ja_JP.UTF-8 LANGUAGE=ja_JP:ja

dbus-uuidgen --ensure=/etc/machine-id
if ! id desktop >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash desktop
fi

usermod -a -G sudo desktop
for group in audio video; do
    if getent group "$group" >/dev/null 2>&1; then
        usermod -a -G "$group" desktop
    fi
done

install -d -m 0750 /etc/sudoers.d
printf 'desktop ALL=(ALL:ALL) NOPASSWD:ALL\n' > /etc/sudoers.d/90-linux-desktop
chmod 0440 /etc/sudoers.d/90-linux-desktop
visudo -cf /etc/sudoers.d/90-linux-desktop

install -d -m 0700 -o desktop -g desktop /home/desktop/.config/fcitx5
cat > /home/desktop/.config/fcitx5/profile <<'FCITX_PROFILE'
[Groups/0]
Name=デフォルト
Default Layout=jp
DefaultIM=mozc

[Groups/0/Items/0]
Name=keyboard-jp
Layout=

[Groups/0/Items/1]
Name=mozc
Layout=

[GroupOrder]
0=デフォルト
FCITX_PROFILE

cat > /home/desktop/.profile <<'PROFILE'
export LANG=ja_JP.UTF-8
export LANGUAGE=ja_JP:ja
export LC_ALL=ja_JP.UTF-8
export GTK_IM_MODULE=fcitx
export QT_IM_MODULE=fcitx
export XMODIFIERS=@im=fcitx
PROFILE
cp /home/desktop/.profile /home/desktop/.xprofile
printf 'run_im fcitx5\n' > /home/desktop/.xinputrc
install -d -m 0755 /home/desktop/Desktop /home/desktop/.config
printf 'ja_JP\n' > /home/desktop/.config/user-dirs.locale
ln -sfn /mnt/android '/home/desktop/Desktop/Android共有'

chown -R desktop:desktop /home/desktop
step "Debian XFCEの設定が完了しました"
CONTAINER_SETUP

    set_status "$id" installing 82 "Debianの音声クライアントを設定しています…"
    ensure_audio_client "$id"
    set_status "$id" installing 84 "デスクトップ監視機能を設定しています…"
    ensure_desktop_runtime "$id"
    set_status "$id" installing 86 "Google Chromeをインストールしています…"
    ensure_google_chrome "$id"
    if google_chrome_ready "$id"; then
        write_meta "$id" google_chrome 1
    else
        write_meta "$id" google_chrome 0
    fi
    set_status "$id" installing 90 "Node.jsランタイムを準備しています…"
    if ensure_nodejs "$id"; then
        write_meta "$id" nodejs 1
    else
        write_meta "$id" nodejs 0
        printf '警告: Node.jsの自動導入に失敗しました。GUI起動は継続します。\n' >&2
    fi
    set_status "$id" installing 92 "Linuxデスクトップの初回設定を仕上げています…"
    write_meta "$id" installed 1
    write_meta "$id" desktop "xfce"
    write_meta "$id" distribution "debian"
    write_meta "$id" image "$expected_image"
    set_status "$id" ready 100 "Debian XFCEを起動できます"
    printf '[%s] Linux Desktop installation completed\n' "$(date -Iseconds)"
    termux-wake-unlock >/dev/null 2>&1 || true
}

start_run_worker() {
    local id="$1" display_number="${2:-$(read_meta "$1" display "$DEFAULT_DISPLAY_NUMBER")}" session
    validate_id "$id"
    validate_display_number "$display_number"
    session="$(run_session "$id")"
    tmux_alive "$session" && return 0
    rm -f "$(stop_file "$id")"
    tmux new-session -d -s "$session" \
        env LDFA_DISPLAY_NUMBER="$display_number" "$SELF" worker-run "$id" "$display_number"
}

desktop_ready_once() {
    local id="$1" display="${2:-$(read_meta "$1" display "$DEFAULT_DISPLAY_NUMBER")}"
    validate_id "$id"
    validate_display_number "$display"
    tmux_alive "$(run_session "$id")" || return 1
    [[ -S "$PREFIX/tmp/.X11-unix/X${display}" ]] || return 1

    timeout 3s proot-distro login "$id" --shared-tmp --user desktop -- \
        /usr/bin/env DISPLAY=":$display" XAUTHORITY=/dev/null \
        /bin/bash -c '
            visible_client_class() {
                wanted="$1"
                for window in $(
                    /usr/bin/xprop -root _NET_CLIENT_LIST 2>/dev/null |
                        /usr/bin/grep -oE "0x[[:xdigit:]]+" || true
                ); do
                    if /usr/bin/xprop -id "$window" WM_CLASS 2>/dev/null |
                            /usr/bin/grep -Fqi "$wanted" &&
                        LC_ALL=C /usr/bin/xwininfo -id "$window" 2>/dev/null |
                            /usr/bin/grep -Fq "Map State: IsViewable"; then
                        return 0
                    fi
                done
                return 1
            }
            /usr/bin/xset q >/dev/null 2>&1 &&
            /usr/bin/pgrep -x xfsettingsd >/dev/null 2>&1 &&
            /usr/bin/pgrep -x xfwm4 >/dev/null 2>&1 &&
            /usr/bin/pgrep -x xfce4-panel >/dev/null 2>&1 &&
            /usr/bin/pgrep -x xfdesktop >/dev/null 2>&1 &&
            /usr/bin/xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null |
                /bin/grep -q "window id" &&
            visible_client_class xfdesktop &&
            visible_client_class xfce4-panel
        ' >/dev/null 2>&1
}

desktop_process_snapshot() {
    local id="$1"
    timeout 3s proot-distro login "$id" -- /bin/bash -c \
        '/bin/ps -eo comm= 2>/dev/null | /usr/bin/sort -u | /usr/bin/paste -sd, -' \
        2>/dev/null || true
}

recover_desktop_session() {
    local id="$1" trigger="${2:-watchdog}" force_rebuild="${3:-0}"
    local state display attempt started_at snapshot
    validate_id "$id"
    [[ "$force_rebuild" == 0 || "$force_rebuild" == 1 ]] || \
        die "不正なデスクトップ強制復旧指定です。"
    state="$(read_meta "$id" state unknown)"
    display="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
    validate_display_number "$display"

    if [[ "$force_rebuild" == 0 ]] && desktop_ready_once "$id" "$display"; then
        say "desktop_ready=1"
        say "desktop_recovered=0"
        return 0
    fi

    # The in-session supervisor notices a trimmed XFCE component within 0.5s
    # and replaces it without discarding Chrome's profile or the live X server.
    # Android resume and the periodic heartbeat can race that repair: a single
    # strict health miss must not tear down the replacement processes while
    # their windows are still mapping. Give the lightweight repair a bounded
    # two-second grace period before escalating to a whole-session restart.
    if [[ "$force_rebuild" == 0 ]] && tmux_alive "$(run_session "$id")" && \
        [[ -S "$PREFIX/tmp/.X11-unix/X${display}" ]]; then
        for attempt in $(seq 1 8); do
            sleep 0.25
            if desktop_ready_once "$id" "$display"; then
                printf '[%s] component supervisor recovery observed trigger=%s display=:%s\n' \
                    "$(date -Iseconds)" "$trigger" "$display" >> "$(log_file "$id")"
                say "desktop_ready=1"
                say "desktop_recovered=1"
                return 0
            fi
            tmux_alive "$(run_session "$id")" || break
        done
    fi

    [[ "$state" == running || "$state" == starting ]] || \
        die "実行中ではないLinuxデスクトップは自動復旧しません（現在: $state）。"
    [[ -S "$PREFIX/tmp/.X11-unix/X${display}" ]] || \
        die "DISPLAY=:$display が消失したためXFCEだけを復旧できません。"

    snapshot="$(desktop_process_snapshot "$id")"
    printf '[%s] desktop health failed trigger=%s display=:%s processes=%s\n' \
        "$(date -Iseconds)" "$trigger" "$display" "${snapshot:-unavailable}" \
        >> "$(log_file "$id")"
    set_status "$id" starting 100 "消失したXFCEとChromeを自動復旧しています…"

    # A surviving PRoot tracer can outlive xfce4-session and fool the old tmux-only
    # watchdog. Tear down this Linux process group while keeping the verified X11
    # server, then start one clean session against the same DISPLAY.
    stop_one "$id" 1
    sleep 0.25
    write_meta "$id" display "$display"
    write_file "$(active_file)" "$id"
    set_status "$id" starting 100 "XFCEセッションを再生成しています…"
    started_at="$(date +%s)"
    start_run_worker "$id" "$display"

    for attempt in $(seq 1 60); do
        if desktop_ready_once "$id" "$display"; then
            set_status "$id" running 100 "Linuxデスクトップを実行中"
            printf '[%s] desktop recovery succeeded trigger=%s display=:%s elapsed=%ss\n' \
                "$(date -Iseconds)" "$trigger" "$display" "$(( $(date +%s) - started_at ))" \
                >> "$(log_file "$id")"
            say "desktop_ready=1"
            say "desktop_recovered=1"
            return 0
        fi
        tmux_alive "$(run_session "$id")" || break
        sleep 0.25
    done

    snapshot="$(desktop_process_snapshot "$id")"
    printf '[%s] desktop recovery failed trigger=%s display=:%s processes=%s\n' \
        "$(date -Iseconds)" "$trigger" "$display" "${snapshot:-unavailable}" \
        >> "$(log_file "$id")"
    die "XFCEセッションを再生成しましたがDISPLAY=:$displayで起動確認できませんでした。"
}

cmd_start() {
    local id="${1:-}" state display_number
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "環境が見つかりません。"
    state="$(read_meta "$id" state unknown)"
    [[ "$(read_meta "$id" installed 0)" == 1 ]] || \
        die "この環境のインストールは完了していません。"
    [[ "$state" == ready || "$state" == running || "$state" == starting ]] || \
        die "この環境はまだ起動できません（現在: $state）。"

    display_number="$(detect_active_display "$id")"
    write_meta "$id" display "$display_number"
    stop_other_desktops "$id"
    write_file "$(active_file)" "$id"
    set_status "$id" starting 100 "内蔵X11へ接続しています…"
    start_run_worker "$id" "$display_number"
    say "$id"
}

# Fingerprint of every provisioning contract this build enforces. When the guest
# already satisfies all of them, ensure-apps can be skipped entirely on the hot
# start path. Any marker bump changes the fingerprint and forces re-provisioning.
apps_provisioned_fingerprint() {
    printf '%s|%s|%s|%s' \
        "$AUDIO_CLIENT_MARKER" "$DESKTOP_RUNTIME_MARKER" "$CHROME_LAUNCHER_MARKER" \
        "$NODEJS_MARKER"
}

# Verify audio client, desktop runtime, Chrome launcher and Node.js in ONE guest
# login instead of four. Chrome and Node are optional (32-bit guests have
# neither), so their absence does not fail the check; the caller records their
# state separately. Returns 0 only when audio+runtime are ready, printing
# "chrome=1|0" and "node=1|0".
apps_combined_ready() {
    local id="$1"
    timeout 8s proot-distro login "$id" -- /bin/bash -c '
        audio_marker="$1"; runtime_marker="$2"; chrome_marker="$3"; node_marker="$4"
        for package in pulseaudio-utils libasound2-plugins; do
            [ "$(dpkg-query -W -f='"'"'${Status}'"'"' "$package" 2>/dev/null)" = \
                "install ok installed" ] || exit 1
        done
        test -f /etc/pulse/client.conf.d/99-ldfa.conf || exit 1
        grep -Fqx "$audio_marker" /etc/pulse/client.conf.d/99-ldfa.conf || exit 1
        grep -Fq "default-server = unix:/tmp/ldfa-pulse/native" \
            /etc/pulse/client.conf.d/99-ldfa.conf || exit 1
        grep -Fq "enable-shm = no" /etc/pulse/client.conf.d/99-ldfa.conf || exit 1
        test -f /etc/alsa/conf.d/99-ldfa-pulse.conf || exit 1
        grep -Fqx "$audio_marker" /etc/alsa/conf.d/99-ldfa-pulse.conf || exit 1
        test -x /usr/local/bin/ldfa-session || exit 1
        grep -Fqx "$runtime_marker" /usr/local/bin/ldfa-session || exit 1
        test -f /etc/fish/conf.d/00-ldfa.fish || exit 1
        grep -Fqx "$runtime_marker" /etc/fish/conf.d/00-ldfa.fish || exit 1
        if test -x /usr/bin/google-chrome-stable &&
            test -x /usr/local/bin/google-chrome-ldfa &&
            test -f /home/desktop/.local/share/applications/google-chrome.desktop &&
            grep -Fqx "$chrome_marker" /usr/local/bin/google-chrome-ldfa; then
            printf "chrome=1\n"
        else
            printf "chrome=0\n"
        fi
        if test -x /opt/nodejs/bin/node && test -x /usr/local/bin/npm &&
            test -f /opt/nodejs/ldfa-nodejs-version &&
            grep -Fqx "$node_marker" /opt/nodejs/ldfa-nodejs-version; then
            printf "node=1\n"
        else
            printf "node=0\n"
        fi
    ' _ "$AUDIO_CLIENT_MARKER" "$DESKTOP_RUNTIME_MARKER" "$CHROME_LAUNCHER_MARKER" \
        "$NODEJS_MARKER" \
        2>/dev/null
}

cmd_ensure_apps() {
    local id="${1:-}" fingerprint combined chrome_state
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "環境が見つかりません。"
    container_exists "$id" || die "Debian環境が見つかりません。"
    [[ "$(read_meta "$id" installed 0)" == 1 ]] || \
        die "この環境のインストールは完了していません。"

    # Hot path: when metadata records that this exact provisioning fingerprint was
    # already verified, confirm it with a single guest login. Only fall through to
    # the full per-component migration (3-4 PRoot logins plus optional network) on
    # a mismatch. This removes the dominant repeat-start cost on real ARM devices,
    # where each PRoot login is seconds rather than the ~0.1s of x86 emulation.
    fingerprint="$(apps_provisioned_fingerprint)"
    if [[ "$(read_meta "$id" apps_provisioned '')" == "$fingerprint" ]]; then
        if combined="$(apps_combined_ready "$id")"; then
            case "$combined" in
                *chrome=1*) write_meta "$id" google_chrome 1; say "google_chrome=1" ;;
                *)          write_meta "$id" google_chrome 0; say "google_chrome=unsupported" ;;
            esac
            case "$combined" in
                *node=1*) write_meta "$id" nodejs 1; say "nodejs=1" ;;
                *)        write_meta "$id" nodejs 0; say "nodejs=unsupported" ;;
            esac
            say "apps_provisioned=cached"
            return 0
        fi
        # Fingerprint matched but the guest no longer satisfies it (user changed the
        # rootfs, package removed, etc.). Drop the marker and re-provision fully.
        write_meta "$id" apps_provisioned ''
    fi

    local audio_ok=1 nodejs_ok=1
    if ! ensure_audio_client "$id" >> "$(log_file "$id")" 2>&1; then
        tail -n 60 "$(log_file "$id")" >&2 || true
        write_meta "$id" audio_ready 0
        audio_ok=0
        printf '警告: Debianの音声クライアントを更新できませんでした。GUI起動は継続します。\n' \
            >&2
    fi
    if ! ensure_desktop_runtime "$id" >> "$(log_file "$id")" 2>&1; then
        tail -n 60 "$(log_file "$id")" >&2 || true
        die "Debian XFCEの復旧機能を更新できませんでした。ログを確認してください。"
    fi
    if ! ensure_google_chrome "$id" >> "$(log_file "$id")" 2>&1; then
        tail -n 60 "$(log_file "$id")" >&2 || true
        die "Google ChromeをDebianへインストールできませんでした。ネットワーク接続とログを確認してください。"
    fi
    if google_chrome_ready "$id"; then
        write_meta "$id" google_chrome 1
        say "google_chrome=1"
    else
        write_meta "$id" google_chrome 0
        say "google_chrome=unsupported"
    fi
    # Node.js is best-effort like Chrome: a network failure degrades tooling but
    # never blocks the desktop. A failed run leaves nodejs_ok=0 so the fingerprint
    # is not recorded and the next start retries.
    if ! ensure_nodejs "$id" >> "$(log_file "$id")" 2>&1; then
        tail -n 60 "$(log_file "$id")" >&2 || true
        nodejs_ok=0
        printf '警告: Node.jsの自動導入に失敗しました。GUI起動は継続します。\n' >&2
    fi
    if nodejs_ready "$id"; then
        write_meta "$id" nodejs 1
        say "nodejs=1"
    else
        write_meta "$id" nodejs 0
        say "nodejs=unsupported"
    fi

    # Record the provisioning fingerprint so the next start can take the hot path.
    # Only record when the audio client and Node.js actually provisioned (a
    # degraded run must keep retrying on later starts); Chrome already died above
    # on a hard failure, so reaching here means its state is authoritative.
    if [[ "$audio_ok" == 1 && "$nodejs_ok" == 1 ]]; then
        write_meta "$id" apps_provisioned "$fingerprint"
    else
        write_meta "$id" apps_provisioned ''
    fi
}

worker_run() {
    local id="$1" display_number="${2:-${LDFA_DISPLAY_NUMBER:-$(read_meta "$1" display "$DEFAULT_DISPLAY_NUMBER")}}" shared log rc=0 wait_count=0 xset_attempt xset_ready=0 audio_ready=0
    validate_id "$id"
    validate_display_number "$display_number"
    DISPLAY_NUMBER="$display_number"
    X11_SOCKET="$PREFIX/tmp/.X11-unix/X${DISPLAY_NUMBER}"
    write_meta "$id" display "$DISPLAY_NUMBER"
    shared="$(shared_path "$id")"
    log="$(log_file "$id")"
    mkdir -p "$shared"

    exec >>"$log" 2>&1
    cleanup_run_worker() {
        local exit_code=$?
        set +e
        if [[ -f "$(stop_file "$id")" ]]; then
            set_status "$id" ready 100 "Linuxデスクトップを起動できます"
        else
            set_status "$id" starting 100 "監視サービスによる自動復旧を待っています…"
        fi
        if [[ -f "$(active_file)" ]] && [[ "$(cat "$(active_file)" 2>/dev/null)" == "$id" ]]; then
            rm -f "$(active_file)"
        fi
        return "$exit_code"
    }
    trap cleanup_run_worker EXIT

    printf '\n[%s] Linux Desktop worker started: %s display=:%s\n' "$(date -Iseconds)" "$id" "$DISPLAY_NUMBER"
    termux-wake-lock >/dev/null 2>&1 || true
    rm -f "$(stop_file "$id")"
    if ensure_audio_bridge && guest_audio_ready "$id"; then
        audio_ready=1
    fi
    write_meta "$id" audio_ready "$audio_ready"
    if [[ "$audio_ready" != 1 ]]; then
        printf '[%s] Audio bridge is unavailable; continuing the graphical session without sound\n' \
            "$(date -Iseconds)" >&2
    fi

    while [[ ! -S "$X11_SOCKET" ]] && (( wait_count < 40 )); do
        [[ -f "$(stop_file "$id")" ]] && exit 0
        set_status "$id" starting 100 "内蔵X11表示サーバーを待っています…"
        sleep 0.5
        wait_count=$((wait_count + 1))
    done

    [[ -S "$X11_SOCKET" ]] || die "X11 socket is unavailable; refusing to start XFCE"
    for xset_attempt in $(seq 1 20); do
        [[ -f "$(stop_file "$id")" ]] && exit 0
        if proot-distro login "$id" --shared-tmp --user desktop -- \
            /usr/bin/env DISPLAY=":$DISPLAY_NUMBER" XAUTHORITY=/dev/null \
            /usr/bin/xset q >/dev/null 2>&1; then
            xset_ready=1
            break
        fi
        sleep 0.25
    done
    [[ "$xset_ready" == 1 ]] || die "display preflight xset failed; refusing to start XFCE"

    set_status "$id" running 100 "Linuxデスクトップを実行中"
    while [[ ! -f "$(stop_file "$id")" ]]; do
        printf '[%s] launching Linux session\n' "$(date -Iseconds)"
        set +e
        # Clean environment before entering PRoot
        unset LD_PRELOAD
        unset LD_LIBRARY_PATH
        unset PROOT_NO_SECCOMP

        # Use env -u to ensure child processes within PRoot don't inherit Termux preloads.
        # --bind exposes the app-private PulseAudio bridge socket at /tmp/ldfa-pulse
        # independently of --shared-tmp, so desktop audio survives proot's tmp churn.
        proot-distro login "$id" --shared-tmp --bind "$shared:/mnt/android" \
            --bind "$PULSE_GUEST_BIND" --user desktop -- \
            /usr/bin/env -u LD_PRELOAD -u LD_LIBRARY_PATH \
                DISPLAY=":$DISPLAY_NUMBER" GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx \
                XMODIFIERS=@im=fcitx PULSE_SERVER="$PULSE_GUEST_SERVER" \
                LDFA_SCALE="$(read_meta "$id" scale 100)" \
                /usr/local/bin/ldfa-session
        rc=$?
        set -e

        [[ -f "$(stop_file "$id")" ]] && break
        printf '[%s] session exited (%s); restarting in 1 second\n' "$(date -Iseconds)" "$rc"
        set_status "$id" starting 100 "セッションを自動復旧しています…"
        sleep 1
        set_status "$id" running 100 "Linuxデスクトップを実行中"
    done

    set_status "$id" ready 100 "Linuxデスクトップを起動できます"
    termux-wake-unlock >/dev/null 2>&1 || true
    if [[ -f "$(active_file)" ]] && [[ "$(cat "$(active_file)" 2>/dev/null)" == "$id" ]]; then
        rm -f "$(active_file)"
    fi
}

cmd_stop() {
    local id="${1:-}" preserve_chrome_restore="${2:-0}"
    validate_id "$id"
    [[ "$preserve_chrome_restore" == 0 || "$preserve_chrome_restore" == 1 ]] || \
        die "不正なChrome復元指定です。"
    stop_one "$id" "$preserve_chrome_restore"
}

cmd_delete() {
    local id="${1:-}" purge_shared="${2:-0}"
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "環境が見つかりません。"

    stop_one "$id"
    if tmux_alive "$(install_session "$id")"; then
        tmux kill-session -t "$(install_session "$id")" >/dev/null 2>&1 || true
    fi
    if container_exists "$id"; then
        proot-distro remove "$id"
    fi

    rm -rf "$(meta_dir "$id")" "$(log_file "$id")" "$(stop_file "$id")"
    if [[ "$purge_shared" == 1 ]]; then
        rm -rf "$(shared_path "$id")"
    fi
}

cmd_set_scale() {
    local id="${1:-}" percent="${2:-100}" display
    validate_id "$id"
    case "$percent" in
        100|125|150|175|200|225|250) : ;;
        *) die "無効な表示スケールです（100/125/150/175/200のいずれか）: $percent" ;;
    esac
    write_meta "$id" scale "$percent"

    # If a desktop session is live, apply the size-based keys immediately so the
    # panel/icons/fonts/cursor update without a restart. The env-derived scales
    # (GDK/QT, GDK_SCALE) only affect newly launched apps; a stop/start reapplies
    # everything from the stored meta.
    if ! tmux_alive "$(run_session "$id")"; then
        say "scale=$percent"
        return 0
    fi
    display="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
    local dpi=$(( percent * 96 / 100 )) cur=$(( percent * 24 / 100 ))
    local pan=$(( percent * 28 / 100 )) ico=$(( percent * 48 / 100 )) gsf=1
    [[ "$percent" == 200 ]] && gsf=2
    unset PROOT_NO_SECCOMP
    LDFA_APPLY_DPI="$dpi" LDFA_APPLY_CUR="$cur" LDFA_APPLY_PAN="$pan" \
    LDFA_APPLY_ICO="$ico" LDFA_APPLY_GSF="$gsf" \
    proot-distro login "$id" --user desktop -- /usr/bin/env \
        DISPLAY=":$display" \
        LDFA_APPLY_DPI="$dpi" LDFA_APPLY_CUR="$cur" LDFA_APPLY_PAN="$pan" \
        LDFA_APPLY_ICO="$ico" LDFA_APPLY_GSF="$gsf" \
        /bin/bash -c '
            addr_file=/tmp/runtime-desktop/dbus_address
            [[ -s "$addr_file" ]] && export DBUS_SESSION_BUS_ADDRESS="$(cat "$addr_file")"
            xq() { timeout 3 xfconf-query -c "$1" -p "$2" -n -t int -s "$3" 2>/dev/null ||
                   timeout 3 xfconf-query -c "$1" -p "$2" -s "$3" 2>/dev/null || true; }
            # Update the X resource too so newly launched Chrome/Electron/Qt apps
            # pick up the DPI (they ignore XSETTINGS). Already-running apps keep
            # their scale until relaunched; a stop/start reapplies everything.
            printf "Xft.dpi: %s\nXft.hinting: 1\nXft.autohint: 0\n" "$LDFA_APPLY_DPI" |
                timeout 3 xrdb -merge 2>/dev/null || true
            xq xsettings     /Xft/DPI                 "$LDFA_APPLY_DPI"
            xq xsettings     /Gtk/CursorThemeSize     "$LDFA_APPLY_CUR"
            xq xsettings     /Gdk/WindowScalingFactor "$LDFA_APPLY_GSF"
            xq xfce4-panel   /panels/panel-1/size     "$LDFA_APPLY_PAN"
            xq xfce4-desktop /desktop-icons/icon-size "$LDFA_APPLY_ICO"
            # Nudge the panel to re-read its size immediately.
            xfce4-panel --restart >/dev/null 2>&1 || true
        ' >/dev/null 2>&1 || true
    say "scale=$percent"
}

cmd_probe() {
    local id="${1:-}" display attempt
    validate_id "$id"
    display="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
    validate_display_number "$display"
    tmux_alive "$(run_session "$id")" || die "Linuxデスクトップworkerが停止しています。"

    for attempt in $(seq 1 80); do
        if desktop_ready_once "$id" "$display"; then
            say "desktop_ready=1"
            say "display=:$display"
            return 0
        fi
        tmux_alive "$(run_session "$id")" || break
        sleep 0.25
    done
    die "XFCE window managerがDISPLAY=:$displayで起動完了しませんでした。"
}

cmd_resume() {
    local id="${1:-}" state force_rebuild=0
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "環境が見つかりません。"
    container_exists "$id" || die "Debian環境が見つかりません。"
    [[ "$(read_meta "$id" installed 0)" == 1 ]] || \
        die "この環境のインストールは完了していません。"
    state="$(read_meta "$id" state unknown)"
    [[ "$state" == running || "$state" == starting ]] || \
        die "実行中ではないLinuxデスクトップは復帰しません（現在: $state）。"

    # Controller and Debian/Chrome launcher migrations run before the viewer opens.
    # Repeating them on every Activity resume creates avoidable PRoot children next
    # to Chrome, exactly when Android may already be enforcing its child-process cap.
    if ! request_chrome_restore_if_needed "$id"; then
        force_rebuild=1
        printf '[%s] Chrome restore signal missed; rebuilding desktop session\n' \
            "$(date -Iseconds)" >> "$(log_file "$id")"
    fi
    recover_desktop_session "$id" "android-resume" "$force_rebuild"
}

cmd_health() {
    local id="${1:-}"
    validate_id "$id"
    recover_desktop_session "$id" "display-heartbeat"
}

cmd_logs() {
    local id="${1:-}" file lines="${2:-300}"
    validate_id "$id"
    [[ "$lines" =~ ^[0-9]+$ ]] || lines=300
    (( lines > 1000 )) && lines=1000
    file="$(log_file "$id")"
    [[ -f "$file" ]] || return 0
    tail -n "$lines" "$file"
}

cmd_heartbeat() {
    local requested="${1:-}" dir id state busy=0
    shopt -s nullglob
    for dir in "$META_ROOT"/*; do
        [[ -d "$dir" ]] || continue
        id="$(basename "$dir")"
        state="$(read_meta "$id" state unknown)"
        case "$state" in
            queued|installing)
                busy=1
                if ! tmux_alive "$(install_session "$id")"; then
                    set_status "$id" queued "$(read_meta "$id" progress 1)" \
                        "中断されたインストールを再開しています…"
                    start_install_worker "$id"
                fi
                ;;
            starting|running)
                busy=1
                if [[ -z "$requested" || "$requested" == "$id" ]]; then
                    recover_desktop_session "$id" "periodic-heartbeat"
                fi
                ;;
        esac
    done
    shopt -u nullglob
    say "busy=$busy"
}

cmd_repair() {
    local dir id state
    shopt -s nullglob
    for dir in "$META_ROOT"/*; do
        [[ -d "$dir" ]] || continue
        id="$(basename "$dir")"
        state="$(read_meta "$id" state unknown)"
        if [[ "$state" == failed ]] && ! tmux_alive "$(run_session "$id")"; then
            if [[ "$(read_meta "$id" installed 0)" == 1 ]]; then
                set_status "$id" ready 100 "Linuxデスクトップを起動できます"
            else
                set_status "$id" queued "$(read_meta "$id" progress 1)" \
                    "失敗したインストールを再開しています…"
                start_install_worker "$id"
            fi
        fi
    done
    shopt -u nullglob
    cmd_heartbeat ""
}

usage() {
    cat <<USAGE
Usage: ldfa-host <command> [arguments]
Commands: doctor bootstrap list create ensure-apps start resume health stop delete probe set-scale audio-probe logs heartbeat repair
USAGE
}

main() {
    local command="${1:-}" locked=0
    [[ -n "$command" ]] || { usage; exit 2; }
    shift || true
    case "$command" in
        bootstrap|create|ensure-apps|start|resume|health|stop|delete|audio-probe|heartbeat|repair)
            acquire_controller_lock
            locked=1
            trap release_controller_lock EXIT INT TERM
            ;;
    esac
    case "$command" in
        doctor) cmd_doctor "$@" ;;
        bootstrap) cmd_bootstrap "$@" ;;
        list) cmd_list "$@" ;;
        create) cmd_create "$@" ;;
        worker-install) worker_install "$@" ;;
        ensure-apps) cmd_ensure_apps "$@" ;;
        start) cmd_start "$@" ;;
        resume) cmd_resume "$@" ;;
        health) cmd_health "$@" ;;
        worker-run) worker_run "$@" ;;
        stop) cmd_stop "$@" ;;
        delete) cmd_delete "$@" ;;
        probe) cmd_probe "$@" ;;
        set-scale) cmd_set_scale "$@" ;;
        audio-probe) cmd_audio_probe "$@" ;;
        logs) cmd_logs "$@" ;;
        heartbeat) cmd_heartbeat "$@" ;;
        repair) cmd_repair "$@" ;;
        *) usage; die "未知の操作: $command" ;;
    esac
    if [[ "$locked" == 1 ]]; then
        release_controller_lock
        trap - EXIT INT TERM
    fi
}

main "$@"
