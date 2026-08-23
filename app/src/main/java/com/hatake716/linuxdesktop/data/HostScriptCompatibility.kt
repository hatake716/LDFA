package com.hatake716.linuxdesktop.data

/**
 * Applies runtime compatibility and safety fixes to the bundled Debian/XFCE host controller before
 * it is installed into the embedded Termux environment.
 *
 * X11 server lifecycle itself is owned by Android. This layer keeps old host-controller assets and
 * existing containers compatible with the current runtime contract: XFCE only starts after a real
 * X11 probe, and the worker follows the display number selected by the active backend.
 */
internal object HostScriptCompatibility {
    private const val CURRENT_VERSION = "VERSION=\"0.9.0\""
    private const val LEGACY_LOCALE_COMMAND =
        "update-locale LANG=ja_JP.UTF-8 LANGUAGE=ja_JP:ja"
    private const val LEGACY_MACHINE_ID_COMMAND =
        "dbus-uuidgen --ensure=/etc/machine-id"
    private const val LEGACY_SOCKET_WAIT =
        "while [[ ! -e \"${'$'}X11_SOCKET\" ]] && (( wait_count < 40 )); do"
    private const val STRICT_SOCKET_WAIT =
        "while [[ ! -S \"${'$'}X11_SOCKET\" ]] && (( wait_count < 40 )); do"
    private const val LEGACY_DISPLAY_GLOBALS =
        "DISPLAY_NUMBER=1\nX11_SOCKET=\"${'$'}PREFIX/tmp/.X11-unix/X${'$'}{DISPLAY_NUMBER}\""
    private const val DYNAMIC_DISPLAY_GLOBALS =
        "DEFAULT_DISPLAY_NUMBER=1\nDISPLAY_NUMBER=\"${'$'}{LDFA_DISPLAY_NUMBER:-${'$'}DEFAULT_DISPLAY_NUMBER}\"\nX11_SOCKET=\"${'$'}PREFIX/tmp/.X11-unix/X${'$'}{DISPLAY_NUMBER}\""
    private val runningStatusLines = listOf(
        "    set_status \"${'$'}id\" running 100 \"Linuxデスクトップを実行中\"",
        "    set_status \"${'$'}id\" running 100 \"Debian 12 XFCEを実行中\"",
    )
    private const val STOP_BREAK =
        "[[ -f \"${'$'}(stop_file \"${'$'}id\")\" ]] && break"

    private val containerSetupHeader = """
        set -Eeuo pipefail
        export DEBIAN_FRONTEND=noninteractive
        export LC_ALL=C.UTF-8
    """.trimIndent()

    fun normalize(script: String): String {
        var normalized = script
        for (version in listOf("0.3.1", "0.3.2", "0.3.3", "0.3.4", "0.4.0", "0.5.0")) {
            normalized = normalized.replaceFirst("VERSION=\"$version\"", CURRENT_VERSION)
        }

        normalized = normalized.replace(LEGACY_LOCALE_COMMAND, localeSetup)
        normalized = normalized.replace(LEGACY_MACHINE_ID_COMMAND, machineIdSetup)
        normalized = normalized.replace(LEGACY_SOCKET_WAIT, STRICT_SOCKET_WAIT)
        normalized = normalized.replace(LEGACY_DISPLAY_GLOBALS, DYNAMIC_DISPLAY_GLOBALS)

        if (
            normalized.contains("validate_id() {") &&
            !normalized.contains("validate_display_number()")
        ) {
            normalized = normalized.replaceFirst(
                "meta_dir() {",
                "$displayHelpers\n\nmeta_dir() {",
            )
        }

        if (
            normalized.contains(containerSetupHeader) &&
            !normalized.contains("container setup failed: exit=")
        ) {
            normalized = normalized.replaceFirst(
                containerSetupHeader,
                "$containerSetupHeader\n$containerErrorTrap",
            )
        }

        if (
            normalized.contains("start_run_worker() {") &&
            !normalized.contains("LDFA_DISPLAY_NUMBER=\"${'$'}display_number\"")
        ) {
            normalized = normalized.replaceFirst(legacyStartWorkerFunction, displayAwareStartWorkerFunction)
        }

        if (
            normalized.contains("cmd_start() {") &&
            !normalized.contains("detect_active_display \"${'$'}id\"")
        ) {
            normalized = normalized.replaceFirst(legacyCmdStartHeader, displayAwareCmdStartHeader)
            normalized = normalized.replaceFirst(
                "    stop_other_desktops \"${'$'}id\"",
                "    display_number=\"${'$'}(detect_active_display \"${'$'}id\")\"\n" +
                    "    write_meta \"${'$'}id\" display \"${'$'}display_number\"\n" +
                    "    stop_other_desktops \"${'$'}id\"",
            )
        }

        normalized = upgradeLegacyStartWorkerCalls(normalized)

        if (
            normalized.contains("worker_run() {") &&
            !normalized.contains("worker display=:")
        ) {
            normalized = normalized.replaceFirst(legacyWorkerHeader, displayAwareWorkerHeader)
        }

        normalized = normalized.replace(
            "env -u LD_PRELOAD -u LD_LIBRARY_PATH /usr/local/bin/ldfa-session",
            "env -u LD_PRELOAD -u LD_LIBRARY_PATH DISPLAY=\":${'$'}DISPLAY_NUMBER\" " +
                "GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx " +
                "PULSE_SERVER=unix:/tmp/ldfa-pulse/native " +
                "/usr/local/bin/ldfa-session",
        )

        if (!normalized.contains("display preflight xset failed")) {
            val runningStatusLine = runningStatusLines.firstOrNull(normalized::contains)
            if (runningStatusLine != null) {
                normalized = normalized.replaceFirst(
                    runningStatusLine,
                    "$strictDisplayPreflight\n\n$runningStatusLine",
                )
            }
        }

        if (
            normalized.contains(STOP_BREAK) &&
            !normalized.contains("X11 disappeared after XFCE exit")
        ) {
            normalized = normalized.replaceFirst(
                STOP_BREAK,
                "$STOP_BREAK\n$displayStillAliveCheck",
            )
        }

        return normalized
    }

    /** Replace only an entire legacy call line so running normalize twice cannot append arguments. */
    private fun upgradeLegacyStartWorkerCalls(script: String): String {
        val hadTrailingNewline = script.endsWith('\n')
        val upgraded = script.lines().joinToString("\n") { line ->
            if (line.trim() == "start_run_worker \"${'$'}id\"") {
                val indent = line.takeWhile(Char::isWhitespace)
                indent +
                    "start_run_worker \"${'$'}id\" \"${'$'}(read_meta \"${'$'}id\" display \"${'$'}DEFAULT_DISPLAY_NUMBER\")\""
            } else {
                line
            }
        }
        return if (hadTrailingNewline && !upgraded.endsWith('\n')) "$upgraded\n" else upgraded
    }

    private val displayHelpers = """
        validate_display_number() {
            [[ "${'$'}{1:-}" =~ ^[1-9][0-9]?${'$'} ]] || die "不正なDISPLAY番号です: ${'$'}{1:-empty}"
        }

        detect_active_display() {
            local id="${'$'}1" x1=0 x2=0 remembered
            [[ -S "${'$'}PREFIX/tmp/.X11-unix/X1" ]] && x1=1
            [[ -S "${'$'}PREFIX/tmp/.X11-unix/X2" ]] && x2=1

            if [[ "${'$'}x1" == 1 && "${'$'}x2" == 1 ]]; then
                die "DISPLAY :1 と :2 が同時に使用されています。表示サーバーを安全に切り替えられません。"
            fi
            if [[ "${'$'}x2" == 1 ]]; then
                printf '2'
                return 0
            fi
            if [[ "${'$'}x1" == 1 ]]; then
                printf '1'
                return 0
            fi

            remembered="${'$'}(read_meta "${'$'}id" display "${'$'}DEFAULT_DISPLAY_NUMBER")"
            validate_display_number "${'$'}remembered"
            printf '%s' "${'$'}remembered"
        }
    """.trimIndent()

    private val localeSetup = """
        step "日本語ロケールを検証しています"
        if ! locale -a | grep -qi '^ja_JP\.utf8${'$'}'; then
            localedef -i ja_JP -f UTF-8 ja_JP.UTF-8
        fi
        if ! locale -a | grep -qi '^ja_JP\.utf8${'$'}'; then
            printf '[%s] 日本語ロケールの生成に失敗しました。\n' "${'$'}(date -Iseconds)" >&2
            exit 31
        fi

        step "システムロケール設定を書き込んでいます"
        install -d -m 0755 /etc/default
        cat > /etc/default/locale <<'LOCALE_DEFAULTS'
        LANG=ja_JP.UTF-8
        LANGUAGE=ja_JP:ja
        LOCALE_DEFAULTS
    """.trimIndent()

    private val machineIdSetup = """
        step "DBus machine-idをPRoot互換方式で設定しています"
        machine_id="${'$'}(dbus-uuidgen 2>/dev/null || true)"
        if [[ ! "${'$'}machine_id" =~ ^[0-9a-fA-F]{32}${'$'} ]] && [[ -r /proc/sys/kernel/random/uuid ]]; then
            machine_id="${'$'}(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null || true)"
        fi
        if [[ ! "${'$'}machine_id" =~ ^[0-9a-fA-F]{32}${'$'} ]]; then
            machine_id="${'$'}(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || true)"
        fi
        if [[ ! "${'$'}machine_id" =~ ^[0-9a-fA-F]{32}${'$'} ]]; then
            printf '[%s] DBus machine-idを生成できませんでした。\n' "${'$'}(date -Iseconds)" >&2
            exit 32
        fi
        install -d -m 0755 /var/lib/dbus
        rm -f /etc/machine-id /var/lib/dbus/machine-id
        printf '%s\n' "${'$'}machine_id" > /etc/machine-id
        printf '%s\n' "${'$'}machine_id" > /var/lib/dbus/machine-id
    """.trimIndent()

    private val containerErrorTrap = """
        trap 'rc=${'$'}?; printf "\n[%s] container setup failed: exit=%s line=%s command=%s\n" "${'$'}(date -Iseconds)" "${'$'}rc" "${'$'}LINENO" "${'$'}BASH_COMMAND" >&2; exit "${'$'}rc"' ERR
    """.trimIndent()

    private val legacyStartWorkerFunction = """
        start_run_worker() {
            local id="${'$'}1" session
            validate_id "${'$'}id"
            session="${'$'}(run_session "${'$'}id")"
            tmux_alive "${'$'}session" && return 0
            rm -f "${'$'}(stop_file "${'$'}id")"
            tmux new-session -d -s "${'$'}session" "${'$'}SELF" worker-run "${'$'}id"
        }
    """.trimIndent()

    private val displayAwareStartWorkerFunction = """
        start_run_worker() {
            local id="${'$'}1" display_number="${'$'}{2:-${'$'}(read_meta "${'$'}1" display "${'$'}DEFAULT_DISPLAY_NUMBER")}" session
            validate_id "${'$'}id"
            validate_display_number "${'$'}display_number"
            session="${'$'}(run_session "${'$'}id")"
            tmux_alive "${'$'}session" && return 0
            rm -f "${'$'}(stop_file "${'$'}id")"
            tmux new-session -d -s "${'$'}session" \
                env LDFA_DISPLAY_NUMBER="${'$'}display_number" "${'$'}SELF" worker-run "${'$'}id" "${'$'}display_number"
        }
    """.trimIndent()

    private val legacyCmdStartHeader = """
        cmd_start() {
            local id="${'$'}{1:-}" state
    """.trimIndent()

    private val displayAwareCmdStartHeader = """
        cmd_start() {
            local id="${'$'}{1:-}" state display_number
    """.trimIndent()

    private val legacyWorkerHeader = """
        worker_run() {
            local id="${'$'}1" shared log rc=0 wait_count=0
            validate_id "${'$'}id"
    """.trimIndent()

    private val displayAwareWorkerHeader = """
        worker_run() {
            local id="${'$'}1" display_number="${'$'}{2:-${'$'}{LDFA_DISPLAY_NUMBER:-${'$'}(read_meta "${'$'}1" display "${'$'}DEFAULT_DISPLAY_NUMBER")}}" shared log rc=0 wait_count=0
            validate_id "${'$'}id"
            validate_display_number "${'$'}display_number"
            DISPLAY_NUMBER="${'$'}display_number"
            X11_SOCKET="${'$'}PREFIX/tmp/.X11-unix/X${'$'}{DISPLAY_NUMBER}"
            write_meta "${'$'}id" display "${'$'}DISPLAY_NUMBER"
            printf '[%s] worker display=:%s socket=%s\n' "${'$'}(date -Iseconds)" "${'$'}DISPLAY_NUMBER" "${'$'}X11_SOCKET"
    """.trimIndent()

    private val strictDisplayPreflight = """
        if [[ ! -S "${'$'}X11_SOCKET" ]]; then
            printf '[%s] X11 socket is unavailable; refusing to start XFCE\n' "${'$'}(date -Iseconds)" >&2
            set_status "${'$'}id" starting 100 "X11表示サーバーの復旧を待っています…"
            exit 41
        fi

        local xset_attempt xset_ready=0
        for xset_attempt in ${'$'}(seq 1 20); do
            [[ -f "${'$'}(stop_file "${'$'}id")" ]] && exit 0
            if proot-distro login "${'$'}id" --shared-tmp --user desktop -- \
                /usr/bin/env DISPLAY=":${'$'}DISPLAY_NUMBER" XAUTHORITY=/dev/null \
                /usr/bin/xset q >/dev/null 2>&1; then
                xset_ready=1
                break
            fi
            sleep 0.25
        done
        if [[ "${'$'}xset_ready" != 1 ]]; then
            printf '[%s] display preflight xset failed; refusing to start XFCE\n' "${'$'}(date -Iseconds)" >&2
            set_status "${'$'}id" starting 100 "X11接続の復旧を待っています…"
            exit 42
        fi
    """.trimIndent().prependIndent("    ")

    private val displayStillAliveCheck = """
        if ! proot-distro login "${'$'}id" --shared-tmp --user desktop -- \
            /usr/bin/env DISPLAY=":${'$'}DISPLAY_NUMBER" XAUTHORITY=/dev/null \
            /usr/bin/xset q >/dev/null 2>&1; then
            printf '[%s] X11 disappeared after XFCE exit; leaving worker for display recovery\n' "${'$'}(date -Iseconds)" >&2
            set_status "${'$'}id" starting 100 "X11表示サーバーを復旧しています…"
            break
        fi
    """.trimIndent().prependIndent("        ")
}
