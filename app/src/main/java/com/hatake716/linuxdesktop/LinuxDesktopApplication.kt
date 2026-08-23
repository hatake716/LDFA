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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class LinuxDesktopApplication : TermuxApplication() {
    val repository: LinuxDesktopRepository by lazy { LinuxDesktopRepository(this) }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSessionStart: Pair<String, Deferred<DesktopDisplayBackend>>? = null
    private var viewerResumeRecovery: Job? = null

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
