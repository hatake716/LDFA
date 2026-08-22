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

google_chrome_ready() {
    local id="$1"
    proot-distro login "$id" -- /bin/bash -c \
        'test -x /usr/bin/google-chrome-stable &&
         test -x /usr/local/bin/google-chrome-ldfa &&
         test -f /home/desktop/.local/share/applications/google-chrome.desktop' \
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
# Chromium's namespace/setuid sandbox cannot establish its normal privilege
# boundary inside Android PRoot. Run Chrome as the unprivileged desktop user
# with the PRoot-compatible flags required by this environment.
exec /usr/bin/google-chrome-stable \
    --no-sandbox \
    --disable-dev-shm-usage \
    --ozone-platform=x11 \
    --password-store=basic \
    "$@"
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

/usr/bin/google-chrome-stable --version
apt-get clean
rm -rf /var/lib/apt/lists/*
CHROME_SETUP
}

stop_one() {
    local id="$1" session active=""
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || return 0

    session="$(run_session "$id")"
    set_status "$id" stopping 100 "Linuxデスクトップを停止しています…"
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

install -d -m 0755 /usr/local/bin
cat > /usr/local/bin/ldfa-session <<'SESSION'
#!/bin/bash
# Hardened LDFA Session Script
set -Eeuo pipefail

# 1. Clear environment from Android/Termux leakage
unset LD_PRELOAD
unset LD_LIBRARY_PATH
unset SESSION_MANAGER

# 2. Setup robust environment
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
export PULSE_SERVER=127.0.0.1

# 3. Disable SHM to avoid futex/sync errors on older kernels
export _MITSHM=0
export QT_X11_NO_MITSHM=1
export GDK_RENDERING=image
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

# 4. GLib/DBus stability
export G_SLICE=always-malloc
export MALLOC_CHECK_=0
export NO_AT_BRIDGE=1

export XDG_RUNTIME_DIR="/tmp/runtime-desktop"
mkdir -p "$XDG_RUNTIME_DIR" "$HOME/Desktop" "$HOME/.cache" "$HOME/.config"
chmod 700 "$XDG_RUNTIME_DIR"

# 5. Start DBus manually (Hardened for PRoot)
DBUS_PID_FILE="$XDG_RUNTIME_DIR/dbus.pid"
DBUS_SOCK="$XDG_RUNTIME_DIR/bus"
if [[ -f "$DBUS_PID_FILE" ]]; then
    pid=$(cat "$DBUS_PID_FILE")
    kill -0 "$pid" 2>/dev/null || rm -f "$DBUS_PID_FILE" "$DBUS_SOCK"
fi

if [[ ! -f "$DBUS_PID_FILE" ]]; then
    dbus-daemon --session --fork --print-address 5 --print-pid 6 \
        --address="unix:path=$DBUS_SOCK" \
        5> "$XDG_RUNTIME_DIR/dbus_address" 6> "$DBUS_PID_FILE"
    # Give DBus a moment to stabilize on some kernels
    sleep 1
fi
export DBUS_SESSION_BUS_ADDRESS=$(cat "$XDG_RUNTIME_DIR/dbus_address")

# 6. Apply UI tweaks
setxkbmap -layout jp >/dev/null 2>&1 || true
xfconf-query -c xsettings -p /Net/ThemeName -s Adwaita 2>/dev/null || true
xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null || true
xfconf-query -c xfwm4 -p /general/sync_to_vblank -s false 2>/dev/null || true

# 7. Start session components with recovery
fcitx5 -d --replace >/dev/null 2>&1 || true

exec startxfce4 || {
    echo "startxfce4 failed; starting minimal desktop" >&2
    xfwm4 --compositor=off --replace &
    xfce4-panel &
    xfdesktop &
    wait
}
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
install -d -m 0755 /home/desktop/Desktop /home/desktop/.config
printf 'ja_JP\n' > /home/desktop/.config/user-dirs.locale
ln -sfn /mnt/android '/home/desktop/Desktop/Android共有'

chown -R desktop:desktop /home/desktop
step "Debian XFCEの設定が完了しました"
CONTAINER_SETUP

    set_status "$id" installing 86 "Google Chromeをインストールしています…"
    ensure_google_chrome "$id"
    if google_chrome_ready "$id"; then
        write_meta "$id" google_chrome 1
    else
        write_meta "$id" google_chrome 0
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

cmd_ensure_apps() {
    local id="${1:-}"
    validate_id "$id"
    [[ -d "$(meta_dir "$id")" ]] || die "環境が見つかりません。"
    container_exists "$id" || die "Debian環境が見つかりません。"
    [[ "$(read_meta "$id" installed 0)" == 1 ]] || \
        die "この環境のインストールは完了していません。"

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
}

worker_run() {
    local id="$1" display_number="${2:-${LDFA_DISPLAY_NUMBER:-$(read_meta "$1" display "$DEFAULT_DISPLAY_NUMBER")}}" shared log rc=0 wait_count=0 xset_attempt xset_ready=0
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
    pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true

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

        # Use env -u to ensure child processes within PRoot don't inherit Termux preloads
        proot-distro login "$id" --shared-tmp --bind "$shared:/mnt/android" --user desktop -- \
            /usr/bin/env -u LD_PRELOAD -u LD_LIBRARY_PATH \
                DISPLAY=":$DISPLAY_NUMBER" GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx \
                XMODIFIERS=@im=fcitx PULSE_SERVER=127.0.0.1 \
                /usr/local/bin/ldfa-session
        rc=$?
        set -e

        [[ -f "$(stop_file "$id")" ]] && break
        printf '[%s] session exited (%s); restarting in 4 seconds\n' "$(date -Iseconds)" "$rc"
        set_status "$id" starting 100 "セッションを自動復旧しています…"
        sleep 4
        set_status "$id" running 100 "Linuxデスクトップを実行中"
    done

    set_status "$id" ready 100 "Linuxデスクトップを起動できます"
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

cmd_probe() {
    local id="${1:-}" display attempt
    validate_id "$id"
    display="$(read_meta "$id" display "$DEFAULT_DISPLAY_NUMBER")"
    validate_display_number "$display"
    tmux_alive "$(run_session "$id")" || die "Linuxデスクトップworkerが停止しています。"

    for attempt in $(seq 1 80); do
        if proot-distro login "$id" --shared-tmp --user desktop -- \
            /usr/bin/env DISPLAY=":$display" XAUTHORITY=/dev/null \
            /bin/bash -c '/usr/bin/xset q >/dev/null 2>&1 && /usr/bin/pgrep -x xfce4-session >/dev/null 2>&1 && /usr/bin/pgrep -x xfwm4 >/dev/null 2>&1 && /usr/bin/xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | /bin/grep -q "window id"'; then
            say "desktop_ready=1"
            say "display=:$display"
            return 0
        fi
        tmux_alive "$(run_session "$id")" || break
        sleep 0.25
    done
    die "XFCE window managerがDISPLAY=:$displayで起動完了しませんでした。"
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
                    if ! tmux_alive "$(run_session "$id")"; then
                        set_status "$id" starting 100 "中断されたセッションを復旧しています…"
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
Commands: doctor bootstrap list create ensure-apps start stop delete probe logs heartbeat repair
USAGE
}

main() {
    local command="${1:-}" locked=0
    [[ -n "$command" ]] || { usage; exit 2; }
    shift || true
    case "$command" in
        bootstrap|create|ensure-apps|start|stop|delete|heartbeat|repair)
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
        worker-run) worker_run "$@" ;;
        stop) cmd_stop "$@" ;;
        delete) cmd_delete "$@" ;;
        probe) cmd_probe "$@" ;;
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
