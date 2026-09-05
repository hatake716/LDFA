package com.hatake716.linuxdesktop.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.hatake716.linuxdesktop.BuildConfig
import com.hatake716.linuxdesktop.x11.EmbeddedX11PrerequisiteController
import com.hatake716.linuxdesktop.x11.EmbeddedX11ServiceController
import com.termux.app.EmbeddedTermuxRuntime
import com.termux.app.TermuxService
import com.termux.x11.EmbeddedX11Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class RuntimeStatus(
    val terminalReady: Boolean,
    val x11Embedded: Boolean = true,
)

enum class DesktopDisplayBackend(val preferenceValue: String) {
    // COMPATIBILITY_VNC was removed with the VNC fallback; a stored
    // "compatibility-vnc" preference from an old build resolves to null and is
    // treated as "no active session".
    NATIVE_X11("native-x11");

    companion object {
        fun fromPreference(value: String?): DesktopDisplayBackend? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

class LinuxDesktopRepository(private val context: Context) {
    private val commandClient = TermuxCommandClient(context)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    // Google-Play / targetSdk-35 path: launches the desktop worker under its own
    // persistent proot (a worker can't outlive the short-lived `start` proot).
    // No-op when the proot native libs are absent (legacy targetSdk-28 build).
    private val prootWorkerLauncher =
        com.hatake716.linuxdesktop.runtime.ProotWorkerLauncher.from(context)
    private val prootWorkers = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // The desktop session's single-layer proot Process (native path). Held separately
    // from the worker so stop tears down both; destroying it kills XFCE.
    private val prootSessions = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // The Debian-install worker's persistent proot Process (native path). Like the
    // desktop worker, worker-install must run in its own proot — spawning it via setsid
    // inside the short-lived `create` RUN_COMMAND kills it the moment cmd_create returns.
    // Held here for the whole install; the reference IS the proot's lifetime.
    private val prootInstallWorkers = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // The Debian-install provision's single-layer proot Process (native path). The outer
    // install worker publishes a request after extracting the rootfs; the app runs the
    // guest apt/provision body as its OWN single proot layer so the dpkg-heavy xfce unpack
    // isn't nested (proot-in-proot ran it ~6x slower). Held for the provision's duration.
    private val prootInstallSessions = java.util.concurrent.ConcurrentHashMap<String, Process>()
    private val x11LifecycleMutex = GLOBAL_DISPLAY_LIFECYCLE_MUTEX
    private var termuxServiceLease: ServiceConnection? = null
    private val hostScript: String by lazy {
        val bundled = context.assets.open("ldfa-host.sh").bufferedReader().use { it.readText() }
        HostScriptCompatibility.normalize(bundled)
    }
    private val x11Script: String by lazy {
        context.assets.open("ldfa-x11.sh").bufferedReader().use { it.readText() }
    }

    fun hasActiveInstallation(): Boolean = prootInstallWorkers.values.any { it.isAlive } ||
        prootInstallSessions.values.any { it.isAlive }

    fun installationsPaused(): Boolean = preferences.getBoolean("installation_paused", false)

    fun allowInstallationResume() {
        preferences.edit().putBoolean("installation_paused", false).commit()
    }

    suspend fun pauseInstallations() = withContext(Dispatchers.IO) {
        // Persist the user's choice before reaping workers; polling or process recreation
        // must not interpret an explicit stop as an invitation to restart automatically.
        preferences.edit().putBoolean("installation_paused", true).commit()
        val ids = (prootInstallWorkers.keys + prootInstallSessions.keys).distinct().filter { id ->
            prootInstallWorkers.containsKey(id) || File(context.filesDir,
                "home/.local/share/linux-desktop-for-android/containers/$id/installed")
                .takeIf { it.isFile }?.readText()?.trim() != "1"
        }
        for (id in ids) {
            resumedInstalls.add(id)
            val request = prootWorkerLauncher.installRequestFile(id)
            File(request.parentFile, "$id.stop").writeText("user stopped installation")
            teardownNativeProot(id)
            val meta = File(context.filesDir, "home/.local/share/linux-desktop-for-android/containers/$id")
            if (meta.isDirectory) {
                File(meta, "message").writeText("Linuxの準備を停止しました。導入・起動の修復から再開できます。")
                File(meta, "state").writeText("failed")
            }
        }
    }

    fun runtimeStatus(): RuntimeStatus = RuntimeStatus(
        terminalReady = commandClient.isRuntimeInstalled(),
    )

    suspend fun doctor(): DoctorReport = withContext(Dispatchers.IO) {
        DoctorReport.parse(
            commandClient.runBundledHostScript(
                script = hostScript,
                action = "doctor",
                timeout = 20.seconds,
            ),
        )
    }

    suspend fun bootstrapHost(): DoctorReport = withContext(Dispatchers.IO) {
        commandClient.runBundledHostScript(
            script = hostScript,
            action = "bootstrap",
            arguments = listOf(BuildConfig.HOST_SCRIPT_VERSION),
            timeout = 30.minutes,
        )
        if (DisplayBackendPolicy.shouldAttemptNativeFirst(Build.VERSION.SDK_INT)) {
            commandClient.runBundledX11Script(
                script = x11Script,
                action = "prepare",
                timeout = 5.minutes,
            )
        }
        doctor()
    }

    suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        ContainerInfoParser.parse(
            commandClient.runBundledHostScript(
                script = hostScript,
                action = "list",
                timeout = 30.seconds,
            ),
        )
    }

    suspend fun listContainersFast(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        ContainerInfoParser.parse(
            commandClient.runInstalledHost(
                action = "list",
                timeout = 15.seconds,
            ),
        )
    }

    suspend fun createContainer(displayName: String): String = withContext(Dispatchers.IO) {
        val id = createContainerId(displayName)
        commandClient.runBundledHostScript(
            script = hostScript,
            action = "create",
            arguments = listOf(id, displayName.trim()),
            timeout = 45.seconds,
        )
        // On the native path cmd_create only prepares state (meta + queued status + empty
        // log) and returns; it deliberately does NOT setsid the install worker (that would
        // die with the create proot). The app launches worker-install in its own persistent
        // proot instead, exactly like the desktop worker. Legacy path: cmd_create's
        // tmux-backed start_install_worker already ran, so this is a no-op there.
        launchNativeProotInstallWorkerIfNeeded(id)
        // The install worker publishes a provision request once install_container extracts
        // the rootfs. This waits for that request, then launches the guest apt/provision
        // body as its OWN single native-proot layer (the dpkg-heavy xfce unpack, which was
        // ~6x slower nested inside the worker proot). No-op on the legacy path (the worker
        // runs the provision inline via pd_login and never publishes a request).
        launchNativeProotInstallProvisionIfNeeded(id)
        id
    }

    /**
     * Container ids this process already tried to auto-resume, so a worker that dies
     * instantly cannot be relaunched in a loop by the periodic list refresh. The 修復
     * action clears an id to allow another explicit attempt.
     */
    private val resumedInstalls: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Relaunch the install worker + provision for an install whose app process died
     * mid-flight: the native worker/provision proots are children of this process and
     * die with it, and cmd_repair's tmux-based revival cannot help on the native path.
     * The relaunched worker preserves the extracted rootfs and resumes provisioning; its re-entry
     * guard makes a redundant relaunch a no-op. Returns true when a resume launched.
     */
    suspend fun resumeInterruptedInstalls(
        containers: List<ContainerInfo>,
        force: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        if (installationsPaused()) return@withContext 0
        var resumed = 0
        for (container in containers) {
            val id = container.id
            val interrupted = (container.state == ContainerState.QUEUED ||
                container.state == ContainerState.INSTALLING) && !container.sessionAlive
            if (!interrupted) continue
            if (!prootWorkerLauncher.usable) continue
            if (prootInstallWorkers[id]?.isAlive == true) continue
            if (force) resumedInstalls.remove(id)
            if (!resumedInstalls.add(id)) continue
            Log.i(LIFECYCLE_LOG_TAG, "resuming interrupted install id=$id")
            // The dead run's provision request must not race the fresh worker
            // (it belongs to a previous provisioning generation). The worker
            // clears it too; deleting here closes the poll-window race.
            ContainerOperationLocks.withLock(id) {
                // A supervisor can exit while its separate guest PRoot still runs.
                // Finish that generation before repairing dpkg or publishing a new request.
                stopInstallProvision(id)
                prootWorkerLauncher.installRequestFile(id).delete()
                launchNativeProotInstallWorkerIfNeeded(id)
                launchNativeProotInstallProvisionIfNeeded(id)
            }
            resumed++
        }
        resumed
    }

    suspend fun startContainer(id: String, onProgress: (String) -> Unit = {}): DesktopDisplayBackend {
        onProgress("既存のセッションを確認しています")
        val affectedIds = listOfNotNull(id, activeContainerId()).distinct()
        return ContainerOperationLocks.withLocks(affectedIds) {
            withContext(Dispatchers.IO) {
                x11LifecycleMutex.withLock {
                    check(activeContainerId()?.let { it in affectedIds } != false) {
                        "実行中の環境が変わりました。状態を確認してもう一度起動してください。"
                    }
                    holdTermuxServiceLifetime()
                    val previousId = activeContainerId()
                    clearActiveSession()
                    try {
                        if (previousId != null && previousId != id) {
                            try {
                                commandClient.runInstalledHost(action = "stop", arguments = listOf(previousId), timeout = 30.seconds)
                            } finally {
                                teardownNativeProot(previousId)
                            }
                        }
                        // A previous run can still own a stale worker generation. Stop it before
                        // starting a new one.
                        try {
                            commandClient.runInstalledHost(
                                action = "stop",
                                arguments = listOf(id),
                                timeout = 30.seconds,
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Throwable) {
                        }
                        teardownNativeProot(id)
                        closeAllDisplaysAndWait()
                        stopAllDisplayServers()
                        onProgress("Linuxアプリの設定を確認しています")
                        ensureBundledDesktopApps(id)

                        onProgress("X11表示サーバーを起動しています")
                        val backend = selectAndStartDisplayBackend(id)
                        onProgress("DebianとXFCEを起動しています")
                        startAndProbeHost(id)

                        // The VNC fallback was removed (its first-time provisioning took
                        // ~30 minutes through a nested proot — worse than failing): a
                        // desktop that cannot be PRESENTED on native X11 is a start
                        // failure with diagnostics, never a silent degraded mode.
                        onProgress("デスクトップの描画を確認しています")
                        val desktopPresentationFailure = verifyNativeDesktopPresentation(id)
                        if (desktopPresentationFailure != null) {
                            throw desktopPresentationFailure
                        }

                        setActiveSession(id, backend)
                        backend
                    } catch (throwable: Throwable) {
                        onProgress("起動を停止し、後処理を行っています")
                        Log.e(LIFECYCLE_LOG_TAG, "startContainer failed id=$id", throwable)
                        withContext(NonCancellable) {
                            clearActiveSession()
                            runCatching {
                                commandClient.runInstalledHost(
                                    action = "stop",
                                    arguments = listOf(id),
                                    timeout = 30.seconds,
                                )
                            }
                            teardownNativeProot(id)
                            val displayCleanup = runCatching {
                                closeAllDisplaysAndWait()
                                stopAllDisplayServers()
                            }
                            if (displayCleanup.isSuccess) {
                                releaseTermuxServiceLifetime()
                            } else {
                                Log.e(
                                    LIFECYCLE_LOG_TAG,
                                    "display cleanup failed; retaining Termux service lease id=$id",
                                    displayCleanup.exceptionOrNull(),
                                )
                            }
                        }
                        throw throwable
                    }
                }
            }
        }
    }

    suspend fun stopContainer(id: String) = ContainerOperationLocks.withLock(id) {
        withContext(Dispatchers.IO) {
            x11LifecycleMutex.withLock {
                val ownsDisplay = activeContainerId() == id
                var failure: Throwable? = null
                var displayCleanupComplete = false
                if (ownsDisplay) {
                    holdTermuxServiceLifetime()
                    clearActiveSession()
                    EmbeddedX11ServiceController.cancelPendingDisplayOpen()
                }
                try {
                    commandClient.runBundledHostScript(
                        script = hostScript,
                        action = "stop",
                        arguments = listOf(id),
                        timeout = 45.seconds,
                    )
                } catch (throwable: Throwable) {
                    failure = throwable
                } finally {
                    // Reap the native single-layer desktop and worker Processes (no-op on the
                    // legacy path). The host `stop` sets the stop file, but the app owns these
                    // Processes, so it must destroy them to tear the proots down.
                    teardownNativeProot(id)
                    if (ownsDisplay) {
                        withContext(NonCancellable) {
                            try {
                                // Viewer teardown is the acknowledgement barrier.  Do not stop its
                                // server first, even when the host command was cancelled or failed.
                                closeAllDisplaysAndWait()
                                stopAllDisplayServers()
                                displayCleanupComplete = true
                            } catch (cleanupFailure: Throwable) {
                                if (failure == null) failure = cleanupFailure
                                else failure?.addSuppressed(cleanupFailure)
                            }
                            if (displayCleanupComplete) releaseTermuxServiceLifetime()
                        }
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    suspend fun deleteContainer(id: String, deleteSharedFiles: Boolean) = ContainerOperationLocks.withLock(id) {
        withContext(Dispatchers.IO) {
            x11LifecycleMutex.withLock {
                val wasActive = activeContainerId() == id
                if (wasActive) {
                    holdTermuxServiceLifetime()
                    clearActiveSession()
                    closeAllDisplaysAndWait()
                    stopAllDisplayServers()
                    releaseTermuxServiceLifetime()
                }
                commandClient.runInstalledHost(action = "stop", arguments = listOf(id), timeout = 45.seconds)
                teardownNativeProot(id)
                commandClient.runBundledHostScript(
                    script = hostScript,
                    action = "delete",
                    arguments = listOf(id, if (deleteSharedFiles) "1" else "0"),
                    timeout = 10.minutes,
                )
            }
        }
    }

    suspend fun logs(id: String): String = withContext(Dispatchers.IO) {
        val desktopLogs = commandClient.runBundledHostScript(
            script = hostScript,
            action = "logs",
            arguments = listOf(id),
            timeout = 30.seconds,
        )
        val x11Logs = runCatching {
            commandClient.runBundledX11Script(
                script = x11Script,
                action = "logs",
                arguments = listOf("400"),
                timeout = 20.seconds,
            )
        }.getOrDefault("")
        buildString {
            append("===== Android process / memory =====\n")
            append(ProcessExitDiagnostics.report(context))
            append("\n\n===== Debian / XFCE =====\n")
            append(desktopLogs.ifBlank { "ログはありません。" })
            append("\n\n===== Native Termux:X11 =====\n")
            append(x11Logs.ifBlank { "ネイティブX11ログはありません。" })
        }
    }

    suspend fun liveInstallationLogs(id: String): String = withContext(Dispatchers.IO) {
        val rawLogs = commandClient.runInstalledHost(
            action = "logs",
            arguments = listOf(id, LIVE_LOG_LINE_COUNT.toString()),
            timeout = 10.seconds,
        )
        LiveLogFormatter.format(rawLogs, LIVE_LOG_LINE_COUNT)
    }

    suspend fun repairInterruptedWork() = withContext(Dispatchers.IO) {
        commandClient.runBundledHostScript(
            script = hostScript,
            action = "repair",
            timeout = 45.seconds,
        )
    }

    suspend fun heartbeat(id: String?): Boolean = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            val currentId = activeContainerId()
            // A cancelled/stale KeepAlive job may resume after a newer container acquired this
            // mutex.  It must never probe, clear or replace that newer session's display.
            if (id != null && currentId != id) return@withLock currentId != null
            val effectiveId = currentId ?: id
            if (effectiveId != null) holdTermuxServiceLifetime()
            val effectiveBackend = activeDisplayBackend()

            // Chrome + the complete XFCE desktop already use most of Android's default
            // phantom-process allowance. While the native viewer Activity still owns a
            // verified X11 service, the event-driven in-session supervisor repairs trimmed
            // XFCE components without polling. Avoid adding transient Termux/PRoot probes
            // every 30 seconds; Activity resume first uses a zero-process procfs check and
            // escalates to the installed controller only when a required process is missing.
            if (
                effectiveId != null &&
                effectiveBackend == DesktopDisplayBackend.NATIVE_X11 &&
                withContext(Dispatchers.Main.immediate) { EmbeddedX11Display.isOpen() } &&
                EmbeddedX11ServiceController.isServiceReady(context)
            ) {
                return@withLock true
            }

            val displayBusy = effectiveId?.let { heartbeatDisplay(it) } ?: false

            if (effectiveId != null && activeContainerId() != effectiveId) {
                return@withLock activeContainerId() != null
            }
            val desktopBusy = if (
                effectiveBackend == DesktopDisplayBackend.NATIVE_X11 && displayBusy
            ) {
                // heartbeatNativeDisplay already validates and repairs the complete XFCE
                // session. Avoid creating a second transient PRoot probe every 30 seconds.
                true
            } else {
                runCatching {
                    commandClient.runInstalledHost(
                        action = "heartbeat",
                        arguments = listOfNotNull(effectiveId),
                        timeout = 45.seconds,
                    ).lineSequence().any { it.trim() == "busy=1" }
                }.getOrDefault(false)
            }

            displayBusy || desktopBusy
        }
    }

    /**
     * Runs immediately when the native viewer returns from Gmail, Recents or the lock screen.
     * The normal 30-second watchdog remains the backstop, but a visible blank desktop must not
     * wait for its next interval. Controller and Debian runtime migrations are completed before
     * the viewer opens; resume deliberately uses the installed controller to avoid transient
     * script-deployment and provisioning processes beside Chrome.
     */
    suspend fun recoverActiveDesktopAfterViewerResume(): Boolean = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            val id = activeContainerId() ?: return@withLock false
            if (activeDisplayBackend() != DesktopDisplayBackend.NATIVE_X11) {
                return@withLock false
            }
            holdTermuxServiceLifetime()

            try {
                if (!EmbeddedX11ServiceController.isServiceReady(context)) {
                    throw TermuxCommandException(
                        "復帰時にX11サービスのprocess世代、PIDまたはUnix socketが一致しません。",
                    )
                }

                // Do not launch RunCommand/PRoot beside a healthy Chrome desktop. On Pixel-class
                // devices those transient shells can be the processes that cross Android's
                // phantom-child cap. Reading this UID's /proc entries creates no Linux child.
                when (desktopResumeProcessState(id)) {
                    DesktopResumeProcessState.READY -> {
                        Log.i(
                            LIFECYCLE_LOG_TAG,
                            "viewer resume used zero-process fast path id=$id",
                        )
                        return@withLock true
                    }
                    DesktopResumeProcessState.IN_SESSION_REPAIR -> {
                        // The event-driven supervisor is already replacing one or more direct
                        // children. Poll procfs only; a strict X11/PRoot probe here can race the
                        // replacement windows and tear down a desktop that has just reappeared.
                        repeat(RESUME_IN_SESSION_REPAIR_ATTEMPTS) {
                            delay(RESUME_IN_SESSION_REPAIR_POLL_MILLIS)
                            if (desktopResumeProcessState(id) == DesktopResumeProcessState.READY) {
                                Log.i(
                                    LIFECYCLE_LOG_TAG,
                                    "viewer resume observed in-session repair id=$id",
                                )
                                return@withLock true
                            }
                        }
                    }
                    DesktopResumeProcessState.EXTERNAL_RECOVERY -> Unit
                }

                val result = commandClient.runInstalledHost(
                    action = "resume",
                    arguments = listOf(id),
                    timeout = 90.seconds,
                )
                if (activeContainerId() != id) return@withLock activeContainerId() != null
                Log.i(
                    LIFECYCLE_LOG_TAG,
                    "viewer resume health ready id=$id result=${result.replace('\n', ' ')}",
                )

                // surfaceChanged/onResume already request a full X11 redraw. Add an X damage
                // event when the transport is ready, without treating a still-rebinding Binder
                // as another Linux-session failure.
                val viewerReady = withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11Display.isViewerReady()
                }
                if (viewerReady && isNativeViewerForeground()) {
                    try {
                        commandClient.runInstalledX11(
                            action = "draw-probe",
                            arguments = listOf(id),
                            timeout = 15.seconds,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (redrawFailure: Throwable) {
                        Log.w(
                            LIFECYCLE_LOG_TAG,
                            "resume redraw probe failed id=$id",
                            redrawFailure,
                        )
                    }
                }
                true
            } catch (resumeFailure: Throwable) {
                if (resumeFailure is CancellationException) throw resumeFailure
                Log.w(
                    LIFECYCLE_LOG_TAG,
                    "viewer resume health failed; recovering complete native pipeline id=$id",
                    resumeFailure,
                )
                if (activeContainerId() != id) return@withLock activeContainerId() != null
                heartbeatNativeDisplay(id)
            }
        }
    }

    /**
     * Returns true only when the existing in-session supervisor can finish resume without a new
     * Termux command. A Chrome crash marker with no real browser process intentionally fails this
     * check so the installed controller can wake the supervisor and restore the previous session.
     */
    private fun desktopResumeProcessState(id: String): DesktopResumeProcessState {
        val processes = File("/proc").listFiles()
            ?.asSequence()
            ?.filter { entry -> entry.name.all(Char::isDigit) }
            ?.mapNotNull { entry ->
                runCatching {
                    val status = ProcessExitDiagnostics.parseProcStatus(
                        File(entry, "status").readText(),
                        android.os.Process.myUid(),
                    ) ?: return@runCatching null
                    val arguments = File(entry, "cmdline").readBytes()
                        .toString(Charsets.UTF_8)
                        .split('\u0000')
                        .filter(String::isNotBlank)
                    DesktopChildProcess(
                        name = status.name,
                        pid = status.pid,
                        parentPid = status.parentPid,
                        arguments = arguments,
                    )
                }.getOrNull()
            }
            ?.toList()
            .orEmpty()

        val processByPid = processes.associateBy(DesktopChildProcess::pid)
        val containerRootSegment = "/containers/$id/rootfs"
        val supervisor = processes.firstOrNull { process ->
            process.arguments.any { it == "/usr/local/bin/ldfa-session" } &&
                process.belongsToContainer(processByPid, containerRootSegment)
        }
            ?: return DesktopResumeProcessState.EXTERNAL_RECOVERY
        val supervisedChildren = processes.asSequence()
            .filter { process -> process.parentPid == supervisor.pid }
            .mapTo(mutableSetOf(), DesktopChildProcess::name)
        if (!supervisedChildren.containsAll(REQUIRED_DESKTOP_CHILDREN)) {
            return DesktopResumeProcessState.IN_SESSION_REPAIR
        }

        val chromeMarker = File(
            context.filesDir,
            "usr/var/lib/proot-distro/containers/$id/rootfs/" +
                "home/desktop/.local/state/ldfa/chrome-running",
        )
        if (!chromeMarker.isFile) return DesktopResumeProcessState.READY

        val chromeBrowserAlive = processes.any { process ->
            // PRoot exposes its guest command line as one space-separated /proc entry on
            // Android, while native processes use NUL-separated argv. Match both layouts.
            process.name in CHROME_BROWSER_PROCESS_NAMES &&
                process.arguments.none { it.contains("--type=") }
        }
        return if (chromeBrowserAlive) {
            DesktopResumeProcessState.READY
        } else {
            DesktopResumeProcessState.EXTERNAL_RECOVERY
        }
    }

    fun activeContainerId(): String? = preferences.getString(KEY_ACTIVE_CONTAINER, null)

    fun desktopScalePercent(): Int =
        preferences.getInt(KEY_DESKTOP_SCALE, DEFAULT_DESKTOP_SCALE)

    // Persist the chosen whole-desktop scale and, when a container is active, tell
    // the host to store it and apply live if a desktop session is running. The
    // stored value is reapplied on every desktop start regardless, so the runtime
    // call is best-effort (a not-yet-updated host script simply ignores it).
    suspend fun setDesktopScale(percent: Int): Unit = withContext(Dispatchers.IO) {
        preferences.edit().putInt(KEY_DESKTOP_SCALE, percent).apply()

        // The ONLY lever that visibly enlarges the WHOLE desktop on this stack is
        // the embedded X server's (Termux:X11/Xlorie) own display-scale: it shrinks
        // the logical X screen to physical*100/percent and stretches it onto the
        // Android Surface, so panel + icons + text + Chrome all grow uniformly.
        // Xft/DPI (fonts only) and RandR --scale (Xlorie discards the CRTC
        // transform) do NOT work, which is why earlier attempts had no effect.
        // Drive it via the X server's own prefs (default SharedPreferences, read by
        // LorieView.getDimensionsFromSettings) and the reload broadcast.
        loriePrefs().edit()
            .putString("displayResolutionMode", if (percent == 100) "native" else "scaled")
            .putInt("displayScale", percent)
            .apply()
        // Tell the running X server to re-read the prefs and resize the screen.
        for (key in listOf("displayResolutionMode", "displayScale")) {
            context.sendBroadcast(
                Intent("com.termux.x11.ACTION_PREFERENCES_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("key", key)
                    putExtra("fromBroadcast", true)
                },
            )
        }

        // Also apply the guest-side font/panel scale for crispness; harmless and
        // complements the X-screen scale. Best-effort — a stale host script no-ops.
        val id = activeContainerId() ?: return@withContext
        runCatching {
            commandClient.runInstalledHost(
                action = "set-scale",
                arguments = listOf(id, percent.toString()),
            )
        }
        Unit
    }

    // The X server (Termux:X11) reads its prefs from the process default
    // SharedPreferences ("<pkg>_preferences"); open that exact file so getters/
    // setters below write where LorieView/MainActivity read.
    private fun loriePrefs() =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    // The on-screen extra-keys row (ESC/CTRL/ALT/arrows) is shown only when BOTH
    // showAdditionalKbd (the user toggle) AND additionalKbdVisible are true
    // (MainActivity: showNow = ... && showAdditionalKbd && additionalKbdVisible).
    // Default is true, matching the X server's own default.
    fun extraKeysVisible(): Boolean = loriePrefs().getBoolean("showAdditionalKbd", true)

    suspend fun setExtraKeysVisible(visible: Boolean): Unit = withContext(Dispatchers.IO) {
        loriePrefs().edit()
            // Also set additionalKbdVisible so turning it back ON actually reveals
            // the row (a prior hide could have left the runtime-visible flag false).
            .putBoolean("showAdditionalKbd", visible)
            .putBoolean("additionalKbdVisible", visible)
            .apply()
        for (key in listOf("showAdditionalKbd", "additionalKbdVisible")) {
            context.sendBroadcast(
                Intent("com.termux.x11.ACTION_PREFERENCES_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("key", key)
                    putExtra("fromBroadcast", true)
                },
            )
        }
        Unit
    }

    fun keyboardLayout(): KeyboardLayout =
        KeyboardLayout.fromId(preferences.getString(KEY_KEYBOARD_LAYOUT, null))

    // Persist the chosen physical keyboard layout and, when a container is active,
    // tell the host to store it per-environment and apply live if a desktop is
    // running. The layout is decided by the guest's XKB (Xorg :1), not by the X
    // server's own prefs — so unlike the display scale there is no Lorie pref to
    // set here; the host script drives setxkbmap / xfconf / Fcitx. The stored
    // value is reapplied on every desktop start, so the runtime call is
    // best-effort (a not-yet-updated host script simply ignores it).
    suspend fun setKeyboardLayout(layout: KeyboardLayout): Unit = withContext(Dispatchers.IO) {
        preferences.edit().putString(KEY_KEYBOARD_LAYOUT, layout.id).apply()
        val id = activeContainerId() ?: return@withContext
        runCatching {
            commandClient.runInstalledHost(
                action = "set-keymap",
                arguments = listOf(id, layout.id),
            )
        }
        Unit
    }

    fun activeDisplayBackend(): DesktopDisplayBackend? {
        activeContainerId() ?: return null
        return DesktopDisplayBackend.fromPreference(preferences.getString(KEY_ACTIVE_BACKEND, null))
            ?: DesktopDisplayBackend.NATIVE_X11
    }

    fun openTerminal() = EmbeddedTermuxRuntime.openTerminal(context)

    fun openDisplay() {
        when (activeDisplayBackend()) {
            DesktopDisplayBackend.NATIVE_X11 -> EmbeddedX11ServiceController.openDisplay(context)
            null -> Unit
        }
    }

    fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openThisAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    private suspend fun selectAndStartDisplayBackend(id: String): DesktopDisplayBackend {
        // Native X11 is the only backend. The VNC fallback was removed: its
        // first-time guest provisioning took ~30 minutes through a nested proot
        // and even a warm start took minutes — failing fast with the native
        // diagnostics is the better experience.
        startAndVerifyNativeX11(id)
        return DesktopDisplayBackend.NATIVE_X11
    }

    private suspend fun startAndVerifyNativeX11(id: String) {
        // XKB data and the X11 socket directory must exist BEFORE the :x11
        // process starts Xorg — Xorg cannot create its socket into a missing
        // $PREFIX/tmp/.X11-unix and the service never becomes ready. Bootstrap
        // runs this once, but state can drift (a bootstrap that never finished,
        // a cleared tmp); the prepare action is idempotent and cheap, so
        // re-ensure it on every native start.
        EmbeddedX11PrerequisiteController.ensure(context)
        var lastFailure: Throwable? = null
        var lastLogs = ""
        val attemptedModes = mutableListOf<String>()

        for ((index, mode) in NATIVE_X11_RENDER_MODES.withIndex()) {
            var legacyRetryUseful = true
            attemptedModes += mode
            try {
                EmbeddedX11ServiceController.restartAndWait(
                    context = context,
                    legacyDrawing = mode == NATIVE_X11_MODE_LEGACY,
                )
                Log.i(LIFECYCLE_LOG_TAG, "native service ready mode=$mode")
                commandClient.runBundledX11Script(
                    script = x11Script,
                    action = "probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )
                Log.i(LIFECYCLE_LOG_TAG, "native Debian probe ready mode=$mode")

                val viewerBound = withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11ServiceController.openDisplay(context)
                }
                Log.i(LIFECYCLE_LOG_TAG, "native viewer bind requested mode=$mode bound=$viewerBound")
                if (!viewerBound) {
                    throw TermuxCommandException(
                        "内蔵X11 viewerを現在のサービス世代へbindできませんでした。",
                    )
                }
                if (!waitForNativeViewerReady()) {
                    // Legacy drawing changes Xorg buffer transport; it cannot repair a missing
                    // Activity, Surface, Binder or EGL context. Avoid opening the same broken
                    // viewer a second time before falling back to the isolated VNC path.
                    legacyRetryUseful = false
                    val launchFailure =
                        EmbeddedX11ServiceController.consumeDisplayOpenFailure()
                    throw TermuxCommandException(
                        buildString {
                            append("ネイティブX11表示は${mode}描画でXサーバーへ接続しましたが、Android表示Activity、SurfaceまたはEGL rendererを準備できませんでした。")
                            launchFailure?.message?.takeIf { it.isNotBlank() }?.let {
                                append("\n\nViewer launch:\n")
                                append(it.takeLast(2000))
                            }
                        },
                        launchFailure,
                    )
                }

                val presentationBaseline = withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11Display.successfulPresentSerial()
                }
                commandClient.runInstalledX11(
                    action = "draw-probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )
                if (!waitForNativePresentationAfter(presentationBaseline)) {
                    throw TermuxCommandException(
                        "ネイティブX11は接続済みですが、描画プローブをAndroid Surfaceへpresentできませんでした。",
                    )
                }
                delay(NATIVE_X11_POST_ACTIVITY_STABILIZE_MILLIS)
                return
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Log.w(LIFECYCLE_LOG_TAG, "native mode failed mode=$mode", throwable)
                lastFailure = throwable
                lastLogs = runCatching {
                    commandClient.runInstalledX11("logs", listOf("350"), 15.seconds)
                }.getOrDefault("")
                closeNativeDisplayAndWait()
                EmbeddedX11ServiceController.stopAndWait(context)
                if (legacyRetryUseful && index + 1 < NATIVE_X11_RENDER_MODES.size) {
                    delay(NATIVE_X11_RETRY_DELAY_MILLIS)
                } else {
                    break
                }
            }
        }

        throw TermuxCommandException(
            buildString {
                if (attemptedModes.size == NATIVE_X11_RENDER_MODES.size) {
                    append("ネイティブTermux:X11を通常描画・legacy描画の両方で安定起動できませんでした。デスクトップの起動を中断しました。")
                } else {
                    append("Android表示Activity、SurfaceまたはEGL rendererを準備できませんでした。legacy描画では改善しないためデスクトップの起動を中断しました。")
                }
                lastFailure?.message?.takeIf { it.isNotBlank() }?.let {
                    append("\n\nNative failure:\n")
                    append(it.takeLast(3000))
                }
                if (lastLogs.isNotBlank()) {
                    append("\n\nNative X11 log:\n")
                    append(lastLogs.takeLast(5000))
                }
            },
            lastFailure,
        )
    }

    private suspend fun heartbeatDisplay(id: String): Boolean {
        return when (activeDisplayBackend()) {
            DesktopDisplayBackend.NATIVE_X11 -> heartbeatNativeDisplay(id)
            null -> false
        }
    }

    private suspend fun heartbeatNativeDisplay(id: String): Boolean {
        val viewerWasOpen = withContext(Dispatchers.Main.immediate) {
            EmbeddedX11Display.isOpen()
        }

        return try {
            if (!EmbeddedX11ServiceController.isServiceReady(context)) {
                throw TermuxCommandException("X11サービスのprocess世代、PIDまたはUnix socketが一致しません。")
            }
            commandClient.runInstalledX11(
                action = "probe",
                arguments = listOf(id),
                timeout = 35.seconds,
            )
            commandClient.runInstalledHost(
                action = "health",
                arguments = listOf(id),
                timeout = 45.seconds,
            )
            var verifyViewerPresentation = viewerWasOpen && isNativeViewerForeground()
            if (
                verifyViewerPresentation &&
                !waitForNativeViewerReady(NATIVE_X11_HEARTBEAT_VIEWER_ATTEMPTS)
            ) {
                // Home/Recents can detach the Surface while this bounded wait is running. That is
                // not a renderer failure; the Xorg/XFCE probes above remain authoritative while
                // the viewer is backgrounded.
                verifyViewerPresentation = isNativeViewerForeground()
                if (verifyViewerPresentation) {
                    throw TermuxCommandException(
                        "X11サーバーは応答していますがAndroid表示SurfaceまたはEGL rendererが利用できません。",
                    )
                }
            }
            verifyViewerPresentation = verifyViewerPresentation && isNativeViewerForeground()
            if (verifyViewerPresentation) {
                val baseline = withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11Display.successfulPresentSerial()
                }
                commandClient.runInstalledX11(
                    action = "draw-probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )
                if (
                    !waitForNativePresentationAfter(baseline) &&
                    isNativeViewerForeground()
                ) {
                    throw TermuxCommandException(
                        "X11サービスは応答していますがAndroid Surfaceへのpresentが停止しています。",
                    )
                }
            } else if (viewerWasOpen) {
                Log.i(
                    LIFECYCLE_LOG_TAG,
                    "native heartbeat kept Xorg/XFCE alive while viewer is backgrounded",
                )
            }
            true
        } catch (nativeFailure: Throwable) {
            if (nativeFailure is CancellationException) throw nativeFailure
            val nativeRecoveryFailure = try {
                // Once Xorg dies its XFCE clients cannot be assumed to survive. Stop the old worker
                // before replacing the display and start it again only after the endpoint is ready.
                commandClient.runInstalledHost(
                    action = "stop",
                    arguments = listOf(id, PRESERVE_CHROME_RESTORE),
                    timeout = 30.seconds,
                )
                teardownNativeProot(id)
                closeNativeDisplayAndWait()
                EmbeddedX11ServiceController.stopAndWait(context)
                if (viewerWasOpen) {
                    startAndVerifyNativeX11(id)
                } else {
                    restartNativeServerWithoutViewer(id)
                }
                startAndProbeHost(id)
                if (viewerWasOpen) {
                    verifyNativeDesktopPresentation(id)?.let { throw it }
                }
                null
            } catch (recoveryFailure: Throwable) {
                if (recoveryFailure is CancellationException) {
                    cleanupCancelledRecovery(id)
                    throw recoveryFailure
                }
                recoveryFailure
            }

            if (nativeRecoveryFailure == null) {
                setActiveSession(id, DesktopDisplayBackend.NATIVE_X11)
                true
            } else {
                // No VNC fallback any more: an unrecoverable native display is a
                // stopped desktop with diagnostics, not a degraded mode.
                handleUnrecoverableDisplayFailure(id)
                false
            }
        }
    }

    private suspend fun restartNativeServerWithoutViewer(id: String) {
        var lastFailure: Throwable? = null
        for ((index, mode) in NATIVE_X11_RENDER_MODES.withIndex()) {
            try {
                EmbeddedX11ServiceController.restartAndWait(
                    context = context,
                    legacyDrawing = mode == NATIVE_X11_MODE_LEGACY,
                )
                commandClient.runInstalledX11(
                    action = "probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )
                return
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastFailure = throwable
                EmbeddedX11ServiceController.stopAndWait(context)
                if (index + 1 < NATIVE_X11_RENDER_MODES.size) {
                    delay(NATIVE_X11_RETRY_DELAY_MILLIS)
                }
            }
        }
        throw TermuxCommandException(
            "バックグラウンドのネイティブX11を復旧できませんでした。",
            lastFailure,
        )
    }

    private suspend fun handleUnrecoverableDisplayFailure(id: String) {
        if (activeContainerId() != id) return
        clearActiveSession()
        runCatching {
            commandClient.runInstalledHost(
                action = "stop",
                arguments = listOf(id, PRESERVE_CHROME_RESTORE),
                timeout = 30.seconds,
            )
        }
        val displayCleanup = runCatching {
            teardownNativeProot(id)
            closeAllDisplaysAndWait()
            stopAllDisplayServers()
        }
        if (displayCleanup.isSuccess) releaseTermuxServiceLifetime()
        else Log.e(
            LIFECYCLE_LOG_TAG,
            "unrecoverable display cleanup failed; retaining Termux service lease id=$id",
            displayCleanup.exceptionOrNull(),
        )
    }

    private suspend fun cleanupCancelledRecovery(id: String) {
        withContext(NonCancellable) {
            handleUnrecoverableDisplayFailure(id)
        }
    }

    private suspend fun startAndProbeHost(id: String) {
        Log.i(LIFECYCLE_LOG_TAG, "host worker start id=$id")
        commandClient.runBundledHostScript(
            script = hostScript,
            action = "start",
            arguments = listOf(id),
            timeout = 45.seconds,
        )
        Log.i(LIFECYCLE_LOG_TAG, "host worker command accepted id=$id")
        // Native-proot path: cmd_start prepared state but deliberately did NOT spawn
        // the worker (it can't outlive its short-lived proot). Launch it here under
        // its own persistent proot and hold the process so it stays alive.
        launchNativeProotWorkerIfNeeded(id)
        // The worker does host prep (audio, X1 wait, xset preflight) then publishes a
        // session request. Launch the DESKTOP as its OWN single native-proot layer — one
        // layer deep, not nested in the worker's proot — so XFCE composes in ~1s instead
        // of stalling. The guest session then publishes the ready marker cmd_probe waits on.
        launchNativeProotSessionIfNeeded(id)
        // Native-proot startup is slower end-to-end (embedded Xorg cold-start ~33s on ARM
        // before XFCE can compose and publish the ready marker). 45s is not enough
        // headroom, so allow 120s on the native path. The legacy tmux path keeps 45s.
        val probeTimeout = if (prootWorkerLauncher.usable) 180.seconds else 45.seconds
        commandClient.runInstalledHost(
            action = "probe",
            arguments = listOf(id),
            timeout = probeTimeout,
        )
        Log.i(LIFECYCLE_LOG_TAG, "host desktop probe ready id=$id")
    }

    private fun launchNativeProotWorkerIfNeeded(id: String) {
        if (!prootWorkerLauncher.usable) return
        prootWorkers[id]?.let { if (it.isAlive) return }
        val display = readMetaDisplay(id)
        val process = prootWorkerLauncher.start(id, display)
        if (process != null) {
            prootWorkers[id] = process
            Log.i(LIFECYCLE_LOG_TAG, "native-proot worker launched id=$id display=$display")
        } else {
            Log.w(LIFECYCLE_LOG_TAG, "native-proot worker launch returned null id=$id")
        }
    }

    /**
     * Launch (or re-launch) the Debian-install worker in its own persistent proot on the
     * native path, and hold the Process for the whole install. A daemon thread reaps the
     * map entry when the install finishes so the proot is released. Idempotent: skips if a
     * live install worker is already held. No-op on the legacy path (launcher not usable;
     * cmd_create's tmux backend runs the worker there).
     */
    private fun launchNativeProotInstallWorkerIfNeeded(id: String) {
        if (!prootWorkerLauncher.usable) return
        prootInstallWorkers[id]?.let { if (it.isAlive) return }
        val process = prootWorkerLauncher.startInstall(id)
        if (process != null) {
            prootInstallWorkers[id] = process
            Log.i(LIFECYCLE_LOG_TAG, "native-proot install worker launched id=$id")
            Thread {
                runCatching { process.waitFor() }
                prootInstallWorkers.remove(id, process)
                Log.i(LIFECYCLE_LOG_TAG, "native-proot install worker finished id=$id")
            }.apply { isDaemon = true; name = "ldfa-install-hold-$id" }.start()
        } else {
            Log.w(LIFECYCLE_LOG_TAG, "native-proot install worker launch returned null id=$id")
        }
    }

    /**
     * Once the install worker publishes its provision request (rootfs extracted, guest
     * body written into the rootfs), launch the Debian guest provision as its OWN single
     * native-proot layer and hold the Process. One layer deep (not nested in the worker's
     * proot) is what makes the dpkg-heavy xfce unpack fast. The worker supervises the
     * guest's success/failure marker; the app just keeps this proot alive until the
     * provision exits, then reaps it. Torn down on stop alongside the worker.
     */
    private suspend fun launchNativeProotInstallProvisionIfNeeded(id: String) {
        if (!prootWorkerLauncher.usable) return
        prootInstallSessions[id]?.let { if (it.isAlive) return }
        val request = prootWorkerLauncher.installRequestFile(id)
        // The worker writes the request after install_container extracts the rootfs
        // (a Debian download+unpack). Poll generously; bail if the worker died first.
        var waited = 0
        while (!request.isFile && waited < 1_800_000) {
            if (prootInstallWorkers[id]?.isAlive != true) {
                error("Linuxのダウンロードが中断されました。環境のログを確認し「導入・起動を修復」を選んでください。")
            }
            delay(500); waited += 500
        }
        if (!request.isFile) {
            File(request.parentFile, "$id.stop").writeText("request timeout")
            teardownNativeProot(id)
            error("Linuxのダウンロードが時間内に完了しませんでした。接続を確認して導入を再開してください。")
        }
        val process = prootWorkerLauncher.startInstallProvision(id)
        if (process != null) {
            synchronized(prootInstallSessions) { prootInstallSessions[id] = process }
            Log.i(LIFECYCLE_LOG_TAG, "native-proot install provision launched (single-layer) id=$id")
            Thread {
                runCatching { process.waitFor() }
                // SIGKILL cannot run the guest ERR trap. Only the current process may
                // report failure; a late callback from a stopped generation must not
                // poison the next request using the same rootfs.
                synchronized(prootInstallSessions) {
                    if (prootInstallSessions[id] === process) {
                        runCatching {
                            val rootfs = request.takeIf { it.isFile }?.readLines()
                                ?.firstOrNull { it.startsWith("ROOTFS=") }?.removePrefix("ROOTFS=")
                            if (rootfs != null) {
                                val root = File(rootfs, "root")
                                if (!File(root, ".ldfa-provision.done").exists() && request.exists()) {
                                    File(root, ".ldfa-provision.failed").writeText("guest process exited")
                                }
                            }
                        }
                        prootInstallSessions.remove(id, process)
                    }
                }
                Log.i(LIFECYCLE_LOG_TAG, "native-proot install provision finished id=$id")
            }.apply { isDaemon = true; name = "ldfa-provision-hold-$id" }.start()
        } else {
            File(request.parentFile, "$id.stop").writeText("provision launch failed")
            teardownNativeProot(id)
            error("Linuxの構築処理を起動できませんでした。ログを確認して導入を再開してください。")
        }
    }

    /**
     * Once the worker publishes its session request (host prep done: audio, X1, xset
     * preflight), launch the desktop as its OWN single native-proot layer and hold the
     * Process. One layer deep (not nested in the worker's proot) is what lets XFCE
     * compose in ~1s. Destroyed on stop alongside the worker.
     */
    private suspend fun launchNativeProotSessionIfNeeded(id: String) {
        if (!prootWorkerLauncher.usable) return
        prootSessions[id]?.let { if (it.isAlive) return }
        val request = prootWorkerLauncher.sessionRequestFile(id)
        // The worker writes the request after its X1-wait (~33s cold) + xset preflight.
        // Poll up to ~90s; the overall probe timeout (180s) still bounds the whole start.
        var waited = 0
        while (!request.isFile && waited < 90_000) {
            if (prootWorkers[id]?.isAlive == false) {
                Log.w(LIFECYCLE_LOG_TAG, "native-proot worker died before session request id=$id")
                return
            }
            delay(500); waited += 500
        }
        if (!request.isFile) {
            Log.w(LIFECYCLE_LOG_TAG, "session request never appeared id=$id")
            return
        }
        val process = prootWorkerLauncher.startSession(id)
        if (process != null) {
            prootSessions[id] = process
            Log.i(LIFECYCLE_LOG_TAG, "native-proot session launched (single-layer) id=$id")
        } else {
            Log.w(LIFECYCLE_LOG_TAG, "native-proot session launch returned null id=$id")
        }
    }

    /**
     * Destroy the native-proot session and worker Processes for [id] and drop them from
     * their maps. Called on stop so the single-layer desktop proot (and the outer worker
     * proot) are reaped instead of lingering. Safe to call when nothing is running.
     */
    private fun teardownNativeProot(id: String) {
        var failure: Throwable? = null
        for (processes in listOf(prootSessions, prootWorkers, prootInstallSessions, prootInstallWorkers)) {
            val process = processes[id] ?: continue
            try {
                process.destroyForcibly()
                check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { "Linuxの実行処理を停止できませんでした。" }
                processes.remove(id, process)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun stopInstallProvision(id: String) {
        val process = prootInstallSessions[id] ?: return
        process.destroyForcibly()
        check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "以前のLinux構築処理を停止できませんでした。アプリを開き直して再試行してください。"
        }
        prootInstallSessions.remove(id, process)
    }

    private fun readMetaDisplay(id: String): Int {
        val f = File(
            context.filesDir,
            "home/.local/share/linux-desktop-for-android/containers/$id/display",
        )
        return f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 1
    }

    private suspend fun ensureBundledDesktopApps(id: String) {
        Log.i(LIFECYCLE_LOG_TAG, "bundled desktop app provisioning start id=$id")
        if (prootWorkerLauncher.usable) {
            commandClient.runBundledHostScript(hostScript, "prepare-apps", listOf(id), timeout = 45.seconds)
            if (prootWorkerLauncher.appsRequestFile(id).isFile) {
                val process = prootWorkerLauncher.startAppsProvision(id)
                    ?: throw TermuxCommandException("Linuxアプリの設定処理を起動できませんでした。")
                synchronized(prootInstallSessions) { prootInstallSessions[id] = process }
                try {
                    withTimeout(30.minutes.inWholeMilliseconds) {
                        while (process.isAlive) delay(250)
                    }
                    val exitCode = process.exitValue()
                    commandClient.runBundledHostScript(hostScript, "finish-apps", listOf(id), timeout = 45.seconds)
                    check(exitCode == 0) { "Linuxアプリの設定に失敗しました（exit=$exitCode）。ログを確認してください。" }
                } finally {
                    withContext(NonCancellable) { stopInstallProvision(id) }
                    prootWorkerLauncher.appsRequestFile(id).delete()
                }
            }
            Log.i(LIFECYCLE_LOG_TAG, "bundled desktop app provisioning ready id=$id")
            return
        }
        commandClient.runBundledHostScript(
            script = hostScript,
            action = "ensure-apps",
            arguments = listOf(id),
            timeout = 10.minutes,
        )
        Log.i(LIFECYCLE_LOG_TAG, "bundled desktop app provisioning ready id=$id")
    }

    /** Returns null only when the ready XFCE/WM desktop reaches the Android Surface. */
    private suspend fun verifyNativeDesktopPresentation(id: String): Throwable? {
        return try {
            if (!waitForNativeViewerReady(NATIVE_X11_HEARTBEAT_VIEWER_ATTEMPTS)) {
                throw TermuxCommandException(
                    "XFCE起動後にAndroid SurfaceまたはEGL rendererが失われました。",
                )
            }
            val baseline = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.successfulPresentSerial()
            }
            commandClient.runInstalledX11(
                action = "draw-probe",
                arguments = listOf(id),
                timeout = 35.seconds,
            )
            if (!waitForNativePresentationAfter(baseline)) {
                throw TermuxCommandException(
                    "XFCEとウィンドウマネージャーは起動しましたが、デスクトップをAndroid Surfaceへpresentできませんでした。",
                )
            }
            null
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throwable
        }
    }

    private suspend fun stopAllDisplayServers() {
        EmbeddedX11ServiceController.stopAndWait(context)
    }

    /**
     * Upstream TermuxService clears the complete Termux TMPDIR from onDestroy(). Native X11 and
     * the compatibility server deliberately publish their sockets there so PRoot --shared-tmp can
     * expose them to Debian. Keep the service bound for the complete desktop lifetime; releasing
     * it is safe only after every viewer and display server has acknowledged shutdown.
     */
    private suspend fun holdTermuxServiceLifetime() = withContext(Dispatchers.Main.immediate) {
        if (termuxServiceLease != null) return@withContext

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(LIFECYCLE_LOG_TAG, "Termux service lifetime lease connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(LIFECYCLE_LOG_TAG, "Termux service lifetime lease disconnected")
            }
        }
        if (!context.bindService(
                Intent(context, TermuxService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            throw TermuxCommandException(
                "内蔵ターミナルの一時領域をLinuxデスクトップの実行中に保持できませんでした。",
            )
        }
        termuxServiceLease = connection
        Log.i(LIFECYCLE_LOG_TAG, "Termux service lifetime lease acquired")
    }

    private suspend fun releaseTermuxServiceLifetime() = withContext(Dispatchers.Main.immediate) {
        val connection = termuxServiceLease ?: return@withContext
        termuxServiceLease = null
        runCatching { context.unbindService(connection) }
            .onFailure { Log.w(LIFECYCLE_LOG_TAG, "Termux service lease release failed", it) }
        Log.i(LIFECYCLE_LOG_TAG, "Termux service lifetime lease released")
    }

    private suspend fun waitForNativeViewerReady(
        attempts: Int = NATIVE_X11_ACTIVITY_WAIT_ATTEMPTS,
    ): Boolean {
        repeat(attempts) {
            if (EmbeddedX11ServiceController.hasDisplayOpenFailure()) return false
            val ready = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isViewerReady()
            }
            if (ready) return true
            delay(NATIVE_X11_ACTIVITY_POLL_MILLIS)
        }
        return false
    }

    private suspend fun isNativeViewerForeground(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            EmbeddedX11Display.isViewerForeground()
        }

    private suspend fun waitForNativePresentationAfter(baseline: Long): Boolean {
        repeat(NATIVE_X11_ACTIVITY_WAIT_ATTEMPTS) {
            val status = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isViewerReady() to
                    EmbeddedX11Display.successfulPresentSerial()
            }
            if (!status.first || status.second < baseline) return false
            if (status.second > baseline) return true
            delay(NATIVE_X11_ACTIVITY_POLL_MILLIS)
        }
        return false
    }

    private suspend fun closeNativeDisplayAndWait() {
        EmbeddedX11ServiceController.cancelPendingDisplayOpen()
        withContext(Dispatchers.Main.immediate) {
            EmbeddedX11Display.close(context)
        }
        var stableClosedPolls = 0
        // Start with the fast threshold; the first observation of open==true
        // permanently upgrades this call to the full debounce (a real teardown
        // flap is in progress, so ride it out).
        var requiredStablePolls = DISPLAY_CLOSE_STABLE_POLLS_WHEN_ALREADY_CLOSED
        repeat(NATIVE_X11_ACTIVITY_CLOSE_ATTEMPTS) {
            val open = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isOpen()
            }
            if (open) {
                stableClosedPolls = 0
                requiredStablePolls = DISPLAY_CLOSE_STABLE_POLLS
                withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11Display.close(context)
                }
            } else {
                stableClosedPolls++
                if (stableClosedPolls >= requiredStablePolls) return
            }
            delay(NATIVE_X11_ACTIVITY_CLOSE_POLL_MILLIS)
        }
        throw TermuxCommandException(
            "内蔵X11 viewerのrendererを安全に終了できませんでした。表示サーバーの切り替えを中止します。",
        )
    }

    private suspend fun closeAllDisplaysAndWait() {
        closeNativeDisplayAndWait()
    }

    private fun setActiveSession(id: String, backend: DesktopDisplayBackend) {
        preferences.edit()
            .putString(KEY_ACTIVE_CONTAINER, id)
            .putString(KEY_ACTIVE_BACKEND, backend.preferenceValue)
            .apply()
    }

    private fun clearActiveSession() {
        preferences.edit()
            .remove(KEY_ACTIVE_CONTAINER)
            .remove(KEY_ACTIVE_BACKEND)
            .apply()
    }

    private fun createContainerId(displayName: String): String {
        val asciiPrefix = displayName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(16)
            .ifBlank { "debian-xfce" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$asciiPrefix-$suffix"
    }

    companion object {
        private data class DesktopChildProcess(
            val name: String,
            val pid: Int,
            val parentPid: Int,
            val arguments: List<String>,
        ) {
            fun belongsToContainer(
                processByPid: Map<Int, DesktopChildProcess>,
                containerRootSegment: String,
            ): Boolean {
                var current: DesktopChildProcess? = this
                repeat(MAX_PROCESS_ANCESTORS) {
                    val process = current ?: return false
                    if (process.arguments.any { it.contains(containerRootSegment) }) return true
                    current = processByPid[process.parentPid]
                }
                return false
            }
        }

        private enum class DesktopResumeProcessState {
            READY,
            IN_SESSION_REPAIR,
            EXTERNAL_RECOVERY,
        }

        private val GLOBAL_DISPLAY_LIFECYCLE_MUTEX = Mutex()
        private val REQUIRED_DESKTOP_CHILDREN = setOf(
            "xfsettingsd",
            "xfwm4",
            "xfce4-panel",
            "xfdesktop",
        )
        private val CHROME_BROWSER_PROCESS_NAMES = setOf(
            "chrome",
            "google-chrome",
            "google-chrome-stable",
        )
        private const val RESUME_IN_SESSION_REPAIR_POLL_MILLIS = 250L
        private const val RESUME_IN_SESSION_REPAIR_ATTEMPTS = 12
        private const val MAX_PROCESS_ANCESTORS = 8
        private const val PREFERENCES = "linux_desktop_preferences"
        private const val KEY_ACTIVE_CONTAINER = "active_container"
        private const val KEY_ACTIVE_BACKEND = "active_display_backend"
        private const val KEY_DESKTOP_SCALE = "desktop_scale_percent"
        private const val DEFAULT_DESKTOP_SCALE = 100
        val DESKTOP_SCALE_PRESETS = listOf(100, 125, 150, 175, 200, 225, 250)
        private const val KEY_KEYBOARD_LAYOUT = "keyboard_layout"
        private const val PRESERVE_CHROME_RESTORE = "1"
        private const val LIVE_LOG_LINE_COUNT = 40

        private const val NATIVE_X11_MODE_NORMAL = "normal"
        private const val NATIVE_X11_MODE_LEGACY = "legacy"
        private val NATIVE_X11_RENDER_MODES = listOf(NATIVE_X11_MODE_NORMAL, NATIVE_X11_MODE_LEGACY)
        private const val NATIVE_X11_ACTIVITY_POLL_MILLIS = 250L
        private const val NATIVE_X11_ACTIVITY_WAIT_ATTEMPTS = 60
        private const val NATIVE_X11_HEARTBEAT_VIEWER_ATTEMPTS = 20
        private const val NATIVE_X11_ACTIVITY_CLOSE_POLL_MILLIS = 100L
        private const val NATIVE_X11_ACTIVITY_CLOSE_ATTEMPTS = 50
        private const val NATIVE_X11_POST_ACTIVITY_STABILIZE_MILLIS = 1000L
        private const val NATIVE_X11_RETRY_DELAY_MILLIS = 1000L

        private const val DISPLAY_CLOSE_STABLE_POLLS = 10

        // Fast-exit for the common start case where NOTHING is open yet (cold or
        // warm start: the previous run's viewer is already gone). When the very
        // first poll of a close-and-wait already observes open==false, only this
        // many confirmations are needed instead of the full debounce. The full
        // DISPLAY_CLOSE_STABLE_POLLS debounce exists to ride out the teardown
        // flap of a LIVE viewer (finishAffinity called but the Activity lingers
        // and isOpen can bounce non-null->null->non-null); that flap cannot
        // happen when nothing was ever open. The instant any poll sees open==true
        // the code reverts to the full debounce for the rest of that call.
        private const val DISPLAY_CLOSE_STABLE_POLLS_WHEN_ALREADY_CLOSED = 2
        private const val LIFECYCLE_LOG_TAG = "LDFA-Lifecycle"
    }
}
