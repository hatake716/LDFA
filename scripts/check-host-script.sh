#!/usr/bin/env bash
set -euo pipefail
script="${1:-app/src/main/assets/ldfa-host.sh}"

bash -n "$script"
test_sandbox="$(mktemp -d)"
generated_session="$test_sandbox/ldfa-session"
trap 'rm -rf "$test_sandbox"' EXIT
sed -n '/^    cat <<'"'"'SESSION'"'"'$/,/^SESSION$/p' "$script" | sed '1d;$d' > "$generated_session"
bash -n "$generated_session"
grep -Fqx 'export PULSE_SERVER=unix:/tmp/ldfa-pulse/native' "$generated_session"
audio_client_setup="$test_sandbox/audio-client-setup.sh"
sed -n '/<<'"'"'AUDIO_CLIENT_SETUP'"'"'$/,/^AUDIO_CLIENT_SETUP$/p' "$script" | \
  sed '1d;$d' > "$audio_client_setup"
bash -n "$audio_client_setup"
! grep -Fq 'apt-get clean' "$audio_client_setup"
! grep -Fq 'rm -rf /var/lib/apt/lists' "$audio_client_setup"
nodejs_setup="$test_sandbox/nodejs-setup.sh"
sed -n '/<<'"'"'NODEJS_SETUP'"'"'$/,/^NODEJS_SETUP$/p' "$script" | \
  sed '1d;$d' > "$nodejs_setup"
bash -n "$nodejs_setup"
grep -Fq 'sha256sum -c -' "$nodejs_setup"
grep -Fq 'https://nodejs.org/dist/' "$nodejs_setup"
sed -n '/^cat > \/usr\/local\/bin\/google-chrome-ldfa <<'"'"'CHROME_LAUNCHER'"'"'$/,/^CHROME_LAUNCHER$/p' "$script" | sed '1d;$d' | sh -n

# Reproduce the v1 panel migration against a minimal Bookworm-shaped config.
# The v2 migration must restore only the PulseAudio item. If v1 already ran,
# IDs later re-added by the user and the original backup must remain untouched.
panel_block="$test_sandbox/panel-migration.sh"
{
  printf '%s\n' '#!/usr/bin/env bash' 'set -Eeuo pipefail'
  sed -n '/^PANEL_CONFIG_DIR=/,/^setxkbmap /p' "$generated_session" | sed '$d'
} > "$panel_block"
bash -n "$panel_block"
panel_home="$test_sandbox/panel-home"
panel_state="$test_sandbox/panel-state"
panel_dir="$panel_home/.config/xfce4/xfconf/xfce-perchannel-xml"
panel_config="$panel_dir/xfce4-panel.xml"
mkdir -p "$panel_dir" "$panel_state/ldfa"
cat > "$panel_config" <<'PANEL_XML'
<channel name="xfce4-panel" version="1.0">
  <property name="panels" type="array">
    <property name="panel-1" type="empty">
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="7"/>
        <value type="int" value="9"/>
        <value type="int" value="10"/>
        <value type="int" value="14"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-8" type="string" value="pulseaudio"/>
    <property name="plugin-9" type="string" value="power-manager-plugin"/>
    <property name="plugin-10" type="string" value="notification-plugin"/>
    <property name="plugin-14" type="string" value="actions"/>
  </property>
</channel>
PANEL_XML
printf '%s\n' 'preserve-original-backup' > "$panel_config.ldfa-before-mobile-optimization"
: > "$panel_state/ldfa/panel-mobile-v1"
HOME="$panel_home" XDG_STATE_HOME="$panel_state" bash "$panel_block"
[[ "$(grep -Fc '<value type="int" value="8"/>' "$panel_config")" == 1 ]]
grep -Fq '<value type="int" value="1"/>' "$panel_config"
grep -Fq '<value type="int" value="7"/>' "$panel_config"
grep -Fq '<value type="int" value="9"/>' "$panel_config"
grep -Fq '<value type="int" value="10"/>' "$panel_config"
grep -Fq '<value type="int" value="14"/>' "$panel_config"
grep -Fqx 'preserve-original-backup' "$panel_config.ldfa-before-mobile-optimization"
[[ -f "$panel_state/ldfa/panel-mobile-v2" ]]
HOME="$panel_home" XDG_STATE_HOME="$panel_state" bash "$panel_block"
[[ "$(grep -Fc '<value type="int" value="8"/>' "$panel_config")" == 1 ]]

# A fresh configuration has no v1 marker, so only the three verified default
# non-mobile plugins are removed and a byte-identical backup is created.
fresh_panel_home="$test_sandbox/fresh-panel-home"
fresh_panel_state="$test_sandbox/fresh-panel-state"
fresh_panel_dir="$fresh_panel_home/.config/xfce4/xfconf/xfce-perchannel-xml"
fresh_panel_config="$fresh_panel_dir/xfce4-panel.xml"
mkdir -p "$fresh_panel_dir" "$fresh_panel_state/ldfa"
cp "$panel_config" "$fresh_panel_config"
cp "$fresh_panel_config" "$test_sandbox/fresh-panel-expected.xml"
HOME="$fresh_panel_home" XDG_STATE_HOME="$fresh_panel_state" bash "$panel_block"
[[ "$(grep -Fc '<value type="int" value="8"/>' "$fresh_panel_config")" == 1 ]]
! grep -Fq '<value type="int" value="9"/>' "$fresh_panel_config"
! grep -Fq '<value type="int" value="10"/>' "$fresh_panel_config"
! grep -Fq '<value type="int" value="14"/>' "$fresh_panel_config"
cmp "$test_sandbox/fresh-panel-expected.xml" \
  "$fresh_panel_config.ldfa-before-mobile-optimization"
required=(
  'VERSION="1.0.2"'
  'LINUX_IMAGE="debian:12"'
  'install_help="$(proot-distro install --help 2>&1 || true)"'
  '[[ "$install_help" == *"--name"* ]]'
  'proot-distro install --name "$id" "$image"'
  'proot-distro install "$legacy_distro" --override-alias "$id"'
  'unset PROOT_NO_SECCOMP'
  'proot-distro login "$id" --bind "$shared:/mnt/android" --'
  'storage_linked() {'
  'storage_linked "$HOME/storage/shared" && storage_ok=1'
  'storage_linked "$HOME/storage/shared" || \'
  'Acquire::Retries=3'
  'ensure_google_chrome()'
  'google-chrome-stable_current_${architecture}.deb'
  'amd64|arm64'
  'dpkg-deb --field "$chrome_package" Package'
  'ensure_nodejs()'
  'nodejs_ready()'
  'NODEJS_MARKER="# LDFA_NODEJS_VERSION=5"'
  'NODEJS_VERSION="v22.23.2"'
  'node-${NODEJS_VERSION}-linux-${node_arch}'
  'https://nodejs.org/dist/${NODEJS_VERSION}/${node_basename}.tar.xz'
  'sha256sum -c -'
  'tar -xJf "$node_tarball" -C /opt/nodejs --strip-components=1 \'
  '--no-same-owner --no-same-permissions'
  'ln -sfn "/opt/nodejs/bin/$tool" "/usr/local/bin/$tool"'
  'amd64) node_arch="x64";   node_sha256="$NODEJS_SHA256_x64" ;;'
  'arm64) node_arch="arm64"; node_sha256="$NODEJS_SHA256_arm64" ;;'
  'prefix=/home/desktop/.npm-global'
  'ldfa-nodejs-version'
  "printf 'prefix=/usr/local\\n' > /opt/nodejs/lib/node_modules/npm/npmrc"
  'grep -Fqx "prefix=/usr/local" /opt/nodejs/lib/node_modules/npm/npmrc'
  'cmp -s /home/desktop/.npmrc'
  'for shell_rc in /home/desktop/.profile /home/desktop/.bashrc; do'
  'install -d -m 0755 -o desktop -g desktop /home/desktop/.local/bin'
  'export PATH="$HOME/.local/bin:$PATH"'
  'grep -Fq ".local/bin" /home/desktop/.bashrc'
  'grep -Fq ".local/bin" /home/desktop/.profile'
  'grep -Fq ".npm-global/bin" /home/desktop/.bashrc'
  'test -x /usr/bin/curl'
  'ca-certificates wget xz-utils curl'
  'CHROME_LAUNCHER_MARKER="# LDFA_CHROME_LAUNCHER_VERSION=8"'
  'DESKTOP_RUNTIME_MARKER="# LDFA_SESSION_RUNTIME_VERSION=28"'
  'AUDIO_CLIENT_MARKER="# LDFA_AUDIO_CLIENT_VERSION=3"'
  'NODEJS_MARKER="# LDFA_NODEJS_VERSION=5"'
  'PULSE_BRIDGE_MARKER="# LDFA_PULSE_BRIDGE_VERSION=1"'
  'PULSE_HOST_DIR="$PREFIX/var/run/ldfa-pulse-bridge"'
  'PULSE_RUNTIME_PATH="$PREFIX/var/run/ldfa-pulse-rt"'
  'export PULSE_RUNTIME_PATH'
  'PULSE_HOST_SOCKET="$PULSE_HOST_DIR/native"'
  'PULSE_GUEST_DIR="/tmp/ldfa-pulse"'
  'PULSE_GUEST_SERVER="unix:$PULSE_GUEST_DIR/native"'
  'PULSE_GUEST_BIND="$PULSE_HOST_DIR:$PULSE_GUEST_DIR"'
  '--bind "$PULSE_GUEST_BIND" --user desktop'
  'PULSE_CONFIG_DROP_IN="$PREFIX/etc/pulse/default.pa.d/ldfa-audio.pa"'
  'grep -Fqx "$1" /usr/local/bin/google-chrome-ldfa'
  '/usr/local/bin/google-chrome-ldfa'
  '# LDFA_CHROME_LAUNCHER_VERSION=8'
  '# LDFA_SESSION_RUNTIME_VERSION=28'
  '# LDFA_AUDIO_CLIENT_VERSION=3'
  '# LDFA_PULSE_BRIDGE_VERSION=1'
  'export ELECTRON_DISABLE_SANDBOX=1'
  'grep -Fq "ELECTRON_DISABLE_SANDBOX" "$shell_rc"'
  'install -d -m 0755 /etc/fish/conf.d'
  '/etc/fish/conf.d/00-ldfa.fish'
  'fish_add_path -g -p $HOME/.local/bin $HOME/.npm-global/bin'
  'set -gx ELECTRON_DISABLE_SANDBOX 1'
  'scan_and_fix_electron()'
  'ldfa_electron_pkgdir()'
  'LDFA_ELECTRON_STAMP="# LDFA_ELECTRON_FIX=1"'
  'resources/app.asar'
  'v8_context_snapshot.bin'
  '*--no-sandbox*|*google-chrome*|*/opt/google/chrome/*) continue ;;'
  'print "Exec=" substr(rest, 1, n - 1) " " extra substr(rest, n)'
  'scan_and_fix_electron \'
  'cmd_set_scale() {'
  'set-scale) cmd_set_scale "$@" ;;'
  'LDFA_SCALE="$(read_meta "$id" scale 100)" \'
  'case "$LDFA_SCALE" in 100|125|150|175|200|225|250) : ;; *) LDFA_SCALE=100 ;; esac'
  'ldfa_xfconf_set xsettings     /Xft/DPI                 "$_ldfa_dpi"'
  'export QT_SCALE_FACTOR="$_ldfa_factor"'
  'apply_desktop_scale() {'
  'timeout 3 xrdb -merge 2>/dev/null || true'
  '--force-device-scale-factor=$_ldfa_factor'
  'stamp="$LDFA_ELECTRON_STAMP scale=$LDFA_SCALE"'
  'timeout 3 xfconf-query -c "$1" -p "$2" -n -t int -s "$3" 2>/dev/null ||'
  'MALLOC_ARENA_MAX'
  'CHROME_WRAPPER=/opt/google/chrome/google-chrome'
  '/opt/google/chrome/chrome'
  'WebBrowser=google-chrome'
  '--no-sandbox'
  '--disable-background-mode'
  '--disable-breakpad'
  '--disable-crash-reporter'
  '--disable-extensions'
  '--disable-component-extensions-with-background-pages'
  '--disable-gpu'
  '--no-zygote'
  '--renderer-process-limit=2'
  'xfsettingsd --disable-wm-check --replace'
  'xfce4-panel --disable-wm-check'
  'xfdesktop --disable-wm-check'
  'pid_is_live()'
  'component_pid_running()'
  'wait -n -p exited_component_pid || true'
  'sleep 0.05'
  '[[ "$state" != Z ]]'
  'xfsettingsd.pid'
  'kill -TERM "$settings_pid"'
  'failure_count >= 12'
  'visible_xfce_client()'
  'visible_client_class()'
  'Map State: IsViewable'
  'wm_ready()'
  'restore_chrome_after_wm_ready()'
  'panel-mobile-v2'
  'PANEL_MOBILE_V1_MARKER='
  '<property name="plugin-8" type="string" value="pulseaudio"'
  '9:power-manager-plugin'
  '10:notification-plugin'
  '14:actions'
  'ldfa-before-mobile-optimization'
  'chrome-running'
  '--restore-last-session'
  '--disable-session-crashed-bubble'
  'google-chrome.desktop'
  'ensure-apps'
  'apps_provisioned_fingerprint()'
  'apps_combined_ready()'
  'read_meta "$id" apps_provisioned'
  'write_meta "$id" apps_provisioned "$fingerprint"'
  'apps_provisioned=cached'
  'ensure_audio_bridge()'
  'start_or_recover_pulseaudio()'
  'pulse_process_state()'
  'PulseAudio process state is indeterminate'
  'ensure_audio_client()'
  'guest_audio_ready()'
  'pulse_bridge_module_indexes()'
  'PulseAudio module inventory is unavailable; refusing bridge mutation'
  'could not be unloaded; refusing replacement'
  'module-native-protocol-unix'
  'auth-anonymous=1'
  'env -u PULSE_SERVER timeout 2s pactl load-module module-native-protocol-unix'
  'pactl unload-module "$module_index"'
  'pulseaudio --kill'
  'timeout 1s pkill -TERM -x pulseaudio'
  'timeout 1s pkill -KILL -x pulseaudio'
  'timeout 1s pgrep -x pulseaudio'
  'local deadline="${1:-$((SECONDS + 12))}"'
  'module-aaudio-sink'
  'module-sles-sink'
  'pulseaudio-utils'
  'libasound2-plugins'
  '/etc/pulse/client.conf.d/99-ldfa.conf'
  '/etc/alsa/conf.d/99-ldfa-pulse.conf'
  'cmp -s /home/desktop/.config/pulse/client.conf'
  'cmp -s /home/desktop/.asoundrc'
  'default-server = unix:/tmp/ldfa-pulse/native'
  'enable-shm = no'
  'enable-memfd = no'
  'PULSE_DAEMON_DROP_IN="$PREFIX/etc/pulse/daemon.conf.d/99-ldfa-noshm.conf"'
  'timeout --signal=INT --kill-after=2s 15s /bin/bash -c'
  'Acquire::http::Timeout=5'
  'Acquire::https::Timeout=5'
  '"${APT[@]}" --no-download install'
  'pcm.!default {'
  'type pulse'
  'write_meta "$id" audio_ready "$audio_ready"'
  'continuing the graphical session without sound'
  'cmd_audio_probe()'
  'audio-probe'
  'launch_wm()'
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
  '/usr/bin/pgrep -x xfwm4'
  '/usr/bin/pgrep -x xfsettingsd'
  '/usr/bin/pgrep -x xfce4-panel'
  '/usr/bin/pgrep -x xfdesktop'
  'desktop_ready_once()'
  'component supervisor recovery observed'
  'recover_desktop_session()'
  'request_chrome_restore_if_needed()'
  'cmd_resume()'
  'cmd_health()'
  'stop_one "$id" 1'
  "tmux list-panes -t \"\$session\" -F '#{pane_pid}'"
  'acquire_controller_lock()'
  'bootstrap|create|ensure-apps|start|resume|health|stop|delete|audio-probe|heartbeat|repair'
  'getprop persist.sys.timezone'
  'ln -sfn "/usr/share/zoneinfo/$tz" "$rootfs/etc/localtime"'
  'ln -sfn "/usr/share/zoneinfo/$LDFA_TZ" /etc/localtime'
  'LDFA_TZ="$(host_timezone)"'
  'tzdata \'
  'set-keymap) cmd_set_keymap "$@" ;;'
  'cmd_set_keymap() {'
  'LDFA_KEYBOARD_LAYOUT="${LDFA_KEYBOARD_LAYOUT:-jis}"'
  'setxkbmap -model "$_ldfa_xkb_model" -layout "$_ldfa_xkb_layout"'
  'xkb-data \'
  'cmd_restore_cleanup() {'
  'restore-cleanup) cmd_restore_cleanup "$@" ;;'
  'container_exists "$id" || die "復元されたDebian環境が見つかりません。"'
  'rm -f /home/desktop/.config/google-chrome/Singleton* 2>/dev/null'
  'rm -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null'
  'dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null'
  'write_meta "$id" apps_provisioned ""'
  'say "restore_cleanup=done"'
)
for pattern in "${required[@]}"; do
  grep -Fq -- "$pattern" "$script"
done

! grep -q 'gnome-session' "$script"

! grep -q 'UBUNTU_IMAGE=' "$script"

! grep -Eq 'proot-distro install --help.*\|.*grep -q' "$script"

! grep -q 'export PROOT_NO_SECCOMP=1' "$script"

! grep -Eq 'apt(-get)?[^\n]*install[^\n]*chromium' "$script"

! grep -q -- '--single-process' "$script"

# The old JIS hardcode set only the layout; that leaves symbols shifted. The new
# path always pairs -model with -layout, so a bare "setxkbmap -layout jp" must
# not survive anywhere.
! grep -Fq 'setxkbmap -layout jp' "$script"

# Copying the tzfile breaks ICU zone-ID recovery; /etc/localtime must be a symlink.
! grep -Eq 'cp[^\n]*zoneinfo[^\n]*/etc/localtime' "$script"

# PRoot has no clock-sync mechanism; these would signal a wrong design.
! grep -Eq 'hwclock|timedatectl|ntpdate' "$script"

! grep -q -- '--enable-low-end-device-mode' "$script"

! grep -Fq -- 'wait -n -p exited_component_pid \' "$script"

! grep -Fq 'PULSE_SERVER=127.0.0.1' "$script"

! grep -Fq 'module-native-protocol-tcp' "$script"

! grep -Fq 'listen=0.0.0.0' "$script"

! grep -Fq 'pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true' "$script"

! grep -Fq 'pactl list short modules 2>/dev/null || true' "$script"

! grep -Fq 'pactl list short sinks 2>/dev/null || true' "$script"

! grep -Fq 'value="(8|9|10|14)"' "$script"

! grep -Fq 'cat > /home/desktop/.config/pulse/client.conf' "$script"

! grep -Fq 'cat > /home/desktop/.asoundrc' "$script"

# The storage readiness check must not depend on traversing into the FUSE mount
# (a bare `-d` on the symlink stats /storage/emulated/0 and races the grant).
! grep -Fq '[[ -d "$HOME/storage/shared" ]] && storage_ok=1' "$script"

echo "Debian XFCE host script checks passed"
