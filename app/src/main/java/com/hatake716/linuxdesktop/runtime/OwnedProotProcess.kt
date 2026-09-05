package com.hatake716.linuxdesktop.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

/** A PRoot and only the guest processes traced by that PRoot. */
internal class OwnedProotProcess(private val delegate: Process) : Process() {
    companion object {
        fun start(builder: ProcessBuilder): Process {
            val child = builder.start()
            return try { OwnedProotProcess(child) } catch (failure: Throwable) {
                child.destroy()
                throw failure
            }
        }
    }
    // Android's public Process API has no pid(), and destroyForcibly() falls
    // back to destroy()/SIGTERM. AOSP exposes the PID in toString(). Treat it
    // only as a candidate: validate app ownership and start time through procfs.
    private val pid = Regex("\\bpid=(\\d+)").find(delegate.toString())
        ?.groupValues?.get(1)?.toIntOrNull()
        ?: error("PRootのプロセスIDを確認できませんでした。")
    private val identity = readIdentity(pid)
        ?.takeIf { it.uid == AndroidProcess.myUid() && it.parent == AndroidProcess.myPid() }
        ?: error("PRootのプロセス所有者を確認できませんでした。")

    override fun getOutputStream() = delegate.outputStream
    override fun getInputStream() = delegate.inputStream
    override fun getErrorStream() = delegate.errorStream
    override fun waitFor() = delegate.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
    override fun exitValue() = delegate.exitValue()
    override fun isAlive() = delegate.isAlive
    override fun destroy() { destroyForcibly() }

    @Synchronized
    override fun destroyForcibly(): Process {
        if (!delegate.isAlive) return this
        check(readIdentity(pid) == identity) { "PRootの所有者が変わったため停止を中断しました。" }
        // Freeze the tracer before enumeration: its tracees cannot complete new
        // fork/clone events while it is stopped. Daemonized guests still carry
        // TracerPid even after their PPid becomes 1.
        signal(pid, OsConstants.SIGSTOP)
        try {
            val tracees = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
                val child = dir.name.toIntOrNull() ?: return@mapNotNull null
                val status = readStatus(child) ?: return@mapNotNull null
                if (status.uid == identity.uid && status.tracer == pid) child else null
            }
            for (child in tracees) {
                val status = readStatus(child)
                if (status?.uid == identity.uid && status.tracer == pid) signal(child, OsConstants.SIGKILL)
            }
            if (delegate.isAlive && readIdentity(pid) == identity) signal(pid, OsConstants.SIGKILL)
        } finally {
            // If enumeration failed, never strand a live, frozen tracer.
            if (delegate.isAlive && readIdentity(pid) == identity) signal(pid, OsConstants.SIGCONT)
        }
        check(delegate.waitFor(5, TimeUnit.SECONDS)) { "Linuxの実行処理を停止できませんでした。" }
        delegate.outputStream.runCatching { close() }
        delegate.inputStream.runCatching { close() }
        delegate.errorStream.runCatching { close() }
        return this
    }

    private fun signal(target: Int, signal: Int) {
        try { Os.kill(target, signal) }
        catch (e: android.system.ErrnoException) { if (e.errno != OsConstants.ESRCH) throw e }
    }

    private data class Status(val uid: Int, val parent: Int, val tracer: Int)
    private data class Identity(val uid: Int, val parent: Int, val startTime: String)

    private fun readStatus(target: Int): Status? = runCatching {
        val fields = File("/proc/$target/status").readLines().associate {
            it.substringBefore(':') to it.substringAfter(':').trim().split(Regex("\\s+"))[0]
        }
        Status(fields.getValue("Uid").toInt(), fields.getValue("PPid").toInt(), fields.getValue("TracerPid").toInt())
    }.getOrNull()

    private fun readIdentity(target: Int): Identity? = runCatching {
        val status = readStatus(target) ?: return null
        // Fields after comm's closing ')' start at field 3; starttime is field 22.
        val fields = File("/proc/$target/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
        Identity(status.uid, status.parent, fields[19])
    }.getOrNull()
}
