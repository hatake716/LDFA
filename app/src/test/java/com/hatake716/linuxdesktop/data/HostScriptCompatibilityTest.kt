package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostScriptCompatibilityTest {
    @Test
    fun normalizesLocaleAndMachineIdWithoutInjectingX11ServerLifecycle() {
        val legacy = """
            VERSION="0.3.1"
            set -Eeuo pipefail
            export DEBIAN_FRONTEND=noninteractive
            export LC_ALL=C.UTF-8
            step "日本語ロケールを設定しています"
            update-locale LANG=ja_JP.UTF-8 LANGUAGE=ja_JP:ja
            dbus-uuidgen --ensure=/etc/machine-id

            cmd_list() {
                :
            }
        """.trimIndent()

        val normalized = HostScriptCompatibility.normalize(legacy)

        assertTrue(normalized.contains("VERSION=\"1.0.0\""))
        assertFalse(normalized.contains("update-locale LANG=ja_JP.UTF-8 LANGUAGE=ja_JP:ja"))
        assertFalse(normalized.contains("dbus-uuidgen --ensure=/etc/machine-id"))
        assertTrue(normalized.contains("/etc/default/locale"))
        assertTrue(normalized.contains("DBus machine-idをPRoot互換方式で設定しています"))
        assertTrue(normalized.contains("container setup failed: exit="))
        assertFalse(normalized.contains("cmd_prepare_x11()"))
        assertEquals(normalized, HostScriptCompatibility.normalize(normalized))
    }

    @Test
    fun upgradesEveryPreviousHostVersion() {
        for (version in listOf("0.3.1", "0.3.2", "0.3.3", "0.3.4", "0.4.0", "0.5.0")) {
            val previous = "VERSION=\"$version\"\ncmd_list() { :; }\n"
            val normalized = HostScriptCompatibility.normalize(previous)
            assertTrue(normalized.contains("VERSION=\"1.0.0\""))
            assertFalse(normalized.contains("VERSION=\"$version\""))
        }
    }

    @Test
    fun refusesToStartOrRestartXfceWithoutLiveX11() {
        val legacy = """
            VERSION="0.3.1"
            worker_run() {
                local id="${'$'}1" rc=0 wait_count=0
                while [[ ! -e "${'$'}X11_SOCKET" ]] && (( wait_count < 40 )); do
                    sleep 0.5
                    wait_count=${'$'}((wait_count + 1))
                done
                if [[ ! -e "${'$'}X11_SOCKET" ]]; then
                    printf '[%s] warning: X11 socket was not visible; attempting session start\n' "${'$'}(date -Iseconds)"
                fi

                set_status "${'$'}id" running 100 "Debian 12 XFCEを実行中"
                while [[ ! -f "${'$'}(stop_file "${'$'}id")" ]]; do
                    proot-distro login "${'$'}id" --shared-tmp --user desktop -- /usr/local/bin/ldfa-session
                    rc=${'$'}?
                    [[ -f "${'$'}(stop_file "${'$'}id")" ]] && break
                    printf '[%s] XFCE exited (%s); restarting in 4 seconds\n' "${'$'}(date -Iseconds)" "${'$'}rc"
                    sleep 4
                done
            }
        """.trimIndent()

        val normalized = HostScriptCompatibility.normalize(legacy)

        assertTrue(normalized.contains("while [[ ! -S \"${'$'}X11_SOCKET\" ]]"))
        assertTrue(normalized.contains("X11 socket is unavailable; refusing to start XFCE"))
        assertTrue(normalized.contains("display preflight xset failed; refusing to start XFCE"))
        assertTrue(normalized.contains("/usr/bin/xset q"))
        assertTrue(normalized.contains("X11 disappeared after XFCE exit; leaving worker for display recovery"))
        assertEquals(normalized, HostScriptCompatibility.normalize(normalized))
    }

    @Test
    fun makesWorkerFollowNativeOrCompatibilityDisplay() {
        val legacy = """
            VERSION="0.3.1"
            DISPLAY_NUMBER=1
            X11_SOCKET="${'$'}PREFIX/tmp/.X11-unix/X${'$'}{DISPLAY_NUMBER}"

            validate_id() { :; }
            meta_dir() { :; }

            start_run_worker() {
                local id="${'$'}1" session
                validate_id "${'$'}id"
                session="${'$'}(run_session "${'$'}id")"
                tmux_alive "${'$'}session" && return 0
                rm -f "${'$'}(stop_file "${'$'}id")"
                tmux new-session -d -s "${'$'}session" "${'$'}SELF" worker-run "${'$'}id"
            }

            cmd_start() {
                local id="${'$'}{1:-}" state
                validate_id "${'$'}id"
                stop_other_desktops "${'$'}id"
                start_run_worker "${'$'}id"
            }

            worker_run() {
                local id="${'$'}1" shared log rc=0 wait_count=0
                validate_id "${'$'}id"
                set_status "${'$'}id" running 100 "Debian 12 XFCEを実行中"
                env -u LD_PRELOAD -u LD_LIBRARY_PATH /usr/local/bin/ldfa-session
            }
        """.trimIndent()

        val normalized = HostScriptCompatibility.normalize(legacy)

        assertTrue(normalized.contains("DEFAULT_DISPLAY_NUMBER=1"))
        assertTrue(normalized.contains("detect_active_display()"))
        assertTrue(normalized.contains(".X11-unix/X2"))
        assertTrue(normalized.contains("display_number=\"${'$'}(detect_active_display \"${'$'}id\")\""))
        assertTrue(normalized.contains("LDFA_DISPLAY_NUMBER=\"${'$'}display_number\""))
        assertTrue(normalized.contains("DISPLAY_NUMBER=\"${'$'}display_number\""))
        assertTrue(normalized.contains("X11_SOCKET=\"${'$'}PREFIX/tmp/.X11-unix/X${'$'}{DISPLAY_NUMBER}\""))
        val workerHeader = normalized.substringAfter("worker_run() {").substringBefore("set_status")
        assertTrue(workerHeader.contains("write_meta \"${'$'}id\" display \"${'$'}DISPLAY_NUMBER\""))
        assertFalse(workerHeader.contains("write_meta \"${'$'}id\" display \"${'$'}DEFAULT_DISPLAY_NUMBER\""))
        assertTrue(normalized.contains("DISPLAY=\":${'$'}DISPLAY_NUMBER\""))
        assertTrue(normalized.contains("GTK_IM_MODULE=fcitx"))
        assertTrue(normalized.contains("PULSE_SERVER=unix:/tmp/ldfa-pulse/native"))
        assertEquals(normalized, HostScriptCompatibility.normalize(normalized))
    }
}
