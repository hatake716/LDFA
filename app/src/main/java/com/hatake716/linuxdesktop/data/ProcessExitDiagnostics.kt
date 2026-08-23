package com.hatake716.linuxdesktop.data

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Persists process-death evidence that remains available after Android restarts LDFA.
 *
 * Chrome, Debian, XFCE and the embedded X server share LDFA's Linux UID even though only the
 * Android main and :x11 processes are represented by [ApplicationExitInfo].  The current UID RSS
 * summary therefore complements the historical Android exit reason without recording command
 * lines, page URLs or account data.
 */
internal object ProcessExitDiagnostics {
    private const val TAG = "LDFA-ProcessExit"
    private const val PREFERENCES = "ldfa_process_exit_diagnostics"
    private const val LAST_RECORDED_TIMESTAMP = "last_recorded_timestamp"
    private const val MAX_HISTORICAL_EXITS = 20
    private const val MAX_PERSISTED_EXITS = 200
    private const val MAX_DISPLAY_CHARACTERS = 64 * 1024
    private const val DIAGNOSTICS_DIRECTORY = "ldfa-diagnostics"
    private const val EXIT_LOG = "process-exits.log"

    fun recordHistoricalExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching { recordHistoricalExitsApi30(context) }
            .onFailure { Log.w(TAG, "Unable to record historical process exits", it) }
    }

    fun report(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java)
        val systemMemory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val javaUsedKiB = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        val uidProcesses = sameUidProcessMemory(Process.myUid())
        val exitLog = diagnosticsFile(context).takeIf(File::isFile)
            ?.readText()
            ?.replace('\u0000', '\n')
            ?.takeLast(MAX_DISPLAY_CHARACTERS)
            ?.trim()
            .orEmpty()

        return buildString {
            appendLine("captured_at=${Instant.now()}")
            appendLine("system_total_kib=${systemMemory.totalMem / 1024}")
            appendLine("system_available_kib=${systemMemory.availMem / 1024}")
            appendLine("system_low_memory_threshold_kib=${systemMemory.threshold / 1024}")
            appendLine("system_low_memory=${systemMemory.lowMemory}")
            appendLine("android_low_memory_kill_report_supported=${lowMemoryKillReportSupport()}")
            appendLine("android_main_pss_kib=${Debug.getPss()}")
            appendLine("java_heap_used_kib=$javaUsedKiB")
            appendLine("java_heap_max_kib=${runtime.maxMemory() / 1024}")
            appendLine("same_uid_processes=${uidProcesses.count}")
            appendLine("same_uid_rss_kib=${uidProcesses.rssKiB}")
            appendLine("same_uid_swap_kib=${uidProcesses.swapKiB}")
            appendLine("same_uid_names=${uidProcesses.names.joinToString(",")}")
            appendLine()
            appendLine("historical_android_process_exits:")
            append(exitLog.ifBlank {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "記録済みのAndroidプロセス終了情報はありません。"
                } else {
                    "ApplicationExitInfoはAndroid 11以降で利用できます。"
                }
            })
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun recordHistoricalExitsApi30(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastTimestamp = preferences.getLong(LAST_RECORDED_TIMESTAMP, 0L)
        val exits = manager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_HISTORICAL_EXITS,
        ).filter { it.timestamp > lastTimestamp }
            .sortedWith(compareBy<ApplicationExitInfo> { it.timestamp }.thenBy { it.pid })

        if (exits.isEmpty()) return

        val destination = diagnosticsFile(context)
        destination.parentFile?.mkdirs()
        val existing = destination.takeIf(File::isFile)?.readText().orEmpty()
        val merged = mergeExitLog(existing, exits.map(::formatExit))
        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()
        try {
            output.write(merged.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
        preferences.edit()
            .putLong(LAST_RECORDED_TIMESTAMP, exits.maxOf { it.timestamp })
            .apply()
    }

    /** Cleans interrupted legacy appends and keeps the private diagnostics file bounded. */
    internal fun mergeExitLog(existing: String, newEntries: List<String>): String {
        val lines = (
            existing.replace('\u0000', '\n').lineSequence() +
                newEntries.asSequence().flatMap { it.replace('\u0000', '\n').lineSequence() }
            )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
            .takeLast(MAX_PERSISTED_EXITS)
        return lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n")
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun formatExit(exit: ApplicationExitInfo): String {
        val description = exit.description
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(240)
            .orEmpty()
        return buildString {
            append("time=${Instant.ofEpochMilli(exit.timestamp)}")
            append(" process=${exit.processName}")
            append(" pid=${exit.pid}")
            append(" reason=${reasonName(exit.reason)}(${exit.reason})")
            append(" status=${exit.status}")
            append(" importance=${exit.importance}")
            append(" pss_kib=${exit.pss}")
            append(" rss_kib=${exit.rss}")
            if (description.isNotBlank()) append(" description=$description")
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "java-crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "platform-reason"
    }

    private fun lowMemoryKillReportSupport(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActivityManager.isLowMemoryKillReportSupported().toString()
        } else {
            "unavailable"
        }

    private fun diagnosticsFile(context: Context): File =
        File(File(context.filesDir, DIAGNOSTICS_DIRECTORY), EXIT_LOG)

    private fun sameUidProcessMemory(uid: Int): UidProcessMemorySummary {
        val processes = File("/proc").listFiles()
            ?.asSequence()
            ?.filter { entry -> entry.name.all(Char::isDigit) }
            ?.mapNotNull { entry ->
                runCatching { parseProcStatus(File(entry, "status").readText(), uid) }.getOrNull()
            }
            ?.toList()
            .orEmpty()

        return UidProcessMemorySummary(
            count = processes.size,
            rssKiB = processes.sumOf(UidProcessMemory::rssKiB),
            swapKiB = processes.sumOf(UidProcessMemory::swapKiB),
            names = processes.map(UidProcessMemory::name).filter(String::isNotBlank).distinct().sorted(),
        )
    }

    internal fun parseProcStatus(status: String, expectedUid: Int): UidProcessMemory? {
        val fields = status.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1).trim()
            }
            .toMap()
        fun firstToken(value: String?): String? =
            value?.takeWhile { character -> !character.isWhitespace() }?.takeIf(String::isNotEmpty)

        val uid = firstToken(fields["Uid"])?.toIntOrNull() ?: return null
        if (uid != expectedUid) return null

        fun memoryKiB(name: String): Long = firstToken(fields[name])?.toLongOrNull() ?: 0L

        return UidProcessMemory(
            name = fields["Name"].orEmpty(),
            rssKiB = memoryKiB("VmRSS"),
            swapKiB = memoryKiB("VmSwap"),
            pid = firstToken(fields["Pid"])?.toIntOrNull() ?: 0,
            parentPid = firstToken(fields["PPid"])?.toIntOrNull() ?: 0,
        )
    }
}

internal data class UidProcessMemory(
    val name: String,
    val rssKiB: Long,
    val swapKiB: Long,
    val pid: Int = 0,
    val parentPid: Int = 0,
)

private data class UidProcessMemorySummary(
    val count: Int,
    val rssKiB: Long,
    val swapKiB: Long,
    val names: List<String>,
)
