package com.hatake716.linuxdesktop

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.hatake716.linuxdesktop.data.DesktopDisplayBackend
import com.hatake716.linuxdesktop.data.LinuxDesktopRepository
import com.hatake716.linuxdesktop.service.DesktopKeepAliveService
import com.termux.app.TermuxApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

class LinuxDesktopApplication : TermuxApplication() {
    val repository: LinuxDesktopRepository by lazy { LinuxDesktopRepository(this) }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSessionStart: Pair<String, Deferred<DesktopDisplayBackend>>? = null

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
