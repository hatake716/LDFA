package com.hatake716.linuxdesktop.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.hatake716.linuxdesktop.BuildConfig
import com.hatake716.linuxdesktop.display.VncFallbackActivity
import com.hatake716.linuxdesktop.x11.EmbeddedX11ServiceController
import com.termux.app.EmbeddedTermuxRuntime
import com.termux.x11.EmbeddedX11Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            clearActiveSession()
            stopAllDisplayServers()
            closeAllDisplaysAndWait()

            val backend = selectAndStartDisplayBackend(id)

            try {
                commandClient.runBundledHostScript(
                    script = hostScript,
                    action = "start",
                    arguments = listOf(id),
                    timeout = 45.seconds,
                )

                if (backend == DesktopDisplayBackend.COMPATIBILITY_VNC) {
                    delay(COMPATIBILITY_VIEWER_OPEN_DELAY_MILLIS)
                    withContext(Dispatchers.Main.immediate) {
                        VncFallbackActivity.open(context)
                    }
                }

                setActiveSession(id, backend)
                backend
            } catch (throwable: Throwable) {
                clearActiveSession()
                runCatching {
                    commandClient.runInstalledHost(
                        action = "stop",
                        arguments = listOf(id),
                        timeout = 30.seconds,
                    )
                }
                stopAllDisplayServers()
                closeAllDisplaysAndWait()
                throw throwable
            }
        }
    }

    suspend fun stopContainer(id: String) = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            commandClient.runBundledHostScript(
                script = hostScript,
                action = "stop",
                arguments = listOf(id),
                timeout = 45.seconds,
            )
            stopAllDisplayServers()
            if (activeContainerId() == id) clearActiveSession()
            closeAllDisplaysAndWait()
        }
    }

    suspend fun deleteContainer(id: String, deleteSharedFiles: Boolean) = withContext(Dispatchers.IO) {
        x11LifecycleMutex.withLock {
            val wasActive = activeContainerId() == id
            if (wasActive) {
                stopAllDisplayServers()
                closeAllDisplaysAndWait()
                clearActiveSession()
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
            append("===== Ubuntu / XFCE =====\n")
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
        val displayBusy = if (id == null) {
            false
        } else {
            x11LifecycleMutex.withLock {
                heartbeatDisplay(id)
            }
        }

        val desktopBusy = runCatching {
            commandClient.runInstalledHost(
                action = "heartbeat",
                arguments = listOfNotNull(id),
                timeout = 25.seconds,
            ).lineSequence().any { it.trim() == "busy=1" }
        }.getOrDefault(false)

        displayBusy || desktopBusy
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
            EmbeddedX11ServiceController.stopAndWait(context)
            closeNativeDisplayAndWait()
            startAndVerifyCompatibilityDisplay(id, nativeFailure)
            DesktopDisplayBackend.COMPATIBILITY_VNC
        }
    }

    private suspend fun startAndVerifyNativeX11(id: String) {
        var lastFailure: Throwable? = null
        var lastLogs = ""

        for ((index, mode) in NATIVE_X11_RENDER_MODES.withIndex()) {
            try {
                runCatching { commandClient.runInstalledVnc("stop", timeout = 15.seconds) }
                EmbeddedX11ServiceController.restartAndWait(
                    context = context,
                    legacyDrawing = mode == NATIVE_X11_MODE_LEGACY,
                )
                commandClient.runBundledX11Script(
                    script = x11Script,
                    action = "probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )

                withContext(Dispatchers.Main.immediate) {
                    EmbeddedX11ServiceController.openDisplay(context)
                }
                if (!waitForNativeDisplayConnection()) {
                    throw TermuxCommandException(
                        "ネイティブX11表示は$mode描画でXサーバーへ接続しましたが、Android Surfaceへ実フレームを描画できませんでした。",
                    )
                }

                delay(NATIVE_X11_POST_ACTIVITY_STABILIZE_MILLIS)
                commandClient.runInstalledX11(
                    action = "probe",
                    arguments = listOf(id),
                    timeout = 35.seconds,
                )
                return
            } catch (throwable: Throwable) {
                lastFailure = throwable
                lastLogs = runCatching {
                    commandClient.runInstalledX11("logs", listOf("350"), 15.seconds)
                }.getOrDefault("")
                EmbeddedX11ServiceController.stopAndWait(context)
                closeNativeDisplayAndWait()
                if (index + 1 < NATIVE_X11_RENDER_MODES.size) {
                    delay(NATIVE_X11_RETRY_DELAY_MILLIS)
                }
            }
        }

        throw TermuxCommandException(
            buildString {
                append("ネイティブTermux:X11を通常描画・legacy描画の両方で安定起動できませんでした。互換表示へ切り替えます。")
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
        } catch (compatibilityFailure: Throwable) {
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
        } catch (_: Throwable) {
            runCatching { commandClient.runInstalledVnc("stop", timeout = 20.seconds) }
            try {
                startAndVerifyCompatibilityDisplay(id, null)
                setActiveSession(id, DesktopDisplayBackend.COMPATIBILITY_VNC)
                withContext(Dispatchers.Main.immediate) {
                    VncFallbackActivity.open(context)
                }
                true
            } catch (_: Throwable) {
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
            if (!EmbeddedX11ServiceController.isSocketReady()) {
                throw TermuxCommandException("X11サービスのUnix socketがありません。")
            }
            commandClient.runInstalledX11(
                action = "probe",
                arguments = listOf(id),
                timeout = 35.seconds,
            )
            if (viewerWasOpen && !waitForNativeDisplayConnection(NATIVE_X11_HEARTBEAT_VIEWER_ATTEMPTS)) {
                throw TermuxCommandException("X11サーバーは応答していますがAndroid表示Surfaceへの描画が停止しています。")
            }
            true
        } catch (nativeFailure: Throwable) {
            EmbeddedX11ServiceController.stopAndWait(context)
            closeNativeDisplayAndWait()

            val nativeRecovered = runCatching {
                if (viewerWasOpen) {
                    startAndVerifyNativeX11(id)
                } else {
                    restartNativeServerWithoutViewer(id)
                }
            }.isSuccess

            if (nativeRecovered) {
                setActiveSession(id, DesktopDisplayBackend.NATIVE_X11)
                true
            } else {
                try {
                    startAndVerifyCompatibilityDisplay(id, nativeFailure)
                    setActiveSession(id, DesktopDisplayBackend.COMPATIBILITY_VNC)
                    withContext(Dispatchers.Main.immediate) {
                        VncFallbackActivity.open(context)
                    }
                    true
                } catch (_: Throwable) {
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
        clearActiveSession()
        runCatching {
            commandClient.runInstalledHost(
                action = "stop",
                arguments = listOf(id),
                timeout = 30.seconds,
            )
        }
        stopAllDisplayServers()
        closeAllDisplaysAndWait()
    }

    private suspend fun stopAllDisplayServers() {
        EmbeddedX11ServiceController.stopAndWait(context)
        runCatching { commandClient.runInstalledVnc("stop", timeout = 20.seconds) }
    }

    private suspend fun waitForNativeDisplayConnection(
        attempts: Int = NATIVE_X11_ACTIVITY_WAIT_ATTEMPTS,
    ): Boolean {
        repeat(attempts) {
            val connected = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isConnected()
            }
            if (connected) return true
            delay(NATIVE_X11_ACTIVITY_POLL_MILLIS)
        }
        return false
    }

    private suspend fun closeNativeDisplayAndWait() {
        withContext(Dispatchers.Main.immediate) {
            EmbeddedX11Display.close(context)
        }
        repeat(NATIVE_X11_ACTIVITY_CLOSE_ATTEMPTS) {
            val open = withContext(Dispatchers.Main.immediate) {
                EmbeddedX11Display.isOpen()
            }
            if (!open) return
            delay(NATIVE_X11_ACTIVITY_CLOSE_POLL_MILLIS)
        }
    }

    private suspend fun closeAllDisplaysAndWait() {
        closeNativeDisplayAndWait()
        withContext(Dispatchers.Main.immediate) {
            VncFallbackActivity.close()
        }
        repeat(COMPATIBILITY_ACTIVITY_CLOSE_ATTEMPTS) {
            val open = withContext(Dispatchers.Main.immediate) {
                VncFallbackActivity.isOpen()
            }
            if (!open) return
            delay(COMPATIBILITY_ACTIVITY_CLOSE_POLL_MILLIS)
        }
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
            .ifBlank { "ubuntu-xfce" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$asciiPrefix-$suffix"
    }

    companion object {
        private val GLOBAL_DISPLAY_LIFECYCLE_MUTEX = Mutex()
        private const val PREFERENCES = "linux_desktop_preferences"
        private const val KEY_ACTIVE_CONTAINER = "active_container"
        private const val KEY_ACTIVE_BACKEND = "active_display_backend"
        private const val LIVE_LOG_LINE_COUNT = 40

        private const val NATIVE_X11_MODE_NORMAL = "normal"
        private const val NATIVE_X11_MODE_LEGACY = "legacy"
        private val NATIVE_X11_RENDER_MODES = listOf(NATIVE_X11_MODE_NORMAL, NATIVE_X11_MODE_LEGACY)
        private const val NATIVE_X11_ACTIVITY_POLL_MILLIS = 250L
        private const val NATIVE_X11_ACTIVITY_WAIT_ATTEMPTS = 60
        private const val NATIVE_X11_HEARTBEAT_VIEWER_ATTEMPTS = 12
        private const val NATIVE_X11_ACTIVITY_CLOSE_POLL_MILLIS = 100L
        private const val NATIVE_X11_ACTIVITY_CLOSE_ATTEMPTS = 30
        private const val NATIVE_X11_POST_ACTIVITY_STABILIZE_MILLIS = 750L
        private const val NATIVE_X11_RETRY_DELAY_MILLIS = 800L

        private const val COMPATIBILITY_START_TIMEOUT_MINUTES = 12
        private const val COMPATIBILITY_VIEWER_OPEN_DELAY_MILLIS = 700L
        private const val COMPATIBILITY_ACTIVITY_CLOSE_POLL_MILLIS = 100L
        private const val COMPATIBILITY_ACTIVITY_CLOSE_ATTEMPTS = 20
    }
}
