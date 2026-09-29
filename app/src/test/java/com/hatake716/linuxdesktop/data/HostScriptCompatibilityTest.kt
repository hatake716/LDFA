package com.hatake716.linuxdesktop.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostScriptCompatibilityTest {
    @Test
    fun bundledHostScriptStaysValidBashAfterNormalization() {
        val bundled = bundledHostScript()
        val normalized = HostScriptCompatibility.normalize(bundled)

        assertBashSyntax("normalized ldfa-host.sh", normalized)
        assertBashSyntax("provision body", heredoc(normalized, "CONTAINER_SETUP"))
        val session = heredoc(normalized, "SESSION")
        assertBashSyntax("ldfa-session", session)
        // The session script defines no `step`; a legacy rewrite injected there fails at runtime
        // even though the syntax stays valid.
        assertEquals(heredoc(bundled, "SESSION"), session)
    }

    @Test
    fun restoreCleanupKeepsItsGuestCommandStringAfterNormalization() {
        val bundled = bundledHostScript()
        val normalized = HostScriptCompatibility.normalize(bundled)
        val function = shellFunction(normalized, "cmd_restore_cleanup")
        assertEquals(shellFunction(bundled, "cmd_restore_cleanup"), function)

        // Run only the host-side function with stubs; pd_login records its argv instead of
        // entering a guest, so the cleanup commands themselves are never executed here.
        val sandbox = Files.createTempDirectory("ldfa-restore-cleanup").toFile()
        try {
            val argv = File(sandbox, "argv")
            val stubs = """
                set -Eeuo pipefail
                validate_id() { :; }
                die() { exit 70; }
                meta_dir() { printf '%s' "${'$'}LDFA_TEST_SANDBOX"; }
                container_exists() { :; }
                log_file() { printf '%s/log' "${'$'}LDFA_TEST_SANDBOX"; }
                write_meta() { :; }
                say() { :; }
                pd_login() { shift; [[ "${'$'}1" == -- ]] && shift; printf '%s\0' "${'$'}@" > "${'$'}LDFA_TEST_SANDBOX/argv"; }
            """.trimIndent()
            val result = runBash(
                "$stubs\n$function\ncmd_restore_cleanup restored\n",
                "-s",
                env = mapOf("LDFA_TEST_SANDBOX" to sandbox.path),
            )
            assertEquals(result.output, 0, result.exitCode)

            val arguments = argv.readText().removeSuffix("\u0000").split('\u0000')
            assertEquals("pd_login must receive exactly one -c string: $arguments", 3, arguments.size)
            assertEquals(listOf("/bin/bash", "-c"), arguments.take(2))
            val guestCommand = arguments[2]
            assertBashSyntax("restore-cleanup -c string", guestCommand)
            assertTrue(guestCommand.contains("rm -rf /tmp/* /run/* /var/run/*"))
            assertTrue(guestCommand.contains("rm -f /home/desktop/.config/google-chrome/Singleton*"))
            assertTrue(guestCommand.contains("rm -f /etc/machine-id /var/lib/dbus/machine-id"))
            assertTrue(guestCommand.contains("dbus-uuidgen"))
            assertTrue(guestCommand.contains("/var/lib/dbus/machine-id"))
            assertFalse(Regex("""(?m)^\s*step\s""").containsMatchIn(guestCommand))
        } finally {
            sandbox.deleteRecursively()
        }
    }


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

        assertTrue(normalized.contains("VERSION=\"1.2.0\""))
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
            assertTrue(normalized.contains("VERSION=\"1.2.0\""))
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

    private class BashResult(val exitCode: Int, val output: String)

    private fun bundledHostScript(): String {
        // Gradle runs module unit tests from app/; an IDE may use the repository root.
        val asset = listOf("src/main/assets/ldfa-host.sh", "app/src/main/assets/ldfa-host.sh")
            .map(::File)
            .firstOrNull(File::isFile)
            ?: error("ldfa-host.sh not found from ${File("").absolutePath}")
        return asset.readText()
    }

    /** Body of a quoted heredoc `<<'tag'`, up to the line that is exactly [tag]. */
    private fun heredoc(script: String, tag: String): String {
        val lines = script.lines()
        val start = lines.indexOfFirst { it.trimEnd().endsWith("<<'$tag'") }
        assertTrue("heredoc $tag not found", start >= 0)
        val end = lines.subList(start + 1, lines.size).indexOf(tag)
        assertTrue("heredoc $tag is not terminated", end >= 0)
        return lines.subList(start + 1, start + 1 + end).joinToString("\n", postfix = "\n")
    }

    /** A top-level function from its header to the first closing brace in column 0. */
    private fun shellFunction(script: String, name: String): String {
        val start = script.indexOf("\n$name() {\n")
        assertTrue("function $name not found", start >= 0)
        val end = script.indexOf("\n}\n", start + 1)
        assertTrue("function $name is not terminated", end >= 0)
        return script.substring(start + 1, end + 2)
    }

    private fun assertBashSyntax(label: String, script: String) {
        val result = runBash(script, "-n")
        assertEquals("$label: ${result.output}", 0, result.exitCode)
    }

    private fun runBash(
        script: String,
        vararg arguments: String,
        env: Map<String, String> = emptyMap(),
    ): BashResult {
        val process = try {
            ProcessBuilder(listOf("bash") + arguments)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
        } catch (error: IOException) {
            assumeTrue("bash is unavailable: ${error.message}", false)
            throw error
        }
        process.outputStream.bufferedWriter().use { it.write(script) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue("bash timed out", process.waitFor(30, TimeUnit.SECONDS))
        return BashResult(process.exitValue(), output)
    }
}
