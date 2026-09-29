#!/usr/bin/env bash
set -euo pipefail
# `! cmd` never trips `set -e`, so a negated check that fails would pass
# silently. refute turns an unexpected success into a real test failure.
refute() {
    if "$@"; then
        printf 'Unexpected success: %s\n' "$*" >&2
        exit 1
    fi
}
repository="$(cd "$(dirname "$0")/.." && pwd)"
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
export PREFIX="$sandbox/prefix" XDG_DATA_HOME="$sandbox/share"
sed '/^main "\$@"$/d' "$repository/app/src/main/assets/ldfa-host.sh" > "$sandbox/host.sh"
(
    source "$sandbox/host.sh"
    rootfs="$XDG_DATA_HOME/proot-distro/containers/test/rootfs"
    mkdir -p "$rootfs"/{etc/{pulse/client.conf.d,alsa/conf.d,fish/conf.d},usr/local/bin,var/lib/{dpkg,dbus},root}
    printf 'Package: pulseaudio-utils\nStatus: install ok installed\n\nPackage: libasound2-plugins\nStatus: install ok installed\n' > "$rootfs/var/lib/dpkg/status"
    printf '%s\ndefault-server = unix:/tmp/ldfa-pulse/native\nenable-shm = no\n' "$AUDIO_CLIENT_MARKER" > "$rootfs/etc/pulse/client.conf.d/99-ldfa.conf"
    printf '%s\n' "$AUDIO_CLIENT_MARKER" > "$rootfs/etc/alsa/conf.d/99-ldfa-pulse.conf"
    printf '%s\n' "$DESKTOP_RUNTIME_MARKER" > "$rootfs/usr/local/bin/ldfa-session"
    chmod +x "$rootfs/usr/local/bin/ldfa-session"
    printf '%s\n' "$DESKTOP_RUNTIME_MARKER" > "$rootfs/etc/fish/conf.d/00-ldfa.fish"
    apps_required_files_ready test
    # No guest command is needed when the actual package/configuration files match.
    ensure_timezone() { :; }
    apps_combined_ready() { echo guest >> "$sandbox/guest-calls"; return 0; }
    write_meta test installed 1
    write_meta test apps_provisioned "$(apps_provisioned_fingerprint)"
    cmd_prepare_apps test >/dev/null
    [[ ! -e "$sandbox/guest-calls" ]]
    # Package removal and configuration edits must immediately miss the fast path.
    sed -i 's/install ok installed/deinstall ok config-files/g' "$rootfs/var/lib/dpkg/status"
    refute apps_required_files_ready test
    cmd_prepare_apps test >/dev/null
    [[ -s "$sandbox/guest-calls" ]]
    sed -i 's/deinstall ok config-files/install ok installed/g' "$rootfs/var/lib/dpkg/status"
    printf 'old marker\n' > "$rootfs/etc/fish/conf.d/00-ldfa.fish"
    refute apps_required_files_ready test
    # If the guest also rejects the configuration, retain the provisioning route.
    apps_combined_ready() { return 1; }
    cmd_prepare_apps test >/dev/null
    [[ -s "$RUN_ROOT/ldfa-apps-test.session-request" && -s "$rootfs/root/.ldfa-apps.sh" ]]
    # Missing/inconsistent/corrupted D-Bus IDs must still invoke guest repair.
    pd_login() { echo repair >> "$sandbox/machine-id-calls"; }
    mid=0123456789abcdef0123456789abcdef
    printf '%s\n' "$mid" > "$rootfs/etc/machine-id"
    printf '%s\n' "$mid" > "$rootfs/var/lib/dbus/machine-id"
    ensure_machine_id test >/dev/null
    [[ ! -e "$sandbox/machine-id-calls" ]]
    printf 'bad\n' >> "$rootfs/etc/machine-id"
    ensure_machine_id test >/dev/null
    [[ -s "$sandbox/machine-id-calls" ]]
    rm "$sandbox/machine-id-calls" "$rootfs/etc/machine-id"
    ensure_machine_id test >/dev/null
    [[ -s "$sandbox/machine-id-calls" ]]
)
sed '/^main "\$@"$/d' "$repository/app/src/main/assets/ldfa-x11.sh" > "$sandbox/x11.sh"
(
    source "$sandbox/x11.sh"
    service_alive() { return 0; }
    socket_alive() { return 0; }
    proot-distro() { printf '%s\n' "$*" >> "$sandbox/draw-calls"; }
    cmd_draw_probe test >/dev/null
    [[ "$(wc -l < "$sandbox/draw-calls")" == 1 ]]
    grep -q '/usr/bin/xrefresh' "$sandbox/draw-calls"
    : > "$sandbox/draw-calls"
    # A cold-server failure retains the xset retry and retries the actual draw.
    proot-distro() {
        printf '%s\n' "$*" >> "$sandbox/draw-calls"
        [[ "$(wc -l < "$sandbox/draw-calls")" != 1 ]]
    }
    cmd_draw_probe test >/dev/null
    [[ "$(wc -l < "$sandbox/draw-calls")" == 3 ]]
    sed -n '2p' "$sandbox/draw-calls" | grep -q '/usr/bin/xset q'
    sed -n '3p' "$sandbox/draw-calls" | grep -q '/usr/bin/xrefresh'
    # Do not report successful rendering if connectivity or the retry fails.
    proot-distro() { return 1; }
    cmd_probe() { return 1; }
    dump_failure() { :; }
    if (cmd_draw_probe test >/dev/null 2>&1); then exit 1; fi
)
printf 'Startup controller regression checks passed\n'
