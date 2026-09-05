package com.hatake716.linuxdesktop.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * End-to-end round-trip of the whole `.ldfa` pipeline in pure JVM: build a fake
 * rootfs + host metadata, run [BackupWriter] into an in-memory `.ldfa` (header +
 * payload + trailer, exactly as BackupEngine assembles it), then [BackupReader]
 * back out into a fresh tree and assert the tree matches. This is the test that
 * would have caught a broken extract path — the class BackupEngine wraps with
 * Android-only StatFs/host calls that can't run here.
 */
class BackupPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(entryCount: Long, bytes: Long) = BackupManifest(
        formatVersion = 1,
        createdAt = "2026-08-25T21:00:00+09:00",
        app = BackupManifest.AppInfo("1.0.2", 19, "com.termux"),
        sourceDevice = BackupManifest.SourceDevice("Pixel", 36, "arm64-v8a"),
        container = BackupManifest.ContainerInfo(
            "debian-xfce-a1b2c3d4", "Debian XFCE", "debian:12", "arm64",
            "/data/data/com.termux/files/usr",
        ),
        scope = BackupManifest.Scope.FULL,
        payload = BackupManifest.Payload("gzip", 6, "ustar+gnu", bytes, entryCount),
        encryption = null,
        includes = BackupManifest.Includes(rootfs = true, hostMetadata = true, androidShared = false),
        excludes = BackupExclusions().manifestExcludes(),
    )

    private fun writeLdfa(rootfs: File, meta: File?): ByteArray {
        val writer = BackupWriter(rootfs, meta, BackupExclusions())
        val counts = writer.count { }
        val m = manifest(counts.entryCount, counts.uncompressedBytes)
        val bos = ByteArrayOutputStream()
        BackupFormat.writeHeader(bos, m)
        val result = writer.write(
            rawOut = bos,
            manifestBytes = m.toJsonBytes(),
            total = counts,
            level = 6,
            cancelCheck = { },
            progress = null,
        )
        BackupFormat.writeTrailer(bos, result.payloadSha256, result.payloadLength)
        return bos.toByteArray()
    }

    private fun extract(ldfa: ByteArray, rootfsOut: File, metaOut: File) = runBlocking {
        // Mirror BackupEngine.restore: read trailer from the tail, header from the
        // head, then extract the payload that sits between them.
        val payloadLen = java.nio.ByteBuffer
            .wrap(ldfa, ldfa.size - 8, 8)
            .order(java.nio.ByteOrder.BIG_ENDIAN).long
        val trailerSha = ldfa.copyOfRange(ldfa.size - 40, ldfa.size - 8)

        val headerLen = ByteArrayInputStream(ldfa).use { headerIn ->
            val counting = object : java.io.FilterInputStream(headerIn) {
                var n = 0
                override fun read(): Int = super.read().also { if (it >= 0) n++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) n += it }
            }
            BackupFormat.readHeader(counting)
            counting.n
        }

        val payloadIn = ByteArrayInputStream(ldfa, headerLen, ldfa.size - headerLen)
        BackupReader().extract(
            payloadIn = payloadIn,
            payloadLength = payloadLen,
            expectedSha256 = trailerSha,
            rootfsOut = rootfsOut,
            metaOut = metaOut,
            total = 0,
            progress = null,
            sourceContainer = manifest(0, 0).container,
        )
    }

    @Test
    fun `restored PRoot hard links refer only to the new rootfs`() {
        val src = tmp.newFolder("linked-source")
        val locale = File(src, "usr/lib/locale").apply { mkdirs() }
        val oldRoot = BackupRootfsLinks.sourceRoots(manifest(0, 0).container).first()
        File(locale, ".l2s.locale.0000").writeText("日本語ロケールのデータ")
        Files.createSymbolicLink(File(locale, ".l2s.locale.0001").toPath(), File("$oldRoot/usr/lib/locale/.l2s.locale.0000").toPath())
        Files.createSymbolicLink(File(locale, "locale-archive").toPath(), File("$oldRoot/usr/lib/locale/.l2s.locale.0001").toPath())
        val archive = writeLdfa(src, null)
        val out = tmp.newFolder("linked-destination")
        extract(archive, out, tmp.newFolder("linked-meta"))
        src.deleteRecursively()
        assertEquals("日本語ロケールのデータ", File(out, "usr/lib/locale/locale-archive").readText())
        assertTrue(Files.readSymbolicLink(File(out, "usr/lib/locale/locale-archive").toPath()).toString().startsWith(out.canonicalPath + "/"))
    }

    @Test
    fun `round-trips files, subdirs, symlinks and host meta`() {
        val src = tmp.newFolder("rootfs")
        File(src, "etc").mkdirs()
        File(src, "etc/hostname").writeText("debian")
        File(src, "home/desktop").mkdirs()
        File(src, "home/desktop/notes.txt").writeText("こんにちは\n".repeat(100))
        File(src, "usr/bin").mkdirs()
        Files.createSymbolicLink(File(src, "usr/bin/python").toPath(), File("python3.11").toPath())

        val meta = tmp.newFolder("meta")
        File(meta, "name").writeText("Debian XFCE")
        File(meta, "scale").writeText("150")

        val ldfa = writeLdfa(src, meta)

        val outRootfs = tmp.newFolder("out-rootfs")
        val outMeta = tmp.newFolder("out-meta")
        extract(ldfa, outRootfs, outMeta)

        assertEquals("debian", File(outRootfs, "etc/hostname").readText())
        assertEquals("こんにちは\n".repeat(100), File(outRootfs, "home/desktop/notes.txt").readText())
        val link = File(outRootfs, "usr/bin/python").toPath()
        assertTrue("symlink not recreated", Files.isSymbolicLink(link))
        assertEquals("python3.11", Files.readSymbolicLink(link).toString())
        // Host meta lands under meta/host/*.
        assertEquals("150", File(outMeta, "host/scale").readText())
        assertEquals("Debian XFCE", File(outMeta, "host/name").readText())
    }

    @Test
    fun `excludes volatile trees like proc and mnt-android`() {
        val src = tmp.newFolder("rootfs2")
        File(src, "etc").mkdirs()
        File(src, "etc/keep").writeText("keep")
        File(src, "proc").mkdirs()
        File(src, "proc/should-not-appear").writeText("x")
        File(src, "mnt/android").mkdirs()
        File(src, "mnt/android/host-file").writeText("shared")
        File(src, "tmp").mkdirs()
        File(src, "tmp/scratch").writeText("temp")

        val ldfa = writeLdfa(src, null)
        val outRootfs = tmp.newFolder("out-rootfs2")
        val outMeta = tmp.newFolder("out-meta2")
        extract(ldfa, outRootfs, outMeta)

        assertTrue(File(outRootfs, "etc/keep").exists())
        assertFalse("proc must be excluded", File(outRootfs, "proc/should-not-appear").exists())
        assertFalse("/mnt/android must be excluded", File(outRootfs, "mnt/android/host-file").exists())
        assertFalse("/tmp must be excluded", File(outRootfs, "tmp/scratch").exists())
    }

    @Test
    fun `large file content survives round-trip byte-for-byte`() {
        val src = tmp.newFolder("rootfs3")
        // NB: not "/data" — that is an excluded Android mount point. Use a real
        // Debian path so the payload is actually backed up.
        File(src, "var/lib/app").mkdirs()
        val payload = ByteArray(2_500_000) { (it * 31 + 7).toByte() }
        File(src, "var/lib/app/blob.bin").writeBytes(payload)

        val ldfa = writeLdfa(src, null)
        val outRootfs = tmp.newFolder("out-rootfs3")
        extract(ldfa, outRootfs, tmp.newFolder("out-meta3"))

        assertArrayEquals(payload, File(outRootfs, "var/lib/app/blob.bin").readBytes())
    }

    @Test
    fun `filenames containing dot-dot round-trip and are not treated as traversal`() {
        val src = tmp.newFolder("rootfs5")
        File(src, "var/lib/foo").mkdirs()
        // ".." embedded in a name is legitimate (Debian packages/caches have these)
        // and must survive; earlier a bare contains("..") aborted the whole restore.
        File(src, "var/lib/foo/pkg..old").writeText("keep-me")
        File(src, "var/lib/foo/a..b..c").writeText("also-keep")

        val ldfa = writeLdfa(src, null)
        val outRootfs = tmp.newFolder("out-rootfs5")
        extract(ldfa, outRootfs, tmp.newFolder("out-meta5"))

        assertEquals("keep-me", File(outRootfs, "var/lib/foo/pkg..old").readText())
        assertEquals("also-keep", File(outRootfs, "var/lib/foo/a..b..c").readText())
    }

    @Test
    fun `existing-env Android dirs are excluded and an unreadable dir does not abort`() {
        val src = tmp.newFolder("rootfs4")
        File(src, "etc").mkdirs()
        File(src, "etc/hostname").writeText("debian")
        // Android host mount-point dirs an existing environment carries.
        File(src, "apex/com.android.art").mkdirs()
        File(src, "apex/com.android.art/marker").writeText("android")
        File(src, "system/bin").mkdirs()
        File(src, "system/bin/linker").writeText("android")
        File(src, "linkerconfig").mkdirs()
        File(src, "linkerconfig/ld.config.txt").writeText("cfg")
        // An unreadable directory OUTSIDE the exclusion list must not abort the walk.
        val locked = File(src, "opt/locked")
        locked.mkdirs()
        File(locked, "secret").writeText("x")
        val madeUnreadable = locked.setReadable(false, false)

        val ldfa = writeLdfa(src, null)
        val outRootfs = tmp.newFolder("out-rootfs4")
        extract(ldfa, outRootfs, tmp.newFolder("out-meta4"))

        // Debian content survives; Android mount points are gone.
        assertTrue(File(outRootfs, "etc/hostname").exists())
        assertFalse(File(outRootfs, "apex/com.android.art/marker").exists())
        assertFalse(File(outRootfs, "system/bin/linker").exists())
        assertFalse(File(outRootfs, "linkerconfig/ld.config.txt").exists())
        // The key guarantee is simply that writeLdfa+extract completed without
        // throwing despite the unreadable dir. Restore read permission for cleanup.
        if (madeUnreadable) locked.setReadable(true, false)
    }
    @Test
    fun `corrupted checksum is rejected`() {
        val src = tmp.newFolder("corruption-source")
        File(src, "important.txt").writeText("preserve this data")
        val archive = writeLdfa(src, null)
        archive[archive.size - 40] = (archive[archive.size - 40].toInt() xor 1).toByte()
        org.junit.Assert.assertThrows(BackupFormatException::class.java) {
            extract(archive, tmp.newFolder("corrupt-out"), tmp.newFolder("corrupt-meta"))
        }
    }

    @Test
    fun `truncated archive cannot report a successful restore`() {
        val src = tmp.newFolder("truncation-source")
        File(src, "important.txt").writeText("preserve this data")
        val archive = writeLdfa(src, null)
        val truncated = archive.copyOf(archive.size - 24)
        org.junit.Assert.assertThrows(Exception::class.java) {
            extract(truncated, tmp.newFolder("truncated-out"), tmp.newFolder("truncated-meta"))
        }
    }

}
