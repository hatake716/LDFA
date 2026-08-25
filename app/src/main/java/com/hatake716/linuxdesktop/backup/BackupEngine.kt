package com.hatake716.linuxdesktop.backup

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.hatake716.linuxdesktop.BuildConfig
import com.hatake716.linuxdesktop.data.TermuxCommandClient
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * Orchestrates a FULL backup and a restore, tying together [BackupPaths],
 * [BackupWriter]/[BackupReader], and [RestorePlanner]. Called from
 * BackupService on an app-scoped coroutine; progress is reported through the
 * supplied listener. Cancellation and error both delete the half-written output.
 */
class BackupEngine(
    private val context: Context,
    private val paths: BackupPaths = BackupPaths(context),
    private val commandClient: TermuxCommandClient = TermuxCommandClient(context),
) {
    sealed interface Phase {
        object Counting : Phase
        data class Writing(val processed: Long, val total: Long, val bytes: Long) : Phase
        data class Extracting(val processed: Long, val total: Long) : Phase
    }

    fun interface ProgressListener { fun onPhase(phase: Phase) }

    class BackupError(message: String) : Exception(message)

    data class BackupOutput(
        val file: File,
        val sizeBytes: Long,
        val sha256Prefix: String,
        val skippedSpecial: Int,
        val unreadableCount: Int,
    )

    // ---- create ------------------------------------------------------------

    suspend fun createFull(id: String, outputDir: File, listener: ProgressListener?): BackupOutput {
        if (!paths.isStopped(id)) {
            throw BackupError("バックアップの前に環境を停止してください。")
        }
        val rootfs = paths.rootfsDir(id)
            ?: throw BackupError("この環境のデータが見つかりません。")
        val metaDir = paths.metaDir(id).takeIf { it.isDirectory }

        val displayName = paths.displayName(id)
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val finalName = BackupFormat.safeFileName(displayName, timestamp, BackupManifest.Scope.FULL, false)
        outputDir.mkdirs()
        val finalFile = File(outputDir, finalName)
        val partFile = File(outputDir, finalName + BackupFormat.PART_SUFFIX)

        val exclusions = BackupExclusions(outputRealSubtree = outputDir.canonicalPath)
        val writer = BackupWriter(rootfs, metaDir, exclusions)
        // Capture the Job once; Job.isActive is non-suspending so the walk visitor
        // (a plain callback) can poll it for cooperative cancellation.
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancelCheck: () -> Unit = {
            if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
        }

        listener?.onPhase(Phase.Counting)
        val counts = writer.count(cancelCheck)
        coroutineContext.ensureActive()

        requireFreeSpace(outputDir, (counts.uncompressedBytes * 0.6).toLong())

        val manifest = buildManifest(id, displayName, counts)

        try {
            partFile.outputStream().use { rawFos ->
                val out = BufferedOutputStream(rawFos, 1 shl 16)
                BackupFormat.writeHeader(out, manifest)
                val result = writer.write(
                    rawOut = out,
                    manifestBytes = manifest.toJsonBytes(),
                    total = counts,
                    level = 6,
                    cancelCheck = cancelCheck,
                    progress = object : BackupWriter.Progress {
                        override fun onProgress(processedEntries: Long, totalEntries: Long, processedBytes: Long) {
                            listener?.onPhase(Phase.Writing(processedEntries, totalEntries, processedBytes))
                        }
                    },
                )
                BackupFormat.writeTrailer(out, result.payloadSha256, result.payloadLength)
                out.flush()
                if (!partFile.renameTo(finalFile)) {
                    // Cross-filesystem rename can fail; copy+delete as a fallback is
                    // undesirable for GBs, so surface it instead.
                    throw BackupError("保存先へ書き込めませんでした。別の保存先を選んでください。")
                }
                return BackupOutput(
                    file = finalFile,
                    sizeBytes = finalFile.length(),
                    sha256Prefix = result.payloadSha256.take(8).joinToString("") { "%02x".format(it) },
                    skippedSpecial = result.skippedSpecial,
                    unreadableCount = result.unreadable.size,
                )
            }
        } catch (t: Throwable) {
            partFile.delete()
            finalFile.takeIf { it.exists() && t !is BackupError }?.delete()
            throw t
        }
    }

    // ---- restore -----------------------------------------------------------

    data class RestoreResult(val newId: String, val displayName: String)

    suspend fun restore(
        input: File,
        existingDisplayNames: Set<String>,
        listener: ProgressListener?,
    ): RestoreResult {
        // 1. Header + trailer read.
        val manifest: BackupManifest
        val payloadLen: Long
        val trailerSha: ByteArray
        val headerBytes: Int
        RandomAccessFile(input, "r").use { raf ->
            val len = raf.length()
            if (len < BackupFormat.TRAILER_LEN + 10) throw BackupFormatException("ファイルが壊れています。")
            raf.seek(len - BackupFormat.TRAILER_LEN)
            trailerSha = ByteArray(32).also { raf.readFully(it) }
            val lenBytes = ByteArray(8).also { raf.readFully(it) }
            payloadLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).long
        }
        input.inputStream().use { ins ->
            val counting = CountingInputStream(BufferedInputStream(ins))
            manifest = BackupFormat.readHeader(counting)
            headerBytes = counting.count.toInt()
        }

        // 2. Validate.
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val guestArch = manifest.container.guestArch
        if (!abiMatchesArch(deviceAbi, guestArch)) {
            throw BackupError("このバックアップは $guestArch 用です。この端末（$deviceAbi）には復元できません。")
        }

        // 3. Plan a fresh container.
        val planner = RestorePlanner(paths)
        val displayName = planner.uniqueDisplayName(
            manifest.container.displayName.ifBlank { "Debian XFCE" },
            existingDisplayNames,
        )
        val plan = planner.plan(displayName)

        // The container dir doesn't exist yet; check free space on filesDir, which
        // is the same filesystem and always present (StatFs rejects missing paths).
        requireFreeSpace(context.filesDir, (manifest.payload.uncompressedBytes * 1.05).toLong())

        // 4. Extract (rootfs + metadata) with hash verification.
        try {
            input.inputStream().use { ins ->
                val bin = BufferedInputStream(ins)
                // Skip magic+header we already parsed, to reach the payload start.
                skipFully(bin, headerBytes.toLong())
                val reader = BackupReader()
                reader.extract(
                    payloadIn = bin,
                    payloadLength = payloadLen,
                    expectedSha256 = trailerSha,
                    rootfsOut = plan.rootfsDir,
                    metaOut = plan.metaDir,
                    total = manifest.payload.entryCount,
                    progress = object : BackupReader.Progress {
                        override fun onProgress(processedEntries: Long, totalEntries: Long) {
                            listener?.onPhase(Phase.Extracting(processedEntries, totalEntries))
                        }
                    },
                )
            }

            // 5. Register the environment: rewrite the container id in the restored
            //    metadata and give it a fresh shared folder + display name.
            finalizeMetadata(plan, displayName)
            plan.sharedDir.mkdirs()

            // 6. Guest-side cleanup (machine-id, Chrome Singleton, clear provisioning).
            runCatching {
                commandClient.runInstalledHost(
                    action = "restore-cleanup",
                    arguments = listOf(plan.newId),
                    timeout = 5.minutes,
                )
            }
            return RestoreResult(plan.newId, displayName)
        } catch (t: Throwable) {
            // Never leave a half-written environment in the list.
            plan.rootfsDir.deleteRecursively()
            plan.metaDir.deleteRecursively()
            throw t
        }
    }

    /** Rewrite meta so the restored container has the new id, name, and a clean state. */
    private fun finalizeMetadata(plan: RestorePlanner.Plan, displayName: String) {
        val meta = plan.metaDir
        meta.mkdirs()
        // Overwrite identity + volatile keys so cmd_list shows a ready, installed
        // environment; keep user prefs (scale/keyboard/timezone) carried in the
        // backup. cmd_list enumerates $META_ROOT/*, so these keys mirror cmd_create.
        writeMeta(meta, "name", displayName)
        writeMeta(meta, "desktop", "xfce")
        writeMeta(meta, "state", "ready")
        writeMeta(meta, "progress", "100")
        writeMeta(meta, "message", "")
        writeMeta(meta, "installed", "1")
        writeMeta(meta, "display", File(meta, "display").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { "1" } ?: "1")
        // audio + provisioning are re-derived on first boot by restore-cleanup.
        writeMeta(meta, "audio_ready", "")
        writeMeta(meta, "apps_provisioned", "")
        if (!File(meta, "distribution").exists()) writeMeta(meta, "distribution", "debian")
        if (!File(meta, "image").exists()) writeMeta(meta, "image", "debian:12")
        if (!File(meta, "created_at").exists()) writeMeta(meta, "created_at", (System.currentTimeMillis() / 1000).toString())
    }

    private fun writeMeta(dir: File, key: String, value: String) {
        val tmp = File(dir, ".$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(File(dir, key))
    }

    private fun buildManifest(id: String, displayName: String, counts: BackupWriter.Counts): BackupManifest {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return BackupManifest(
            formatVersion = BackupManifest.FORMAT_VERSION,
            createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            app = BackupManifest.AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, "com.termux"),
            sourceDevice = BackupManifest.SourceDevice(Build.MODEL ?: "Android", Build.VERSION.SDK_INT, abi),
            container = BackupManifest.ContainerInfo(
                id = id,
                displayName = displayName,
                distro = paths.distro(id),
                guestArch = archOf(abi),
                prefix = "/data/data/com.termux/files/usr",
            ),
            scope = BackupManifest.Scope.FULL,
            payload = BackupManifest.Payload(
                codec = "gzip",
                level = 6,
                tarFormat = "ustar+gnu",
                uncompressedBytes = counts.uncompressedBytes,
                entryCount = counts.entryCount,
            ),
            encryption = null,
            includes = BackupManifest.Includes(rootfs = true, hostMetadata = true, androidShared = false),
            excludes = BackupExclusions().manifestExcludes(),
        )
    }

    private fun requireFreeSpace(dir: File, needBytes: Long) {
        // StatFs throws IllegalArgumentException("Invalid path: …") on a missing
        // directory, so resolve to the nearest existing ancestor first. If even
        // that can't be stat'd, skip the check rather than abort the operation.
        val probe = existingAncestor(dir) ?: return
        val free = runCatching { StatFs(probe.absolutePath).availableBytes }.getOrNull() ?: return
        if (free < needBytes) {
            throw BackupError(
                "空き容量が不足しています。約 ${humanMb(needBytes)} の空きが必要です（現在 ${humanMb(free)}）。",
            )
        }
    }

    private fun existingAncestor(dir: File): File? {
        var cur: File? = dir.absoluteFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        return cur
    }

    private fun humanMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }

    private fun archOf(abi: String): String = when {
        abi.startsWith("arm64") -> "arm64"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("armeabi") -> "armhf"
        abi == "x86" -> "i386"
        else -> abi
    }

    private fun abiMatchesArch(abi: String, arch: String): Boolean = archOf(abi) == arch

    private fun skipFully(input: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("truncated backup")
            remaining -= r
        }
    }

    private class CountingInputStream(private val src: java.io.InputStream) : java.io.InputStream() {
        var count = 0L; private set
        override fun read(): Int = src.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) count += it }
    }
}
