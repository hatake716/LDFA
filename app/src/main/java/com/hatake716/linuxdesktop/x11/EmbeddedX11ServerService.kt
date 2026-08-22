package com.hatake716.linuxdesktop.x11

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.system.Os
import androidx.core.app.NotificationCompat
import com.hatake716.linuxdesktop.R
import com.termux.x11.EmbeddedX11ServerBridge
import com.termux.x11.ICmdEntryInterface
import java.io.File

/**
 * Hosts the native Termux:X11 X server in a dedicated Android process.
 *
 * The old shell -> app_process -> loader.apk path is deliberately not used here. The service runs
 * in :x11, so a native Xorg failure does not take down the management UI process. The viewer
 * renderer still lives with the viewer Activity in the main process and is hardened separately.
 *
 * The viewer binds directly to this service and receives ICmdEntryInterface through Binder. There
 * is intentionally no TCP 7892 listener and no ACTION_START broadcast handshake in the normal path.
 * Also do not reference the Java CmdEntryPoint class here: its shell-only static initializer reaches
 * hidden framework APIs. Only native Xorg symbols are reused through EmbeddedX11ServerBridge.
 */
class EmbeddedX11ServerService : Service() {
    private var serverStarted = false
    private var displayNumber = 1
    private var serviceGeneration: String? = null

    private val connectionBinder = object : ICmdEntryInterface.Stub() {
        override fun getXConnection() = EmbeddedX11ServerBridge.getXConnection()
        override fun getLogcatOutput() = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Linux Desktop X11")
            .setContentText("Linuxデスクトップ表示サーバーを実行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder = connectionBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recovery is owned by LinuxDesktopRepository/KeepAlive. Never let Android resurrect a
        // crashed native server with a null or stale Intent and bypass renderer-mode selection.
        if (intent?.action != ACTION_START) {
            appendLog("rejecting service start without explicit ACTION_START")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val requestedGeneration = intent.getStringExtra(EXTRA_GENERATION)
        if (requestedGeneration.isNullOrBlank()) {
            appendLog("rejecting service start without an operation generation")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (serverStarted && serviceGeneration != requestedGeneration) {
            appendLog(
                "rejecting generation replacement current=$serviceGeneration requested=$requestedGeneration",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!serverStarted) {
            displayNumber = intent.getIntExtra(EXTRA_DISPLAY, 1)
            val legacyDrawing = intent.getBooleanExtra(EXTRA_LEGACY_DRAWING, false)
            val debug = intent.getBooleanExtra(EXTRA_DEBUG, false)
            try {
                serviceGeneration = requestedGeneration
                writeServiceState(requestedGeneration)
                prepareEnvironment(displayNumber, debug)
                val args = buildList {
                    add(":$displayNumber")
                    // The readiness probe is intentionally a short-lived X client. Without
                    // -noreset, its disconnect makes Xorg replace the socket/lock just before
                    // openDisplay() validates the service generation, so the viewer never opens.
                    add("-noreset")
                    if (legacyDrawing) add("-legacy-drawing")
                }.toTypedArray()
                appendLog("starting embedded Xorg display=:$displayNumber legacy=$legacyDrawing pid=${Process.myPid()}")
                if (!EmbeddedX11ServerBridge.start(args)) {
                    appendLog("native bridge rejected Xorg startup")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serverStarted = true
                appendLog("X11 binder service ready")
            } catch (throwable: Throwable) {
                appendLog("startup failed: ${throwable.stackTraceToString()}")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanupEndpoint(displayNumber)
        appendLog("x11 service process stopping pid=${Process.myPid()}")
        super.onDestroy()
        // Xorg is a native thread with process-wide globals and upstream exit semantics. Killing only
        // this dedicated :x11 process is deterministic and cannot terminate the management UI.
        Process.killProcess(Process.myPid())
    }

    private fun prepareEnvironment(display: Int, debug: Boolean) {
        val tmp = File(TMP_ROOT)
        val socketDir = File(tmp, ".X11-unix")
        tmp.mkdirs()
        socketDir.mkdirs()
        runCatching { Os.chmod(socketDir.absolutePath, 0x3ff) } // 01777

        cleanupStaleEndpoint(display)

        val xkbRoot = XKB_CANDIDATES.firstOrNull { File(it).isDirectory }
            ?: error("XKB configuration is missing. Run Linux base setup again.")

        // app_process used to inherit these from the Termux shell. A real Android Service does not,
        // so provide the minimum Termux process environment explicitly.
        val androidPath = System.getenv("PATH").orEmpty()
        Os.setenv("HOME", TERMUX_HOME, true)
        Os.setenv("PREFIX", TERMUX_PREFIX, true)
        Os.setenv("PATH", "$TERMUX_PREFIX/bin:$androidPath", true)
        Os.setenv("TMPDIR", TMP_ROOT, true)
        Os.setenv("XDG_RUNTIME_DIR", XDG_RUNTIME, true)
        Os.setenv("XKB_CONFIG_ROOT", xkbRoot, true)
        if (debug) Os.setenv("TERMUX_X11_DEBUG", "1", true)
        else runCatching { Os.unsetenv("TERMUX_X11_DEBUG") }

        File(XDG_RUNTIME).mkdirs()
        runCatching { Os.chmod(XDG_RUNTIME, 0x1c0) } // 0700
        appendLog("environment ready home=$TERMUX_HOME path=$TERMUX_PREFIX/bin tmp=$TMP_ROOT xkb=$xkbRoot")
    }

    private fun cleanupStaleEndpoint(display: Int) {
        val lock = File(TMP_ROOT, ".X$display-lock")
        val socket = File(File(TMP_ROOT, ".X11-unix"), "X$display")
        if (lock.exists()) {
            val pid = lock.readText().trim().toIntOrNull()
            val alive = pid != null && runCatching {
                Os.kill(pid, 0)
                true
            }.getOrDefault(false)
            if (alive && pid != Process.myPid()) {
                error("DISPLAY :$display is already owned by pid=$pid")
            }
            lock.delete()
        }
        if (socket.exists()) socket.delete()
    }

    private fun cleanupEndpoint(display: Int) {
        val lock = File(TMP_ROOT, ".X$display-lock")
        val owner = runCatching { lock.readText().trim().toIntOrNull() }.getOrNull()
        // A delayed old-generation onDestroy must never delete a new Xorg
        // generation's endpoint.
        if (owner == Process.myPid()) {
            runCatching { lock.delete() }
            runCatching { File(File(TMP_ROOT, ".X11-unix"), "X$display").delete() }
        }
        val state = File(filesDir, SERVICE_STATE_FILE)
        val stateOwner = runCatching {
            state.useLines { lines -> lines.firstOrNull()?.trim()?.toIntOrNull() }
        }.getOrNull()
        if (stateOwner == Process.myPid())
            runCatching { state.delete() }
    }

    private fun writeServiceState(generation: String) {
        val state = File(filesDir, SERVICE_STATE_FILE)
        val temporary = File(filesDir, ".$SERVICE_STATE_FILE.${Process.myPid()}")
        temporary.writeText("${Process.myPid()}\n$generation\n")
        if (!temporary.renameTo(state)) {
            temporary.delete()
            error("Could not publish the X11 service process state")
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux Desktop X11", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun appendLog(message: String) {
        runCatching {
            val file = File(LOG_FILE)
            file.parentFile?.mkdirs()
            file.appendText("[${java.time.OffsetDateTime.now()}] service: $message\n")
        }
    }

    companion object {
        const val ACTION_START = "com.hatake716.linuxdesktop.x11.START"
        const val EXTRA_DISPLAY = "display"
        const val EXTRA_LEGACY_DRAWING = "legacy_drawing"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_GENERATION = "generation"
        const val SERVICE_STATE_FILE = "embedded-x11-service.state"

        private const val CHANNEL_ID = "linux_desktop_x11"
        private const val NOTIFICATION_ID = 1716
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val TMP_ROOT = "$TERMUX_PREFIX/tmp"
        private const val XDG_RUNTIME = "$TERMUX_HOME/.local/share/linux-desktop-for-android/run/x11-runtime"
        private const val LOG_FILE = "$TERMUX_HOME/.local/share/linux-desktop-for-android/logs/x11-server.log"
        private val XKB_CANDIDATES = listOf(
            "$TERMUX_PREFIX/share/X11/xkb",
            "$TERMUX_PREFIX/share/xkeyboard-config-2",
        )
    }
}
