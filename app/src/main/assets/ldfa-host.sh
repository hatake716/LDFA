#!/data/data/com.termux/files/usr/bin/bash
# Linux Desktop for Android unified host controller
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail

VERSION="0.3.1"
UBUNTU_IMAGE="ubuntu:24.04"
BASE="${XDG_DATA_HOME:-$HOME/.local/share}/linux-desktop-for-android"
BIN_DIR="$BASE/bin"
META_ROOT="$BASE/containers"
LOG_ROOT="$BASE/logs"
RUN_ROOT="$BASE/run"
SHARED_ROOT="$HOME/storage/shared/LinuxDesktop"
SELF="$BIN_DIR/ldfa-host"
BOOTSTRAP_LOG="$LOG_ROOT/bootstrap.log"
DISPLAY_NUMBER=1
X11_SOCKET="$PREFIX/tmp/.X11-unix/X${DISPLAY_NUMBER}"

mkdir -p "$BIN_DIR" "$META_ROOT" "$LOG_ROOT" "$RUN_ROOT"

say() { printf '%s\n' "$*"; }
die() { printf 'エラー: %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

validate_id() {
    [[ "${1:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "不正なコンテナIDです。"
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

ensure_storage() {
    mkdir -p "$HOME/storage"

    if [[ ! -d "$HOME/storage/shared" ]]; then
        termux-setup-storage >/dev/null 2>&1 || true
    fi

    if [[ ! -e "$HOME/storage/shared" ]] && [[ -d /storage/emulated/0 ]]; then
        ln -s /storage/emulated/0 "$HOME/storage/shared" 2>/dev/null || true
    fi

    [[ -d "$HOME/storage/shared" ]] || \
        die "Android共有ストレージへアクセスできません。アプリのストレージ権限を確認してください。"
    mkdir -p "$SHARED_ROOT"
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

stop_one() {
    local id="$1" session active=""
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || return 0

    session="$(run_session "$id")"
    set_status "$id" stopping 100 "Ubuntu XFCEを停止しています…"
    touch "$(stop_file "$id")"

    if tmux_alive "$session"; then
        tmux kill-session -t "$session" >/dev/null 2>&1 || true
    fi

    if has proot-distro; then
        proot-distro kill "$id" >/dev/null 2>&1 || \
            pkill -f "proot.*${id}" >/dev/null 2>&1 || true
    fi

    [[ -f "$(active_file)" ]] && active="$(cat "$(active_file)" 2>/dev/null || true)"
    if [[ "$active" == "$id" ]]; then
        rm -f "$(active_file)"
    fi

    set_status "$id" ready 100 "Ubuntu XFCEを起動できます"
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
    local tmux_ok=0 proot_ok=0 storage_ok=0 host_ok=0
    has tmux && tmux_ok=1
    has proot-distro && proot_ok=1
    [[ -d "$HOME/storage/shared" ]] && storage_ok=1
    [[ $tmux_ok -eq 1 && $proot_ok -eq 1 && $storage_ok -eq 1 ]] && host_ok=1

    say "version=$VERSION"
    say "host_ready=$host_ok"
    say "tmux=$tmux_ok"
    say "proot_distro=$proot_ok"
    say "embedded_x11=1"
    say "storage=$storage_ok"
    say "shared_directory=$SHARED_ROOT"
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
        display="$(read_meta "$id" display "$DISPLAY_NUMBER")"
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
    local id="${1:-}" name="${2:-Ubuntu XFCE}" dir
    validate_id "$id"
    has tmux || die "Linux基盤が未準備です。先にセットアップを実行してください。"
    has proot-distro || die "proot-distroが未インストールです。"
    ensure_storage

    dir="$(meta_dir "$id")"
    [[ ! -e "$dir" ]] || die "同じIDの環境がすでにあります。"

    mkdir -p "$dir" "$(shared_path "$id")"
    write_meta "$id" name "$name"
    write_meta "$id" desktop "xfce"
    write_meta "$id" distribution "ubuntu"
    write_meta "$id" image "$UBUNTU_IMAGE"
    write_meta "$id" display "$DISPLAY_NUMBER"
    write_meta "$id" created_at "$(date +%s)"
    write_meta "$id" installed 0
    set_status "$id" queued 1 "Ubuntu 24.04 XFCEのインストールを開始します…"
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

install_ubuntu_container() {
    local id="$1" attempt
    for attempt in 1 2 3; do
        printf '[%s] Installing %s as %s (attempt %s/3)\n' \
            "$(date -Iseconds)" "$UBUNTU_IMAGE" "$id" "$attempt"
        if proot-distro install --help 2>&1 | grep -q -- '--name'; then
            if proot-distro install --name "$id" "$UBUNTU_IMAGE"; then
                return 0
            fi
        else
            if proot-distro install ubuntu --override-alias "$id"; then
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
    expected_image="$UBUNTU_IMAGE"
    current_image="$(read_meta "$id" image '')"
    mkdir -p "$shared"

    exec >>"$log" 2>&1
    trap 'worker_failed "$id" "$?" "$LINENO"' ERR
    trap 'worker_failed "$id" 130 "$LINENO"' INT
    trap 'worker_failed "$id" 143 "$LINENO"' TERM

    printf '\n[%s] Ubuntu XFCE installation worker started: %s\n' "$(date -Iseconds)" "$id"
    printf '[%s] target image: %s\n' "$(date -Iseconds)" "$expected_image"
    termux-wake-lock >/dev/null 2>&1 || true

    if container_exists "$id" && [[ "$(read_meta "$id" installed 0)" != 1 ]] && [[ "$current_image" != "$expected_image" ]]; then
        set_status "$id" installing 3 "以前の未完了Ubuntu環境を24.04として作り直しています…"
        printf '[%s] Removing incomplete container created from unpinned image: %s\n' \
            "$(date -Iseconds)" "${current_image:-unknown}"
        proot-distro remove "$id" >/dev/null 2>&1 || true
    fi

    if ! container_exists "$id"; then
        set_status "$id" installing 5 "Ubuntu 24.04 LTSをダウンロードしています…"
        install_ubuntu_container "$id" || die "Ubuntu 24.04 LTSの取得に3回失敗しました。ネットワーク接続を確認してください。"
        write_meta "$id" image "$expected_image"
    fi

    set_status "$id" installing 18 "Ubuntu 24.04のパッケージ一覧を更新しています…"
    proot-distro login "$id" --shared-tmp --bind "$shared:/mnt/android" -- \
        /bin/bash -s <<'CONTAINER_SETUP'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
APT=(apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0)

step() { printf '\n[%s] %s\n' "$(date -Iseconds)" "$*"; }

step "Ubuntuパッケージソースを確認しています"
if [[ -f /etc/apt/sources.list.d/ubuntu.sources ]]; then
    sed -i -E 's/^Components:.*/Components: main restricted universe multiverse/' \
        /etc/apt/sources.list.d/ubuntu.sources
fi
if [[ -f /etc/apt/sources.list ]]; then
    sed -i -E '/^[[:space:]]*deb /{ / universe([[:space:]]|$)/! s/[[:space:]]+main([[:space:]]|$)/ main universe /; }' \
        /etc/apt/sources.list || true
fi

step "中断されたdpkg処理を修復しています"
dpkg --configure -a || true

step "Ubuntuパッケージ一覧を更新しています"
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
    x11-xserver-utils \
    mesa-utils \
    libgl1-mesa-dri

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

printf 'ubuntu\n' > /etc/ldfa-distribution
printf '24.04\n' > /etc/ldfa-distribution-version
printf 'xfce\n' > /etc/ldfa-desktop-environment

install -d -m 0755 /usr/local/bin
cat > /usr/local/bin/ldfa-session <<'SESSION'
#!/bin/bash
set -Eeuo pipefail
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
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export NO_AT_BRIDGE=1
export PULSE_SERVER="${PULSE_SERVER:-127.0.0.1}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-desktop}"

mkdir -p "$XDG_RUNTIME_DIR" "$HOME/Desktop" "$HOME/.cache" "$HOME/.config"
chmod 700 "$XDG_RUNTIME_DIR"
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
setxkbmap -layout jp >/dev/null 2>&1 || true

exec dbus-run-session -- bash -lc '
    fcitx5 -d --replace >"$HOME/.cache/fcitx5.log" 2>&1 || true
    xfconf-query -c xsettings -p /Net/ThemeName -s Adwaita 2>/dev/null || true
    xfconf-query -c keyboard-layout -p /Default/XkbLayout -s jp 2>/dev/null || true
    if startxfce4; then
        exit 0
    fi
    echo "startxfce4 failed; starting fallback XFCE components" >&2
    xfwm4 --replace &
    wm_pid=$!
    xfce4-panel &
    xfdesktop &
    wait "$wm_pid"
'
SESSION
chmod 0755 /usr/local/bin/ldfa-session

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
mkdir -p /home/desktop/Desktop /home/desktop/.config
printf 'ja_JP\n' > /home/desktop/.config/user-dirs.locale
ln -sfn /mnt/android '/home/desktop/Desktop/Android共有'
chown -R desktop:desktop /home/desktop

step "Ubuntu 24.04 XFCEの設定が完了しました"
CONTAINER_SETUP

    set_status "$id" installing 92 "Ubuntu XFCEの初回設定を仕上げています…"
    write_meta "$id" installed 1
    write_meta "$id" desktop "xfce"
    write_meta "$id" distribution "ubuntu"
    write_meta "$id" image "$expected_image"
    set_status "$id" ready 100 "Ubuntu 24.04 XFCEを起動できます"
    printf '[%s] Ubuntu 24.04 XFCE installation completed\n' "$(date -Iseconds)"
    termux-wake-unlock >/dev/null 2>&1 || true
}

start_run_worker() {
    local id="$1" session
    validate_id "$id"
    session="$(run_session "$id")"
    tmux_alive "$session" && return 0
    rm -f "$(stop_file "$id")"
    tmux new-session -d -s "$session" "$SELF" worker-run "$id"
}

cmd_start() {
    local id="${1:-}" state
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "Ubuntu環境が見つかりません。"
    state="$(read_meta "$id" state unknown)"
    [[ "$(read_meta "$id" installed 0)" == 1 ]] || \
        die "このUbuntu環境のインストールは完了していません。"
    [[ "$state" == ready || "$state" == running || "$state" == starting ]] || \
        die "この環境はまだ起動できません（現在: $state）。"

    stop_other_desktops "$id"
    write_file "$(active_file)" "$id"
    set_status "$id" starting 100 "内蔵X11へ接続しています…"
    start_run_worker "$id"
    say "$id"
}

worker_run() {
    local id="$1" shared log rc=0 wait_count=0
    validate_id "$id"
    shared="$(shared_path "$id")"
    log="$(log_file "$id")"
    mkdir -p "$shared"

    exec >>"$log" 2>&1
    cleanup_run_worker() {
        local exit_code=$?
        set +e
        if [[ -f "$(stop_file "$id")" ]]; then
            set_status "$id" ready 100 "Ubuntu XFCEを起動できます"
        else
            set_status "$id" starting 100 "監視サービスによる自動復旧を待っています…"
        fi
        if [[ -f "$(active_file)" ]] && [[ "$(cat "$(active_file)" 2>/dev/null)" == "$id" ]]; then
            rm -f "$(active_file)"
        fi
        return "$exit_code"
    }
    trap cleanup_run_worker EXIT

    printf '\n[%s] Ubuntu XFCE worker started: %s\n' "$(date -Iseconds)" "$id"
    termux-wake-lock >/dev/null 2>&1 || true
    rm -f "$(stop_file "$id")"
    pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true

    while [[ ! -e "$X11_SOCKET" ]] && (( wait_count < 40 )); do
        [[ -f "$(stop_file "$id")" ]] && exit 0
        set_status "$id" starting 100 "内蔵X11表示サーバーを待っています…"
        sleep 0.5
        wait_count=$((wait_count + 1))
    done

    if [[ ! -e "$X11_SOCKET" ]]; then
        printf '[%s] warning: X11 socket was not visible; attempting session start\n' "$(date -Iseconds)"
    fi

    set_status "$id" running 100 "Ubuntu XFCEを実行中"
    while [[ ! -f "$(stop_file "$id")" ]]; do
        printf '[%s] launching Ubuntu XFCE session\n' "$(date -Iseconds)"
        set +e
        proot-distro login "$id" --shared-tmp --bind "$shared:/mnt/android" --user desktop -- \
            env DISPLAY=":$DISPLAY_NUMBER" \
            LANG=ja_JP.UTF-8 \
            LANGUAGE=ja_JP:ja \
            GTK_IM_MODULE=fcitx \
            QT_IM_MODULE=fcitx \
            XMODIFIERS=@im=fcitx \
            PULSE_SERVER=127.0.0.1 \
            /usr/local/bin/ldfa-session
        rc=$?
        set -e

        [[ -f "$(stop_file "$id")" ]] && break
        printf '[%s] XFCE exited (%s); restarting in 4 seconds\n' "$(date -Iseconds)" "$rc"
        set_status "$id" starting 100 "XFCEセッションを自動復旧しています…"
        sleep 4
        set_status "$id" running 100 "Ubuntu XFCEを実行中"
    done

    set_status "$id" ready 100 "Ubuntu XFCEを起動できます"
    termux-wake-unlock >/dev/null 2>&1 || true
    if [[ -f "$(active_file)" ]] && [[ "$(cat "$(active_file)" 2>/dev/null)" == "$id" ]]; then
        rm -f "$(active_file)"
    fi
}

cmd_stop() {
    local id="${1:-}"
    validate_id "$id"
    stop_one "$id"
}

cmd_delete() {
    local id="${1:-}" purge_shared="${2:-0}"
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "Ubuntu環境が見つかりません。"

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
                        "中断されたUbuntuインストールを再開しています…"
                    start_install_worker "$id"
                fi
                ;;
            starting|running)
                busy=1
                if [[ -z "$requested" || "$requested" == "$id" ]]; then
                    if ! tmux_alive "$(run_session "$id")"; then
                        set_status "$id" starting 100 "中断されたXFCEを復旧しています…"
                        write_file "$(active_file)" "$id"
                        start_run_worker "$id"
                    fi
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
                set_status "$id" ready 100 "Ubuntu XFCEを起動できます"
            else
                set_status "$id" queued "$(read_meta "$id" progress 1)" \
                    "失敗したUbuntu 24.04インストールを再開しています…"
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
Commands: doctor bootstrap list create start stop delete logs heartbeat repair
create arguments: <id> <display-name>
USAGE
}

main() {
    local command="${1:-}"
    [[ -n "$command" ]] || { usage; exit 2; }
    shift || true
    case "$command" in
        doctor) cmd_doctor "$@" ;;
        bootstrap) cmd_bootstrap "$@" ;;
        list) cmd_list "$@" ;;
        create) cmd_create "$@" ;;
        worker-install) worker_install "$@" ;;
        start) cmd_start "$@" ;;
        worker-run) worker_run "$@" ;;
        stop) cmd_stop "$@" ;;
        delete) cmd_delete "$@" ;;
        logs) cmd_logs "$@" ;;
        heartbeat) cmd_heartbeat "$@" ;;
        repair) cmd_repair "$@" ;;
        *) usage; die "未知の操作: $command" ;;
    esac
}

main "$@"