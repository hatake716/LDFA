package com.hatake716.linuxdesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.hatake716.linuxdesktop.data.DesktopDisplayBackend
import com.hatake716.linuxdesktop.data.LinuxDesktopRepository
import com.hatake716.linuxdesktop.data.ProcessExitDiagnostics
import com.hatake716.linuxdesktop.service.DesktopKeepAliveService
import com.hatake716.linuxdesktop.x11.EmbeddedX11ServiceController
import com.termux.app.TermuxApplication
import com.termux.x11.EmbeddedX11Display
import com.termux.x11.MainActivity as X11MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import com.termux.app.EmbeddedBootstrapInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

data class InstallationProgress(
    val busy: Boolean = false,
    val phase: Int = 0,
    val message: String = "",
    val error: String? = null,
)

class LinuxDesktopApplication : TermuxApplication() {
    val repository: LinuxDesktopRepository by lazy { LinuxDesktopRepository(this) }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSessionStart: Pair<String, Deferred<DesktopDisplayBackend>>? = null
    private var viewerResumeRecovery: Job? = null
    private var installationJob: Job? = null
    private var installationStopJob: Job? = null
    private val _installation = MutableStateFlow(InstallationProgress())
    val installation = _installation.asStateFlow()
    val preparingInstallation: Boolean get() = _installation.value.busy

    /** User-initiated work belongs to the application, including the rootfs request wait. */
    @Synchronized
    fun installLinux(name: String, prepareOnly: Boolean = false) {
        if (installationStopJob?.isActive == true || installationJob?.isActive == true || (!prepareOnly && name.isBlank())) return
        repository.allowInstallationResume()
        _installation.value = InstallationProgress(true, 1, "アプリ内の実行環境を準備しています")
        DesktopKeepAliveService.start(this)
        installationJob = sessionScope.launch {
            try {
                check(prepareOnly || filesDir.usableSpace >= 5L * 1024 * 1024 * 1024) {
                    "Linuxの導入には空き容量が5GB以上必要です。空き容量を増やして再試行してください。"
                }
                runInterruptible { EmbeddedBootstrapInstaller.install(this@LinuxDesktopApplication) }
                _installation.value = InstallationProgress(true, 2, "Linuxの導入準備を確認しています")
                repository.bootstrapHost()
                if (prepareOnly) {
                    _installation.value = InstallationProgress()
                    return@launch
                }
                _installation.value = InstallationProgress(true, 3, "Debianをダウンロードしています")
                repository.createContainer(name)
                _installation.value = InstallationProgress(message = "Linuxの構築を開始しました")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { repository.pauseInstallations() }
                throw cancelled
            } catch (failure: Exception) {
                _installation.value = InstallationProgress(error = failure.message ?: "Linuxの導入に失敗しました")
            }
        }
    }

    @Synchronized
    fun resumeInstallations(force: Boolean = false) {
        if (installationStopJob?.isActive == true || installationJob?.isActive == true) return
        if (force) repository.allowInstallationResume()
        if (repository.installationsPaused()) return
        _installation.value = InstallationProgress(true, 3, "中断したLinuxの導入を再開しています")
        DesktopKeepAliveService.start(this)
        installationJob = sessionScope.launch {
            try {
                if (force) repository.repairInterruptedWork()
                repository.resumeInterruptedInstalls(repository.listContainers(), force)
                _installation.value = InstallationProgress()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { repository.pauseInstallations() }
                throw cancelled
            } catch (failure: Exception) {
                _installation.value = InstallationProgress(error = failure.message ?: "導入を再開できませんでした")
            }
        }
    }

    /** Stop only installation work; preserve downloaded data and require an explicit resume. */
    @Synchronized
    fun stopLinuxInstallation(): Job {
        installationStopJob?.takeIf { it.isActive }?.let { return it }
        val pending = installationJob
        val operation = sessionScope.launch {
            _installation.value = InstallationProgress(true, 3, "Linuxの準備を停止しています")
            try {
                pending?.cancelAndJoin()
                repository.pauseInstallations()
                _installation.value = InstallationProgress(message = "Linuxの準備を停止しました。導入操作または修復から再開できます。")
            } catch (failure: Exception) {
                _installation.value = InstallationProgress(error = failure.message ?: "準備を停止できませんでした")
            }
        }
        installationStopJob = operation
        return operation
    }

    /**
     * The X11 viewer is a separate Activity. Keep the launch transaction owned by the Application
     * so recreating the management Activity (rotation, low-memory or "Don't keep activities") does
     * not cancel it halfway between server startup and XFCE startup.
     */
    @Synchronized
    fun startDesktopSession(id: String): Deferred<DesktopDisplayBackend> {
        pendingSessionStart?.takeIf { it.second.isActive }?.let { pending ->
            check(pending.first == id) { "別のLinuxデスクトップを起動処理中です。" }
            return pending.second
        }

        DesktopKeepAliveService.start(this, id)
        val operation = sessionScope.async {
            val backend = repository.startContainer(id)
            DesktopKeepAliveService.start(this@LinuxDesktopApplication, id)
            backend
        }
        pendingSessionStart = id to operation
        operation.invokeOnCompletion {
            synchronized(this@LinuxDesktopApplication) {
                if (pendingSessionStart?.second === operation) pendingSessionStart = null
            }
        }
        return operation
    }

    /**
     * Android creates the same Application class in the dedicated :x11 process. Running the full
     * TermuxApplication startup there would unlink and re-bind the main process' termux-am socket.
     * The framework has already attached the base Context; the X11 service needs no Termux shell
     * manager, bootstrap or command socket in its isolated process.
     */
    @SuppressLint("MissingSuperCall")
    override fun onCreate() {
        // Route data-dir execs through the native-library proot on Play/targetSdk-35
        // builds (W^X). No effect when the proot native libs are absent (targetSdk 28).
        runCatching {
            val runtime = com.hatake716.linuxdesktop.runtime.ProotRuntime.from(this)
            val termuxLib = File(filesDir, "usr/lib")
            com.hatake716.linuxdesktop.runtime.ProotExecRewriter.register(runtime, termuxLib)
        }
        if (currentProcessName() == "$packageName:x11") return
        super.onCreate()

        // The dedicated :x11 process and its native Xorg thread can survive Android reclaiming
        // this main process while Gmail is foreground. Restore volatile launch state only after
        // validating that the persisted service generation still owns both X11 endpoints.
        if (repository.activeDisplayBackend() == DesktopDisplayBackend.NATIVE_X11) {
            EmbeddedX11ServiceController.restoreDisplayAccess(this)
        }
        registerX11ViewerLifecycle()

        sessionScope.launch {
            ProcessExitDiagnostics.recordHistoricalExits(this@LinuxDesktopApplication)
        }
    }

    private fun registerX11ViewerLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is X11MainActivity) return

                // Activity.onCreate first gets a chance to consume the Binder retained in the
                // task Intent. If Android discarded that Binder, bind to the verified live service
                // on the next main-loop turn. The repository health operation below independently
                // verifies the Linux clients, so a live Xorg process can never mask dead XFCE.
                if (!EmbeddedX11Display.isTransportConnected()) {
                    activity.window.decorView.post {
                        if (!activity.isFinishing && !EmbeddedX11Display.isTransportConnected()) {
                            EmbeddedX11ServiceController.openDisplay(this@LinuxDesktopApplication)
                        }
                    }
                }
                scheduleViewerResumeRecovery()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    @Synchronized
    private fun scheduleViewerResumeRecovery() {
        if (viewerResumeRecovery?.isActive == true) return
        val operation = sessionScope.launch {
            repository.recoverActiveDesktopAfterViewerResume()
        }
        viewerResumeRecovery = operation
        operation.invokeOnCompletion {
            synchronized(this@LinuxDesktopApplication) {
                if (viewerResumeRecovery === operation) viewerResumeRecovery = null
            }
        }
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return runCatching {
            File("/proc/self/cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .substringBefore('\u0000')
        }.getOrNull()
    }
}
