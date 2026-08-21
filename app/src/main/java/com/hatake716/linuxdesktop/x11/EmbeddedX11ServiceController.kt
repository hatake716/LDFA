package com.hatake716.linuxdesktop.x11

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.termux.x11.EmbeddedX11Display
import kotlinx.coroutines.delay
import java.io.File

/**
 * Android-side lifecycle owner for the embedded X11 service.
 *
 * Xorg startup/stop must not go through a Termux shell command. Keeping this boundary in Android
 * means a coroutine timeout can never leave an `am start-foreground-service` controller process
 * running in the background, and Android owns the service process lifetime directly.
 */
internal object EmbeddedX11ServiceController {
    suspend fun restartAndWait(
        context: Context,
        legacyDrawing: Boolean,
    ) {
        stopAndWait(context)

        // Existing v0.7 installations may never have installed Termux-side XKB data because
        // Android 17 previously skipped native X11 entirely. Prepare it idempotently before Xorg.
        EmbeddedX11PrerequisiteController.ensure(context)

        val intent = Intent(context, EmbeddedX11ServerService::class.java).apply {
            action = EmbeddedX11ServerService.ACTION_START
            putExtra(EmbeddedX11ServerService.EXTRA_DISPLAY, DISPLAY_NUMBER)
            putExtra(EmbeddedX11ServerService.EXTRA_LEGACY_DRAWING, legacyDrawing)
            putExtra(EmbeddedX11ServerService.EXTRA_DEBUG, false)
        }
        ContextCompat.startForegroundService(context, intent)

        var stablePolls = 0
        repeat(START_WAIT_ATTEMPTS) {
            if (isSocketReady()) {
                stablePolls++
                if (stablePolls >= START_STABLE_POLLS) return
            } else {
                stablePolls = 0
            }
            delay(START_WAIT_POLL_MILLIS)
        }

        stopAndWait(context)
        error(
            if (legacyDrawing) {
                "内蔵X11サービスをlegacy描画で起動できませんでした。"
            } else {
                "内蔵X11サービスを通常描画で起動できませんでした。"
            },
        )
    }

    suspend fun stopAndWait(context: Context) {
        context.stopService(Intent(context, EmbeddedX11ServerService::class.java))
        if (waitUntilStopped(STOP_WAIT_ATTEMPTS)) return

        val pid = lockOwnerPid()
        if (pid != null && !isPidAlive(pid)) {
            // A crashed Xorg can leave both .X1-lock and X1 behind. The dead PID is enough evidence
            // to clean only those known endpoint files without sending a signal to any process.
            cleanupEndpointsAfterVerifiedExit(pid)
            if (!isSocketReady() && !isLockOwnerAlive()) return
        }

        if (pid == null || !isOwnedX11Process(pid)) {
            throw IllegalStateException(
                "内蔵X11サービスを安全に停止できませんでした。DISPLAY :$DISPLAY_NUMBER を使用中のプロセスを特定できないため、互換表示へは切り替えません。",
            )
        }

        runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        if (waitUntilStopped(FORCE_TERM_WAIT_ATTEMPTS, pid)) return

        if (isPidAlive(pid)) {
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        if (waitUntilStopped(FORCE_KILL_WAIT_ATTEMPTS, pid)) return

        throw IllegalStateException(
            "内蔵X11プロセス(pid=$pid)を完全停止できませんでした。DISPLAY競合を避けるため互換表示への切り替えを中止します。",
        )
    }

    /**
     * Binds to the current X11 service and injects its binder into the viewer. Existing viewers are
     * reconnected in place; fresh viewers are opened with the binder in their launch Intent.
     */
    fun openDisplay(context: Context): Boolean {
        val appContext = context.applicationContext
        lateinit var connection: ServiceConnection
        var bound = false

        fun unbindSafely() {
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    if (service != null && service.isBinderAlive) {
                        EmbeddedX11Display.connect(appContext, service)
                    }
                } finally {
                    unbindSafely()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onBindingDied(name: ComponentName?) {
                unbindSafely()
            }

            override fun onNullBinding(name: ComponentName?) {
                unbindSafely()
            }
        }

        bound = runCatching {
            appContext.bindService(
                Intent(appContext, EmbeddedX11ServerService::class.java),
                connection,
                0,
            )
        }.getOrDefault(false)

        return bound
    }

    fun isSocketReady(): Boolean = runCatching {
        val stat = Os.stat(X11_SOCKET)
        OsConstants.S_ISSOCK(stat.st_mode)
    }.getOrDefault(false)

    private suspend fun waitUntilStopped(attempts: Int, verifiedPid: Int? = null): Boolean {
        repeat(attempts) {
            if (verifiedPid != null && !isPidAlive(verifiedPid)) {
                cleanupEndpointsAfterVerifiedExit(verifiedPid)
            }
            if (!isSocketReady() && !isLockOwnerAlive()) return true
            delay(STOP_WAIT_POLL_MILLIS)
        }
        return false
    }

    private fun lockOwnerPid(): Int? = runCatching {
        File(X11_LOCK).readText().trim().toInt()
    }.getOrNull()

    private fun isLockOwnerAlive(): Boolean = lockOwnerPid()?.let(::isPidAlive) == true

    private fun isPidAlive(pid: Int): Boolean = runCatching {
        Os.kill(pid, 0)
        true
    }.getOrDefault(false)

    private fun isOwnedX11Process(pid: Int): Boolean = runCatching {
        val cmdline = File("/proc/$pid/cmdline")
            .readBytes()
            .toString(Charsets.UTF_8)
            .substringBefore('\u0000')
        cmdline == X11_PROCESS_NAME
    }.getOrDefault(false)

    private fun cleanupEndpointsAfterVerifiedExit(pid: Int) {
        if (isPidAlive(pid)) return

        val currentOwner = lockOwnerPid()
        if (currentOwner != null && currentOwner != pid && isPidAlive(currentOwner)) {
            return
        }

        runCatching { File(X11_LOCK).delete() }
        runCatching { File(X11_SOCKET).delete() }
    }

    private const val DISPLAY_NUMBER = 1
    private const val X11_PROCESS_NAME = "com.termux:x11"
    private const val X11_SOCKET = "/data/data/com.termux/files/usr/tmp/.X11-unix/X1"
    private const val X11_LOCK = "/data/data/com.termux/files/usr/tmp/.X1-lock"
    private const val START_WAIT_POLL_MILLIS = 250L
    private const val START_WAIT_ATTEMPTS = 120
    private const val START_STABLE_POLLS = 4
    private const val STOP_WAIT_POLL_MILLIS = 100L
    private const val STOP_WAIT_ATTEMPTS = 80
    private const val FORCE_TERM_WAIT_ATTEMPTS = 30
    private const val FORCE_KILL_WAIT_ATTEMPTS = 20
}
