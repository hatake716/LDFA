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
import com.hatake716.linuxdesktop.display.VncFallbackActivity
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
    NATIVE_X11("native-x11"),
    COMPATIBILITY_VNC("compatibility-vnc");

    companion object {
        fun fromPreference(value: String?): DesktopDisplayBackend? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

class LinuxDesktopRepository(private val context: Context) {
    private val commandClient = TermuxCommandClient(context)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val x11LifecycleMutex = GLOBAL_DISPLAY_LIFECYCLE_MUTEX
    private var termuxServiceLease: ServiceConnection? = null
    private val hostScript: String by lazy {
        val bundled = context.assets.open("ldfa-host.sh").bufferedReader().use { it.readText() }
        HostScriptCompatibility.normalize(bundled)
    }
    private val x11Script: String by lazy {
        context.assets.open("ldfa-x11.sh").bufferedReader().use { it.readText() }
    }
    private val vncScript: String by lazy {
        context.assets.open("ldfa-vnc.sh").bufferedReader().use { it.readText() }
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
        id
    }

    suspend fun startContainer(id: String): DesktopDisplayBackend = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            holdTermuxServiceLifetime()
            clearActiveSession()
            try {
                // A previous run can still own a worker with DISPLAY=:2. Stop it before selecting
                // a new backend so start_run_worker cannot reuse the stale tmux generation.
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
                closeAllDisplaysAndWait()
                stopAllDisplayServers()
                ensureBundledDesktopApps(id)

                var backend = selectAndStartDisplayBackend(id)
                startAndProbeHost(id)

                if (backend == DesktopDisplayBackend.NATIVE_X11) {
                    val desktopPresentationFailure = verifyNativeDesktopPresentation(id)
                    if (desktopPresentationFailure != null) {
                        // The blank-root probe succeeded but XFCE could not be presented. Tear down
                        // this exact pipeline before starting :2; never leave both displays alive.
                        commandClient.runInstalledHost(
                            action = "stop",
                            arguments = listOf(id),
                            timeout = 30.seconds,
                        )
                        closeNativeDisplayAndWait()
                        EmbeddedX11ServiceController.stopAndWait(context)
                        startAndVerifyCompatibilityDisplay(id, desktopPresentationFailure)
                        backend = DesktopDisplayBackend.COMPATIBILITY_VNC
                        startAndProbeHost(id)
                    }
                }

                if (backend == DesktopDisplayBackend.COMPATIBILITY_VNC) {
                    delay(COMPATIBILITY_VIEWER_OPEN_DELAY_MILLIS)
                    withContext(Dispatchers.Main.immediate) {
                        VncFallbackActivity.open(context)
                    }
                }

                setActiveSession(id, backend)
                backend
            } catch (throwable: Throwable) {
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

    suspend fun stopContainer(id: String) = withContext(Dispatchers.IO) {
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

    suspend fun deleteContainer(id: String, deleteSharedFiles: Boolean) = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            val wasActive = activeContainerId() == id
            if (wasActive) {
                holdTermuxServiceLifetime()
                clearActiveSession()
                closeAllDisplaysAndWait()
                stopAllDisplayServers()
                releaseTermuxServiceLifetime()
            }
            commandClient.runBundledHostScript(
                script = hostScript,
                action = "delete",
                arguments = listOf(id, if (deleteSharedFiles) "1" else "0"),
                timeout = 10.minutes,
            )
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
        val compatibilityLogs = runCatching {
            commandClient.runBundledVncScript(
                script = vncScript,
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
            append("\n\n===== Compatibility X11 / VNC =====\n")
            append(compatibilityLogs.ifBlank { "互換表示ログはありません。" })
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

    fun activeDisplayBackend(): DesktopDisplayBackend? {
        activeContainerId() ?: return null
        return DesktopDisplayBackend.fromPreference(preferences.getString(KEY_ACTIVE_BACKEND, null))
            ?: DesktopDisplayBackend.NATIVE_X11
    }

    fun openTerminal() = EmbeddedTermuxRuntime.openTerminal(context)

    fun openDisplay() {
        when (activeDisplayBackend()) {
            DesktopDisplayBackend.NATIVE_X11 -> EmbeddedX11ServiceController.openDisplay(context)
            DesktopDisplayBackend.COMPATIBILITY_VNC -> VncFallbackActivity.open(context)
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
        return try {
            startAndVerifyNativeX11(id)
            DesktopDisplayBackend.NATIVE_X11
        } catch (nativeFailure: Throwable) {
            if (nativeFailure is CancellationException) throw nativeFailure
            closeNativeDisplayAndWait()
            EmbeddedX11ServiceController.stopAndWait(context)
            startAndVerifyCompatibilityDisplay(id, nativeFailure)
            DesktopDisplayBackend.COMPATIBILITY_VNC
        }
    }

    private suspend fun startAndVerifyNativeX11(id: String) {
        var lastFailure: Throwable? = null
        var lastLogs = ""
        val attemptedModes = mutableListOf<String>()

        for ((index, mode) in NATIVE_X11_RENDER_MODES.withIndex()) {
            var legacyRetryUseful = true
            attemptedModes += mode
            try {
                runCatching { commandClient.runInstalledVnc("stop", timeout = 15.seconds) }
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
                    append("ネイティブTermux:X11を通常描画・legacy描画の両方で安定起動できませんでした。互換表示へ切り替えます。")
                } else {
                    append("Android表示Activity、SurfaceまたはEGL rendererを準備できませんでした。legacy描画では改善しないため互換表示へ切り替えます。")
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

    private suspend fun startAndVerifyCompatibilityDisplay(id: String, nativeFailure: Throwable?) {
        try {
            Log.i(LIFECYCLE_LOG_TAG, "compatibility display start id=$id")
            commandClient.runBundledVncScript(
                script = vncScript,
                action = "start",
                arguments = listOf(id),
                timeout = COMPATIBILITY_START_TIMEOUT_MINUTES.minutes,
            )
            commandClient.runBundledVncScript(
                script = vncScript,
                action = "probe",
                arguments = listOf(id),
                timeout = 45.seconds,
            )
            Log.i(LIFECYCLE_LOG_TAG, "compatibility display probe ready id=$id")
        } catch (compatibilityFailure: Throwable) {
            if (compatibilityFailure is CancellationException) throw compatibilityFailure
            Log.e(LIFECYCLE_LOG_TAG, "compatibility display failed id=$id", compatibilityFailure)
            val compatibilityLogs = runCatching {
                commandClient.runInstalledVnc("logs", listOf("350"), 20.seconds)
            }.getOrDefault("")
            throw TermuxCommandException(
                buildString {
                    if (nativeFailure != null) {
                        append("ネイティブX11と互換X11の両方を起動できませんでした。")
                        nativeFailure.message?.takeIf { it.isNotBlank() }?.let {
                            append("\n\nNative X11:\n")
                            append(it.takeLast(3000))
                        }
                    } else {
                        append("互換X11表示を起動できませんでした。")
                    }
                    compatibilityFailure.message?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nCompatibility X11:\n")
                        append(it.takeLast(3000))
                    }
                    if (compatibilityLogs.isNotBlank()) {
                        append("\n\nCompatibility log:\n")
                        append(compatibilityLogs.takeLast(5000))
                    }
                },
                compatibilityFailure,
            )
        }
    }

    private suspend fun heartbeatDisplay(id: String): Boolean {
        return when (activeDisplayBackend()) {
            DesktopDisplayBackend.COMPATIBILITY_VNC -> heartbeatCompatibilityDisplay(id)
            DesktopDisplayBackend.NATIVE_X11 -> heartbeatNativeDisplay(id)
            null -> false
        }
    }

    private suspend fun heartbeatCompatibilityDisplay(id: String): Boolean {
        return try {
            commandClient.runInstalledVnc(
                action = "heartbeat",
                arguments = listOf(id),
                timeout = 60.seconds,
            )
            true
        } catch (heartbeatFailure: Throwable) {
            if (heartbeatFailure is CancellationException) throw heartbeatFailure
            try {
                // A dead VNC server also terminates its X clients. Recreate the worker after the
                // replacement :2 endpoint is ready instead of reusing a stale tmux session.
                commandClient.runInstalledHost(
                    action = "stop",
                    arguments = listOf(id, PRESERVE_CHROME_RESTORE),
                    timeout = 30.seconds,
                )
                closeCompatibilityDisplayAndWait()
                commandClient.runInstalledVnc("stop", timeout = 20.seconds)
                startAndVerifyCompatibilityDisplay(id, null)
                startAndProbeHost(id)
                setActiveSession(id, DesktopDisplayBackend.COMPATIBILITY_VNC)
                withContext(Dispatchers.Main.immediate) {
                    VncFallbackActivity.open(context)
                }
                true
            } catch (recoveryFailure: Throwable) {
                if (recoveryFailure is CancellationException) {
                    cleanupCancelledRecovery(id)
                    throw recoveryFailure
                }
                handleUnrecoverableDisplayFailure(id)
                false
            }
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
                try {
                    commandClient.runInstalledHost(
                        action = "stop",
                        arguments = listOf(id, PRESERVE_CHROME_RESTORE),
                        timeout = 30.seconds,
                    )
                    closeNativeDisplayAndWait()
                    EmbeddedX11ServiceController.stopAndWait(context)
                    startAndVerifyCompatibilityDisplay(id, nativeRecoveryFailure)
                    startAndProbeHost(id)
                    setActiveSession(id, DesktopDisplayBackend.COMPATIBILITY_VNC)
                    withContext(Dispatchers.Main.immediate) {
                        VncFallbackActivity.open(context)
                    }
                    true
                } catch (fallbackFailure: Throwable) {
                    if (fallbackFailure is CancellationException) {
                        cleanupCancelledRecovery(id)
                        throw fallbackFailure
                    }
                    handleUnrecoverableDisplayFailure(id)
                    false
                }
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
        commandClient.runInstalledHost(
            action = "probe",
            arguments = listOf(id),
            timeout = 45.seconds,
        )
        Log.i(LIFECYCLE_LOG_TAG, "host desktop probe ready id=$id")
    }

    private suspend fun ensureBundledDesktopApps(id: String) {
        Log.i(LIFECYCLE_LOG_TAG, "bundled desktop app provisioning start id=$id")
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
        runCatching { commandClient.runInstalledVnc("stop", timeout = 20.seconds) }
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
        repeat(NATIVE_X11_ACTIVITY_CLOSE_ATTEMPTS) {
            val open = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isOpen()
            }
            if (open) {
                stableClosedPolls = 0
                withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11Display.close(context)
                }
            } else {
                stableClosedPolls++
                if (stableClosedPolls >= DISPLAY_CLOSE_STABLE_POLLS) return
            }
            delay(NATIVE_X11_ACTIVITY_CLOSE_POLL_MILLIS)
        }
        throw TermuxCommandException(
            "内蔵X11 viewerのrendererを安全に終了できませんでした。表示サーバーの切り替えを中止します。",
        )
    }

    private suspend fun closeAllDisplaysAndWait() {
        closeNativeDisplayAndWait()
        closeCompatibilityDisplayAndWait()
    }

    private suspend fun closeCompatibilityDisplayAndWait() {
        withContext(Dispatchers.Main.immediate) {
            VncFallbackActivity.close()
        }
        var stableClosedPolls = 0
        repeat(COMPATIBILITY_ACTIVITY_CLOSE_ATTEMPTS) {
            val open = withContext(Dispatchers.Main.immediate) {
                VncFallbackActivity.isOpen()
            }
            if (open) {
                stableClosedPolls = 0
                withContext(Dispatchers.Main.immediate) {
                    VncFallbackActivity.close()
                }
            } else {
                stableClosedPolls++
                if (stableClosedPolls >= DISPLAY_CLOSE_STABLE_POLLS) return
            }
            delay(COMPATIBILITY_ACTIVITY_CLOSE_POLL_MILLIS)
        }
        throw TermuxCommandException(
            "互換VNC viewerを安全に終了できませんでした。表示サーバーの切り替えを中止します。",
        )
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

        private const val COMPATIBILITY_START_TIMEOUT_MINUTES = 12
        private const val COMPATIBILITY_VIEWER_OPEN_DELAY_MILLIS = 700L
        private const val COMPATIBILITY_ACTIVITY_CLOSE_POLL_MILLIS = 100L
        private const val COMPATIBILITY_ACTIVITY_CLOSE_ATTEMPTS = 50
        private const val DISPLAY_CLOSE_STABLE_POLLS = 10
        private const val LIFECYCLE_LOG_TAG = "LDFA-Lifecycle"
    }
}
