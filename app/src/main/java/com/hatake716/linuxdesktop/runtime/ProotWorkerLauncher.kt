package com.hatake716.linuxdesktop.runtime

import android.content.Context
import java.io.File

/**
 * Launches the desktop worker (`ldfa-host worker-run`) as an INDEPENDENT, long-lived
 * proot process on the Google-Play / targetSdk-35 path.
 *
 * Why this exists: proot kills all its tracees when it exits, and `setsid` cannot
 * escape a proot's process lifetime (verified on device). So the worker cannot be
 * spawned from inside the short-lived `start` host command's proot — it would die
 * the instant `start` returns. Instead the app starts the worker under its OWN
 * proot with `--kill-on-exit` OFF and keeps the returned [Process] alive
 * (DesktopKeepAliveService). tmux is not involved; on the legacy targetSdk-28 path
 * this class is never used (the host script's tmux backend runs the worker).
 */
class ProotWorkerLauncher(
    private val runtime: ProotRuntime,
    private val prefixDir: File,   // $PREFIX = filesDir/usr
    private val homeDir: File,     // $HOME  = filesDir/home
) {
    private val hostScript = File(homeDir, "$INSTALLED_REL/ldfa-host")
    private val termuxLibDir = File(prefixDir, "lib")

    /** True when this launcher can run (native proot present and host installed). */
    val usable: Boolean
        get() = runtime.available && hostScript.canExecute()

    /**
     * Start `ldfa-host worker-run <id> <display>` under a persistent proot and return
     * the running [Process]. Returns null when [usable] is false (caller falls back to
     * the host-script's own worker launch). The caller owns the process lifetime.
     */
    fun start(id: String, displayNumber: Int): Process? {
        if (!usable) return null
        val inner = listOf(
            File(prefixDir, "bin/env").absolutePath,
            "LDFA_DISPLAY_NUMBER=$displayNumber",
            hostScript.absolutePath,
            "worker-run", id, displayNumber.toString(),
        )
        val binds = if (termuxLibDir.isDirectory) {
            listOf("${termuxLibDir.absolutePath}:$TERMUX_LIB_GUEST")
        } else emptyList()
        // Persistent proot: kill-on-exit OFF so the worker lives as long as this
        // process, which the keep-alive service holds.
        val command = runtime.wrap(inner, rootfs = null, binds = binds, killOnExit = false)

        val pb = ProcessBuilder(command).redirectErrorStream(true)
        val env = pb.environment()
        // proot's own env (loader/tmp/lib path + LDFA_NATIVE_PROOT).
        env.putAll(runtime.environment())
        // Termux runtime env the host script and guest expect. Fixed prefix paths.
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = File(prefixDir, "tmp").absolutePath
        env["PATH"] = "${File(prefixDir, "bin").absolutePath}:/system/bin:/system/xbin"
        env["LANG"] = "en_US.UTF-8"
        // worker_run itself writes run/<session>.pid on startup (under native proot),
        // so the host's session_alive/session_kill can track it without the app
        // needing Process.pid() (which is awkward on Android). The app just holds the
        // Process to keep the persistent proot — and therefore the worker — alive.
        return runCatching { pb.start() }.getOrNull()
    }

    companion object {
        private const val INSTALLED_REL =
            ".local/share/linux-desktop-for-android/bin"
        private const val TERMUX_LIB_GUEST = "/data/data/com.termux/files/usr/lib"

        fun from(context: Context): ProotWorkerLauncher {
            val files = context.filesDir
            return ProotWorkerLauncher(
                runtime = ProotRuntime.from(context),
                prefixDir = File(files, "usr"),
                homeDir = File(files, "home"),
            )
        }
    }
}
