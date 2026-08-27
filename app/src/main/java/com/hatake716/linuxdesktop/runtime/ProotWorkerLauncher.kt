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
        return launchInner(inner)
    }

    /**
     * Launch `ldfa-host worker-install <id>` in its OWN persistent proot, exactly like
     * the desktop worker. The Debian install would otherwise be spawned via `setsid`
     * inside the short-lived `create` RUN_COMMAND's proot, which kills it the instant
     * cmd_create returns (setsid cannot escape a proot's process lifetime — verified on
     * device). This runs to completion (the whole install, ~minutes) then exits; the
     * caller holds the returned Process for that duration to keep the proot alive.
     */
    fun startInstall(id: String): Process? {
        if (!usable) return null
        val inner = listOf(
            File(prefixDir, "bin/env").absolutePath,
            hostScript.absolutePath,
            "worker-install", id,
        )
        return launchInner(inner)
    }

    /**
     * Wrap [inner] in a persistent native proot (kill-on-exit OFF), set the Termux
     * runtime env, start it, drain its pre-`exec` output, and return the held Process.
     * Shared by [start] (worker-run) and [startInstall] (worker-install).
     */
    private fun launchInner(inner: List<String>): Process? {
        val binds = if (termuxLibDir.isDirectory) {
            listOf("${termuxLibDir.absolutePath}:$TERMUX_LIB_GUEST")
        } else emptyList()
        // Persistent proot: kill-on-exit OFF so the worker lives as long as this
        // process, which the caller holds.
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
        // The host worker itself writes run/<session>.pid on startup (under native
        // proot), so session_alive/session_kill can track it without the app needing
        // Process.pid(). The app just holds the Process to keep the persistent proot —
        // and therefore the worker — alive.
        val process = runCatching { pb.start() }.getOrNull() ?: return null
        // Drain the (small) pre-`exec` output so the OS pipe never fills. The worker
        // does `exec >>debian.log 2>&1` almost immediately, moving all real logging off
        // this pipe; only proot's startup banner reaches us. We must still read it — an
        // unread pipe that fills would block the worker's early writes. NOTE: do NOT
        // redirect this Process's stdout to a file: that fd stays open on the file and
        // races the worker's own `exec >>` reopen of the same log, which silently
        // swallowed all worker logging and stalled startup.
        drainToNull(process)
        return process
    }

    /**
     * The desktop session's request file, written by worker_run once host prep (audio
     * bridge, X1, xset preflight) is done. Present ⇒ ready to launch the desktop.
     */
    fun sessionRequestFile(id: String): File =
        File(homeDir, "$RUN_REL/${SESSION_PREFIX}$id.session-request")

    /**
     * Launch the XFCE desktop as ITS OWN single native-proot layer (NOT nested inside
     * the worker's outer proot), running ldfa-session in a restart loop. One layer deep
     * is what lets XFCE compose in ~1s instead of stalling for minutes (measured on
     * device). Reads the env the worker published in [sessionRequestFile]. Returns the
     * Process (held by the caller; destroy it to stop the desktop) or null if the
     * request is missing/invalid.
     */
    fun startSession(id: String): Process? {
        if (!runtime.available) return null
        val req = parseRequest(sessionRequestFile(id)) ?: return null
        val rootfs = req["ROOTFS"]?.let(::File) ?: return null
        if (!rootfs.isDirectory) return null
        val changeId = req["CHANGE_ID"] ?: "0:0"
        val display = req["DISPLAY_NUMBER"] ?: "1"
        val shared = req["SHARED"]
        val pulseBind = req["PULSE_GUEST_BIND"]
        val pulseServer = req["PULSE_GUEST_SERVER"] ?: ""
        val scale = req["LDFA_SCALE"] ?: "100"
        val keymap = req["LDFA_KEYBOARD_LAYOUT"] ?: "jis"
        val tz = req["TZ"]

        val proot = File(nativeLibDir(), LIB_PROOT)
        if (!proot.canExecute()) return null

        // Single-layer proot argv — mirrors pd_login's native branch exactly.
        val argv = ArrayList<String>()
        argv += proot.absolutePath
        argv += listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "--change-id=$changeId", "--rootfs=${rootfs.absolutePath}", "--cwd=/home/desktop",
            "--bind=/dev", "--bind=/proc", "--bind=/sys")
        for (p in SYSTEM_BINDS) if (File(p).exists()) argv += "--bind=$p"
        // --shared-tmp: /tmp contains .X11-unix already (do not double-bind it).
        argv += "--bind=${File(prefixDir, "tmp").absolutePath}:/tmp"
        if (shared != null) argv += "--bind=$shared:/mnt/android"
        if (pulseBind != null) argv += "--bind=$pulseBind"
        // Guest env wrapper (env flags before NAME=VALUE) + the desktop env + a bash
        // restart loop around ldfa-session. `mkdir -p /tmp/runtime-desktop` so dbus and
        // the ready marker have their dir. The app owns this Process; destroying it stops
        // the desktop (proot --kill-on-exit reaps the guest tree).
        argv += listOf(
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "LD_PRELOAD",
            "PATH=$GUEST_PATH",
            "DISPLAY=:$display", "XAUTHORITY=/dev/null",
            "XDG_RUNTIME_DIR=/tmp/runtime-desktop",
            "HOME=/home/desktop", "LANG=ja_JP.UTF-8",
            "GTK_IM_MODULE=fcitx", "QT_IM_MODULE=fcitx", "XMODIFIERS=@im=fcitx",
            "PULSE_SERVER=$pulseServer",
            "LDFA_SCALE=$scale", "LDFA_KEYBOARD_LAYOUT=$keymap",
        )
        if (tz != null) argv += "TZ=$tz"
        argv += listOf("/bin/bash", "-c",
            "mkdir -p /tmp/runtime-desktop 2>/dev/null; " +
                "while true; do /usr/local/bin/ldfa-session; sleep 1; done")

        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        val env = pb.environment()
        env.putAll(runtime.environment())
        env["LD_LIBRARY_PATH"] = nativeLibDir().absolutePath
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = File(prefixDir, "tmp").absolutePath
        env["PATH"] = "${File(prefixDir, "bin").absolutePath}:/system/bin:/system/xbin"
        val process = runCatching { pb.start() }.getOrNull() ?: return null
        drainToNull(process)
        return process
    }

    /**
     * The Debian-install provision request, written by worker_install (native path)
     * after install_container extracts the rootfs. Present ⇒ the guest apt/provision
     * body has been written into the rootfs and is ready to run single-layer.
     */
    fun installRequestFile(id: String): File =
        File(homeDir, "$RUN_REL/${INSTALL_PREFIX}$id.session-request")

    /**
     * Run the Debian guest provision (apt update + xfce install + locale/user setup)
     * as ITS OWN single native-proot layer — the dpkg-heavy phase that ran ~6x slower
     * when nested inside the outer worker proot. Mirrors [startSession] exactly, but:
     * change-id 0:0 (the body does root writes: useradd/locale-gen/sudoers), cwd /root,
     * no DISPLAY/PULSE, and the final command runs the provision script worker_install
     * wrote into the rootfs at /root/.ldfa-provision.sh (which self-reports success via
     * /root/.ldfa-provision.done that the outer worker watches). Returns the Process
     * (held by the caller) or null if the request is missing/invalid.
     */
    fun startInstallProvision(id: String): Process? {
        if (!runtime.available) return null
        val req = parseRequest(installRequestFile(id)) ?: return null
        val rootfs = req["ROOTFS"]?.let(::File) ?: return null
        if (!rootfs.isDirectory) return null
        val changeId = req["CHANGE_ID"] ?: "0:0"
        val shared = req["SHARED"]
        val tz = req["LDFA_TZ"]
        val keymap = req["LDFA_KEYBOARD_LAYOUT"] ?: "jis"

        val proot = File(nativeLibDir(), LIB_PROOT)
        if (!proot.canExecute()) return null

        // Single-layer proot argv — mirrors pd_login's native branch (and startSession).
        val argv = ArrayList<String>()
        argv += proot.absolutePath
        argv += listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "--change-id=$changeId", "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
            "--bind=/dev", "--bind=/proc", "--bind=/sys")
        for (p in SYSTEM_BINDS) if (File(p).exists()) argv += "--bind=$p"
        // Do NOT bind the host $PREFIX/tmp onto the guest /tmp here (startSession does,
        // for the X11 socket). apt/dpkg use /tmp/apt-dpkg-install-* to stage .deb archives
        // during unpack; with the host tmp bound in (and --link2symlink rewriting links),
        // dpkg intermittently can't reopen those staged files ("cannot access archive …:
        // No such file or directory" / "opendir … No such file or directory") and the
        // install aborts. The install provision needs no shared /tmp — let the guest use
        // its own rootfs /tmp.
        if (shared != null) argv += "--bind=$shared:/mnt/android"
        // Guest env wrapper (env flags before NAME=VALUE). Provisioning is root, so
        // HOME=/root; no DISPLAY/XAUTHORITY/PULSE. LDFA_TZ/LDFA_KEYBOARD_LAYOUT are read
        // by the provision body from its env.
        argv += listOf(
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "LD_PRELOAD", "-u", "TMPDIR",
            "PATH=$GUEST_PATH",
            "HOME=/root", "LANG=C.UTF-8",
            "LDFA_KEYBOARD_LAYOUT=$keymap",
        )
        if (tz != null) argv += "LDFA_TZ=$tz"
        argv += listOf("/bin/bash", "/root/.ldfa-provision.sh")

        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        val env = pb.environment()
        env.putAll(runtime.environment())
        env["LD_LIBRARY_PATH"] = nativeLibDir().absolutePath
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = File(prefixDir, "tmp").absolutePath
        env["PATH"] = "${File(prefixDir, "bin").absolutePath}:/system/bin:/system/xbin"
        val process = runCatching { pb.start() }.getOrNull() ?: return null
        drainToNull(process)
        return process
    }

    private fun nativeLibDir(): File =
        // The proot libs live next to the loader ProotRuntime exports.
        File(runtime.environment()["PROOT_LOADER"] ?: "").parentFile
            ?: File(prefixDir.parentFile, "lib")

    private fun parseRequest(file: File): Map<String, String>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }.toMap()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Consume and discard a process's merged output on a daemon thread. */
    private fun drainToNull(process: Process) {
        Thread {
            runCatching {
                process.inputStream.use { stream ->
                    val buf = ByteArray(4096)
                    while (stream.read(buf) >= 0) { /* discard */ }
                }
            }
        }.apply { isDaemon = true; name = "ldfa-worker-drain" }.start()
    }

    companion object {
        private const val INSTALLED_REL =
            ".local/share/linux-desktop-for-android/bin"
        private const val RUN_REL =
            ".local/share/linux-desktop-for-android/run"
        // Matches run_session()/session_request_file() in ldfa-host.sh: "ldfa-run-<id>".
        private const val SESSION_PREFIX = "ldfa-run-"
        // Matches install_session()/session_request_file() in ldfa-host.sh:
        // "ldfa-install-<id>". The native-path Debian install publishes its
        // single-layer provision request under this prefix.
        private const val INSTALL_PREFIX = "ldfa-install-"
        private const val TERMUX_LIB_GUEST = "/data/data/com.hatake716.linuxdesktop/files/usr/lib"
        // The native proot lib (renamed to dodge proot-distro's nested-proot guard).
        private const val LIB_PROOT = "libpdrt.so"
        // Guest PATH the single-layer session needs (login shell is skipped).
        private const val GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // Android system paths the guest linker/runtime reach into (proot-distro's
        // system_bindings). Bound only when present.
        private val SYSTEM_BINDS = listOf(
            "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
            "/linkerconfig/ld.config.txt", "/linkerconfig/com.android.art/ld.config.txt",
            "/plat_property_contexts", "/property_contexts",
        )

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
