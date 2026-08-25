package com.hatake716.linuxdesktop.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Streams a FULL backup: walk the rootfs (and host metadata), tar → gzip → into
 * the output, computing the payload SHA-256 as it goes. No intermediate file, so
 * only one output file's worth of free space is needed (docs §6.1).
 *
 * Cancellation is cooperative and non-suspending: the caller passes a [cancelCheck]
 * that throws when the coroutine has been cancelled (the walk visitor is a plain
 * synchronous callback and cannot call suspend functions). Owner is forced to 0/0;
 * symlinks are kept as links; sockets/FIFOs/devices are skipped and counted (§6.4).
 */
class BackupWriter(
    private val rootfsDir: File,
    private val metadataDir: File?,
    private val exclusions: BackupExclusions,
) {
    data class Counts(val entryCount: Long, val uncompressedBytes: Long)

    data class Result(
        val payloadSha256: ByteArray,
        val payloadLength: Long,
        val entryCount: Long,
        val uncompressedBytes: Long,
        val skippedSpecial: Int,
        val unreadable: List<String>,
    )

    interface Progress {
        fun onProgress(processedEntries: Long, totalEntries: Long, processedBytes: Long)
    }

    /** Pass 1: count entries and real bytes (progress denominator + space check). */
    fun count(cancelCheck: () -> Unit): Counts {
        var entries = 0L
        var bytes = 0L
        walk(cancelCheck) { _, realPath, attrs, _ ->
            entries++
            if (attrs != null && attrs.isRegularFile) bytes += attrs.size()
        }
        metadataDir?.let { md ->
            md.walkTopDown().forEach { f ->
                cancelCheck()
                entries++
                if (f.isFile) bytes += f.length()
            }
        }
        entries++ // meta/manifest.json
        return Counts(entries, bytes)
    }

    /** Pass 2: write the payload. [manifestBytes] embeds the header as meta/manifest.json. */
    fun write(
        rawOut: OutputStream,
        manifestBytes: ByteArray,
        total: Counts,
        level: Int,
        cancelCheck: () -> Unit,
        progress: Progress?,
    ): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(rawOut)
        val digesting = DigestOutputStream(counting, digest)
        val gz = object : GZIPOutputStream(BufferedOutputStream(digesting, 1 shl 16)) {
            init { def.setLevel(level.coerceIn(0, Deflater.BEST_COMPRESSION)) }
        }
        val tar = Tar.Writer(gz)

        var processed = 0L
        var skippedSpecial = 0
        val unreadable = ArrayList<String>()
        var lastTick = 0L

        fun tick(force: Boolean) {
            val now = System.currentTimeMillis()
            if (force || now - lastTick >= 500) {
                lastTick = now
                progress?.onProgress(processed, total.entryCount, counting.count)
            }
        }

        putFileBytes(tar, "meta/manifest.json", manifestBytes, 0x1A4)
        processed++; tick(true)

        metadataDir?.let { md ->
            val base = md.toPath()
            md.walkTopDown().forEach { f ->
                cancelCheck()
                val rel = base.relativize(f.toPath()).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                val guest = "meta/host/$rel"
                if (f.isDirectory) {
                    tar.putEntry(Tar.Entry("$guest/", Tar.Type.DIR, 0, 0x1ED))
                } else if (f.isFile) {
                    putFileBytes(tar, guest, f.readBytes(), 0x1A4)
                }
                processed++; tick(false)
            }
        }

        walk(cancelCheck) { guestPath, realPath, attrs, linkTarget ->
            if (exclusions.isExcluded(guestPath, realPath)) return@walk
            val entryPath = "rootfs$guestPath" // guestPath starts with '/'
            when {
                linkTarget != null -> {
                    tar.putEntry(Tar.Entry(entryPath, Tar.Type.SYMLINK, 0, 0x1FF, linkTarget = linkTarget))
                }
                attrs != null && attrs.isDirectory -> {
                    tar.putEntry(Tar.Entry("$entryPath/", Tar.Type.DIR, 0, modeOf(realPath, 0x1ED)))
                }
                attrs != null && attrs.isRegularFile -> {
                    val declared = attrs.size()
                    val ok = runCatching {
                        tar.putEntry(Tar.Entry(entryPath, Tar.Type.FILE, declared, modeOf(realPath, 0x1A4)))
                        File(realPath).inputStream().use { ins ->
                            val buf = ByteArray(64 * 1024)
                            var copied = 0L
                            while (copied < declared) {
                                val r = ins.read(buf, 0, minOf(buf.size.toLong(), declared - copied).toInt())
                                if (r < 0) break
                                tar.writeContent(buf, 0, r)
                                copied += r
                            }
                            while (copied < declared) { // file shrank; pad to declared size
                                val padLen = minOf(declared - copied, buf.size.toLong()).toInt()
                                tar.writeContent(ByteArray(padLen), 0, padLen)
                                copied += padLen
                            }
                        }
                        tar.pad(declared)
                    }.isSuccess
                    if (!ok) {
                        unreadable.add(guestPath)
                        tar.pad(declared) // header already emitted; keep the archive well-formed
                    }
                }
                else -> {
                    skippedSpecial++ // socket / fifo / device / unknown
                    return@walk
                }
            }
            processed++; tick(false)
        }

        tar.finish()
        gz.finish()
        gz.flush()
        counting.flush()
        tick(true)

        return Result(
            payloadSha256 = digest.digest(),
            payloadLength = counting.count,
            entryCount = processed,
            uncompressedBytes = total.uncompressedBytes,
            skippedSpecial = skippedSpecial,
            unreadable = unreadable,
        )
    }

    private fun putFileBytes(tar: Tar.Writer, path: String, bytes: ByteArray, mode: Int) {
        tar.putEntry(Tar.Entry(path, Tar.Type.FILE, bytes.size.toLong(), mode))
        tar.writeContent(bytes, 0, bytes.size)
        tar.pad(bytes.size.toLong())
    }

    private inline fun walk(
        noinline cancelCheck: () -> Unit,
        crossinline visit: (guestPath: String, realPath: String, attrs: BasicFileAttributes?, linkTarget: String?) -> Unit,
    ) {
        val root = rootfsDir.toPath()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancelCheck()
                if (dir == root) return FileVisitResult.CONTINUE
                val guest = guestPathOf(root, dir)
                if (exclusions.isExcluded(guest, dir.toString())) return FileVisitResult.SKIP_SUBTREE
                visit(guest, dir.toString(), attrs, null)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancelCheck()
                val guest = guestPathOf(root, file)
                if (Files.isSymbolicLink(file)) {
                    val target = runCatching { Files.readSymbolicLink(file).toString() }.getOrDefault("")
                    visit(guest, file.toString(), null, target)
                } else {
                    visit(guest, file.toString(), attrs, null)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                val guest = guestPathOf(root, file)
                if (Files.isSymbolicLink(file)) {
                    val target = runCatching { Files.readSymbolicLink(file).toString() }.getOrDefault("")
                    visit(guest, file.toString(), null, target)
                } else {
                    visit(guest, file.toString(), null, null)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun guestPathOf(root: Path, p: Path): String {
        val rel = root.relativize(p).toString().replace('\\', '/')
        return "/$rel"
    }

    private fun modeOf(realPath: String, fallback: Int): Int {
        return try {
            val perms = Files.getPosixFilePermissions(File(realPath).toPath(), LinkOption.NOFOLLOW_LINKS)
            var m = 0
            if (PosixFilePermission.OWNER_READ in perms) m = m or 0x100
            if (PosixFilePermission.OWNER_WRITE in perms) m = m or 0x080
            if (PosixFilePermission.OWNER_EXECUTE in perms) m = m or 0x040
            if (PosixFilePermission.GROUP_READ in perms) m = m or 0x020
            if (PosixFilePermission.GROUP_WRITE in perms) m = m or 0x010
            if (PosixFilePermission.GROUP_EXECUTE in perms) m = m or 0x008
            if (PosixFilePermission.OTHERS_READ in perms) m = m or 0x004
            if (PosixFilePermission.OTHERS_WRITE in perms) m = m or 0x002
            if (PosixFilePermission.OTHERS_EXECUTE in perms) m = m or 0x001
            if (m == 0) fallback else m
        } catch (e: Exception) {
            fallback
        }
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
