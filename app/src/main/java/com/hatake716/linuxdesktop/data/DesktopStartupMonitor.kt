package com.hatake716.linuxdesktop.data

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DesktopStartupProgress(
    val containerId: String = "",
    val containerName: String = "",
    val busy: Boolean = false,
    val phase: String = "",
    val percent: Int = 0,
    val logs: String = "",
    val error: String? = null,
) {
    val visible: Boolean get() = busy || error != null
}

/** Application-owned progress survives management/viewer Activity recreation. */
class DesktopStartupMonitor(private val filesDir: File) {
    private var startedAtNanos = 0L
    private val mutableProgress = MutableStateFlow(DesktopStartupProgress())
    val progress = mutableProgress.asStateFlow()

    fun begin(id: String, name: String) {
        startedAtNanos = System.nanoTime()
        mutableProgress.value = DesktopStartupProgress(id, name, busy = true)
        phase(DesktopStartupStage.PREPARING)
    }

    fun dismissFailure() {
        mutableProgress.update { if (it.busy) it else DesktopStartupProgress() }
    }

    private fun append(text: String) {
        if (text.isBlank()) return
        mutableProgress.update {
            it.copy(logs = LiveLogFormatter.format(it.logs + "\n" + text, 160).takeLast(24_000))
        }
    }

    private fun phase(stage: DesktopStartupStage, completed: Boolean = false) {
        mutableProgress.update {
            it.copy(phase = stage.message, percent = if (completed) 100 else
                maxOf(it.percent, stage.percent.coerceAtMost(99)))
        }
        val elapsedTenths = (System.nanoTime() - startedAtNanos) / 100_000_000
        append("[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] " +
            "${progress.value.percent}% (+${elapsedTenths / 10}.${elapsedTenths % 10}秒) ${stage.message}")
    }

    /** Called in the Application's IO scope; log reads never launch competing shell commands. */
    suspend fun <T> track(start: suspend ((DesktopStartupStage) -> Unit) -> T): T = coroutineScope {
        val id = progress.value.containerId
        val base = "home/.local/share/linux-desktop-for-android"
        val files = listOf(
            "Linux" to File(filesDir, "$base/logs/$id.log"),
            "X11" to File(filesDir, "$base/logs/x11-server.log"),
            "XFCE" to File(filesDir, "usr/tmp/runtime-desktop/xfce-components.log"),
            "アプリ設定" to File(filesDir, "usr/var/lib/proot-distro/containers/$id/rootfs/root/.ldfa-apps.log"),
        )
        val readers = files.mapNotNull { (label, file) ->
            runCatching { label to StartupLogReader(file) }.getOrElse {
                append("[$label] ログを読み取れません: ${it.message}")
                null
            }
        }
        val unreadable = mutableSetOf<String>()
        fun sample() {
            readers.forEach { (label, reader) ->
                runCatching { reader.read() }.onSuccess { text ->
                    if (text.isNotBlank()) append("[$label]\n$text")
                    unreadable.remove(label)
                }.onFailure {
                    if (unreadable.add(label)) append("[$label] ログを読み取れません: ${it.message}")
                }
            }
        }
        val sampler = launch {
            while (isActive) {
                sample()
                delay(1_000)
            }
        }
        try {
            val result = start { phase(it) }
            phase(DesktopStartupStage.READY, completed = true)
            result
        } catch (failure: Throwable) {
            val message = if (failure is CancellationException) "起動が中断されました" else
                failure.message ?: "デスクトップを起動できませんでした"
            append(message)
            mutableProgress.update { it.copy(error = message, phase = "起動できませんでした") }
            throw failure
        } finally {
            withContext(NonCancellable) {
                sampler.cancelAndJoin()
                sample()
                mutableProgress.update { it.copy(busy = false) }
            }
        }
    }
}
