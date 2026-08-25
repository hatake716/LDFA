package com.hatake716.linuxdesktop.backup

import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Reads a `.ldfa` backup: header inspection for the restore preview, and the
 * verified extraction of the payload (docs §7). Extraction refuses path
 * traversal, ignores stored ownership (extracts as the current uid), restores
 * permission bits, and re-checks the trailer SHA-256.
 */
class BackupReader {

    /** Read only the header (magic + JSON) without touching the payload. */
    fun readManifest(input: InputStream): BackupManifest = BackupFormat.readHeader(input)

    interface Progress {
        fun onProgress(processedEntries: Long, totalEntries: Long)
    }

    data class ExtractResult(val entryCount: Long, val hostMetaDir: File?)

    /**
     * Extract the payload that begins at the CURRENT position of [payloadIn] into
     * [rootfsOut] (guest rootfs) and [metaOut] (containers/<id>/). Computes the
     * payload SHA-256 while reading and compares it to [expectedSha256]; throws
     * [BackupFormatException] on mismatch. The caller removes half-written dirs.
     *
     * @param expectedSha256 the trailer digest, read from the file's tail beforehand.
     */
    suspend fun extract(
        payloadIn: InputStream,
        payloadLength: Long,
        expectedSha256: ByteArray,
        rootfsOut: File,
        metaOut: File,
        total: Long,
        progress: Progress?,
    ): ExtractResult {
        val digest = MessageDigest.getInstance("SHA-256")
        // Only the payload bytes (payloadLength) participate in the digest; the
        // trailer that follows must not be read as gzip.
        val bounded = BoundedInputStream(payloadIn, payloadLength)
        val digesting = DigestInputStream(bounded, digest)
        val gz = GZIPInputStream(BufferedInputStream(digesting, 1 shl 16))
        val tar = Tar.Reader(gz)

        rootfsOut.mkdirs()
        metaOut.mkdirs()
        val rootfsCanon = rootfsOut.canonicalFile
        val metaCanon = metaOut.canonicalFile

        var processed = 0L
        var lastTick = 0L
        fun tick(force: Boolean) {
            val now = System.currentTimeMillis()
            if (force || now - lastTick >= 500) {
                lastTick = now
                progress?.onProgress(processed, total)
            }
        }

        while (true) {
            coroutineContext.ensureActive()
            val e = tar.next() ?: break
            val dest = resolveDest(e.path, rootfsCanon, metaCanon)
            if (dest == null) {
                // meta/manifest.json is embedded for cross-check; skip its content.
                if (e.type == Tar.Type.FILE) tar.skipContent(e.size)
                processed++; continue
            }
            when (e.type) {
                Tar.Type.DIR -> {
                    dest.mkdirs()
                    applyMode(dest, e.mode)
                }
                Tar.Type.SYMLINK -> {
                    dest.parentFile?.mkdirs()
                    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) dest.delete()
                    runCatching { Files.createSymbolicLink(dest.toPath(), File(e.linkTarget).toPath()) }
                }
                Tar.Type.FILE -> {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { tar.copyContentTo(it, e.size) }
                    applyMode(dest, e.mode)
                }
            }
            processed++; tick(false)
        }

        // Drain any remaining payload bytes so the digest covers the whole payload.
        drainToEnd(gz)
        tick(true)

        val actual = digest.digest()
        if (!actual.contentEquals(expectedSha256)) {
            throw BackupFormatException("バックアップファイルが壊れています。転送し直してからお試しください。")
        }
        val hostMeta = File(metaOut, "host").takeIf { it.isDirectory }
        return ExtractResult(processed, hostMeta)
    }

    /**
     * Map a tar entry path to a destination file, refusing traversal. Returns null
     * for meta/manifest.json (embedded, verified separately).
     */
    private fun resolveDest(path: String, rootfsCanon: File, metaCanon: File): File? {
        val clean = path.replace('\\', '/')
        if (clean.contains("..") || clean.startsWith("/")) {
            throw BackupFormatException("バックアップファイルが壊れています。")
        }
        return when {
            clean == "meta/manifest.json" -> null
            clean.startsWith("rootfs/") -> under(rootfsCanon, clean.removePrefix("rootfs/"))
            clean.startsWith("meta/host/") -> under(metaCanon, "host/" + clean.removePrefix("meta/host/"))
            else -> null // shared/* and unknown prefixes are ignored in Phase 1/2
        }
    }

    private fun under(base: File, rel: String): File {
        val f = File(base, rel)
        val canon = f.canonicalFile
        if (canon != base && !canon.path.startsWith(base.path + File.separator)) {
            throw BackupFormatException("バックアップファイルが壊れています。")
        }
        return f
    }

    private fun applyMode(f: File, mode: Int) {
        runCatching {
            val perms = HashSet<PosixFilePermission>()
            if (mode and 0x100 != 0) perms.add(PosixFilePermission.OWNER_READ)
            if (mode and 0x080 != 0) perms.add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0x040 != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0x020 != 0) perms.add(PosixFilePermission.GROUP_READ)
            if (mode and 0x010 != 0) perms.add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0x008 != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0x004 != 0) perms.add(PosixFilePermission.OTHERS_READ)
            if (mode and 0x002 != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0x001 != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
            if (perms.isNotEmpty()) Files.setPosixFilePermissions(f.toPath(), perms)
        }
    }

    private fun drainToEnd(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) { /* discard */ }
    }

    /** Reads at most [limit] bytes from [src], then reports EOF. */
    private class BoundedInputStream(private val src: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            if (read >= limit) return -1
            val b = src.read()
            if (b >= 0) read++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= limit) return -1
            val want = minOf(len.toLong(), limit - read).toInt()
            val n = src.read(b, off, want)
            if (n > 0) read += n
            return n
        }
    }
}
