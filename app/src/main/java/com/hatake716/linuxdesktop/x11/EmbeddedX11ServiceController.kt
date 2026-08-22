package com.hatake716.linuxdesktop.x11

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import com.termux.x11.EmbeddedX11Display
import kotlinx.coroutines.delay
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID

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

        val generation = UUID.randomUUID().toString()
        val intent = Intent(context, EmbeddedX11ServerService::class.java).apply {
            action = EmbeddedX11ServerService.ACTION_START
            putExtra(EmbeddedX11ServerService.EXTRA_DISPLAY, DISPLAY_NUMBER)
            putExtra(EmbeddedX11ServerService.EXTRA_LEGACY_DRAWING, legacyDrawing)
            putExtra(EmbeddedX11ServerService.EXTRA_DEBUG, false)
            putExtra(EmbeddedX11ServerService.EXTRA_GENERATION, generation)
        }
        ContextCompat.startForegroundService(context, intent)

        var stablePolls = 0
        repeat(START_WAIT_ATTEMPTS) {
            if (isSocketReady() && isExpectedServiceReady(context, generation)) {
                stablePolls++
                if (stablePolls >= START_STABLE_POLLS) {
                    synchronized(displayOpenLock) {
                        displayOpenAllowed = true
                        expectedServiceGeneration = generation
                        displayOpenFailure = null
                    }
                    return
                }
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
        // No old or newly queued bind may launch a viewer after teardown starts.
        cancelPendingDisplayOpen()
        val knownPid = serviceState(context)?.pid ?: lockOwnerPid()
        context.stopService(Intent(context, EmbeddedX11ServerService::class.java))
        if (waitUntilStopped(context, STOP_WAIT_ATTEMPTS, knownPid)) return

        val pid = knownPid ?: serviceState(context)?.pid ?: lockOwnerPid()
        if (pid != null && !isPidAlive(pid)) {
            // A crashed Xorg can leave both .X1-lock and X1 behind. The dead PID is enough evidence
            // to clean only those known endpoint files without sending a signal to any process.
            cleanupEndpointsAfterVerifiedExit(pid)
            cleanupServiceStateAfterVerifiedExit(context, pid)
            if (!isSocketReady() && !isLockOwnerAlive()) return
        }

        if (pid == null || !isOwnedX11Process(pid)) {
            throw IllegalStateException(
                "内蔵X11サービスを安全に停止できませんでした。DISPLAY :$DISPLAY_NUMBER を使用中のプロセスを特定できないため、互換表示へは切り替えません。",
            )
        }

        runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        if (waitUntilStopped(context, FORCE_TERM_WAIT_ATTEMPTS, pid)) return

        if (isPidAlive(pid)) {
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        if (waitUntilStopped(context, FORCE_KILL_WAIT_ATTEMPTS, pid)) return

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

        fun finishBinding() {
            val shouldUnbind = synchronized(displayOpenLock) {
                pendingDisplayBinds.remove(connection) != null
            }
            if (shouldUnbind)
                runCatching { appContext.unbindService(connection) }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    synchronized(displayOpenLock) {
                        val state = serviceState(appContext)
                        val serviceGeneration = state?.generation
                        if (
                            pendingDisplayBinds.containsKey(this) &&
                            displayOpenAllowed &&
                            serviceGeneration != null &&
                            serviceGeneration == expectedServiceGeneration &&
                            service != null &&
                            service.isBinderAlive
                        ) {
                            // Keep the generation check and Activity launch in one critical
                            // section. cancelPendingDisplayOpen() cannot return and close the
                            // viewer until this launch request has completed.
                            try {
                                EmbeddedX11Display.connect(
                                    appContext,
                                    service,
                                    serviceGeneration,
                                )
                            } catch (launchFailure: RuntimeException) {
                                // ServiceConnection callbacks run on the main thread after
                                // openDisplay() has already returned. Never let an Activity launch
                                // failure escape and crash the complete app process; publish it to
                                // the repository so native teardown and VNC fallback can run.
                                displayOpenFailure = launchFailure
                                EmbeddedX11Display.close(appContext)
                                Log.e(
                                    LIFECYCLE_LOG_TAG,
                                    "viewer launch failed generation=$serviceGeneration",
                                    launchFailure,
                                )
                            }
                        }
                    }
                } finally {
                    finishBinding()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finishBinding()
            }

            override fun onBindingDied(name: ComponentName?) {
                finishBinding()
            }

            override fun onNullBinding(name: ComponentName?) {
                finishBinding()
            }
        }

        val bound = synchronized(displayOpenLock) {
            val state = serviceState(appContext)
            val generationMatches = state?.generation == expectedServiceGeneration
            val socketReady = isSocketReady()
            val processOwned = state?.pid?.let(::isOwnedX11Process) == true
            val lockOwner = lockOwnerPid()
            val serviceReady = socketReady && processOwned && lockOwner == state?.pid
            if (
                !displayOpenAllowed ||
                !generationMatches ||
                !serviceReady
            ) {
                Log.w(
                    LIFECYCLE_LOG_TAG,
                    "viewer bind rejected allowed=$displayOpenAllowed " +
                        "state=${state != null} generationMatches=$generationMatches " +
                        "socket=$socketReady processOwned=$processOwned lockMatches=${lockOwner == state?.pid}",
                )
                return@synchronized false
            }
            pendingDisplayBinds[connection] = appContext
            val didBind = runCatching {
                appContext.bindService(
                    Intent(appContext, EmbeddedX11ServerService::class.java),
                    connection,
                    0,
                )
            }.getOrDefault(false)
            if (!didBind)
                pendingDisplayBinds.remove(connection)
            didBind
        }

        return bound
    }

    fun hasDisplayOpenFailure(): Boolean = synchronized(displayOpenLock) {
        displayOpenFailure != null
    }

    fun consumeDisplayOpenFailure(): Throwable? = synchronized(displayOpenLock) {
        displayOpenFailure.also { displayOpenFailure = null }
    }

    fun cancelPendingDisplayOpen() {
        val pending = synchronized(displayOpenLock) {
            displayOpenAllowed = false
            expectedServiceGeneration = null
            pendingDisplayBinds.entries
                .map { it.key to it.value }
                .also { pendingDisplayBinds.clear() }
        }
        pending.forEach { (connection, boundContext) ->
            runCatching { boundContext.unbindService(connection) }
        }
    }

    fun isSocketReady(): Boolean = runCatching {
        val stat = Os.stat(X11_SOCKET)
        OsConstants.S_ISSOCK(stat.st_mode)
    }.getOrDefault(false)

    fun isServiceReady(context: Context): Boolean {
        val state = serviceState(context) ?: return false
        return isSocketReady() && isOwnedX11Process(state.pid) && lockOwnerPid() == state.pid
    }

    private fun isExpectedServiceReady(context: Context, generation: String): Boolean {
        val state = serviceState(context) ?: return false
        return state.generation == generation && isServiceReady(context)
    }

    private suspend fun waitUntilStopped(
        context: Context,
        attempts: Int,
        verifiedPid: Int? = null,
    ): Boolean {
        repeat(attempts) {
            if (verifiedPid != null && !isPidAlive(verifiedPid)) {
                cleanupEndpointsAfterVerifiedExit(verifiedPid)
                cleanupServiceStateAfterVerifiedExit(context, verifiedPid)
            }
            val servicePid = verifiedPid ?: serviceState(context)?.pid
            // Once a concrete old PID is captured, its actual death—not a
            // transient /proc/cmdline read failure—is the restart barrier.
            val serviceProcessAlive = if (verifiedPid != null) {
                isPidAlive(verifiedPid)
            } else {
                servicePid?.let(::isOwnedX11Process) == true
            }
            if (!serviceProcessAlive && !isSocketReady() && !isLockOwnerAlive()) return true
            delay(STOP_WAIT_POLL_MILLIS)
        }
        return false
    }

    private fun serviceState(context: Context): ServiceState? = runCatching {
        val lines = File(context.filesDir, EmbeddedX11ServerService.SERVICE_STATE_FILE).readLines()
        val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return@runCatching null
        val generation = lines.getOrNull(1)?.trim().orEmpty()
        if (generation.isBlank()) null else ServiceState(pid, generation)
    }.getOrNull()

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

    private fun cleanupServiceStateAfterVerifiedExit(context: Context, pid: Int) {
        if (isPidAlive(pid)) return
        val file = File(context.filesDir, EmbeddedX11ServerService.SERVICE_STATE_FILE)
        if (serviceState(context)?.pid == pid) runCatching { file.delete() }
    }

    private data class ServiceState(val pid: Int, val generation: String)

    private var displayOpenFailure: Throwable? = null

    private const val DISPLAY_NUMBER = 1
    private const val X11_PROCESS_NAME = "com.termux:x11"
    private const val X11_SOCKET = "/data/data/com.termux/files/usr/tmp/.X11-unix/X1"
    private const val X11_LOCK = "/data/data/com.termux/files/usr/tmp/.X1-lock"
    private const val LIFECYCLE_LOG_TAG = "LDFA-Lifecycle"
    private const val START_WAIT_POLL_MILLIS = 250L
    private const val START_WAIT_ATTEMPTS = 120
    private const val START_STABLE_POLLS = 4
    private const val STOP_WAIT_POLL_MILLIS = 100L
    private const val STOP_WAIT_ATTEMPTS = 80
    private const val FORCE_TERM_WAIT_ATTEMPTS = 30
    private const val FORCE_KILL_WAIT_ATTEMPTS = 20
    private val displayOpenLock = Any()
    private val pendingDisplayBinds = IdentityHashMap<ServiceConnection, Context>()
    private var displayOpenAllowed = false
    private var expectedServiceGeneration: String? = null
}
