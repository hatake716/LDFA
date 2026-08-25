package com.hatake716.linuxdesktop.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupFormatTest {

    private fun sampleManifest() = BackupManifest(
        formatVersion = 1,
        createdAt = "2026-08-25T21:00:00+09:00",
        app = BackupManifest.AppInfo("1.0.2", 19, "com.termux"),
        sourceDevice = BackupManifest.SourceDevice("Pixel 10a", 36, "arm64-v8a"),
        container = BackupManifest.ContainerInfo(
            id = "debian-xfce-a1b2c3d4",
            displayName = "Debian XFCE",
            distro = "debian:12",
            guestArch = "arm64",
            prefix = "/data/data/com.termux/files/usr",
        ),
        scope = BackupManifest.Scope.FULL,
        payload = BackupManifest.Payload("gzip", 6, "ustar", 5368709120L, 214893L),
        encryption = null,
        includes = BackupManifest.Includes(rootfs = true, hostMetadata = true, androidShared = false),
        excludes = listOf("/proc/*", "/sys/*"),
    )

    @Test
    fun manifestRoundTrips() {
        val m = sampleManifest()
        val back = BackupManifest.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(m.container.id, back.container.id)
        assertEquals(m.container.guestArch, back.container.guestArch)
        assertEquals(m.scope, back.scope)
        assertEquals(m.payload.uncompressedBytes, back.payload.uncompressedBytes)
        assertEquals(m.payload.entryCount, back.payload.entryCount)
        assertNull(back.encryption)
        assertEquals(m.excludes, back.excludes)
    }

    @Test
    fun manifestIgnoresUnknownFields() {
        val json = sampleManifest().toJson()
        json.put("future_field", "whatever")
        json.getJSONObject("container").put("new_key", 1)
        val back = BackupManifest.fromJson(json) // must not throw
        assertEquals("debian-xfce-a1b2c3d4", back.container.id)
    }

    @Test
    fun nonLdfaJsonIsRejected() {
        try {
            BackupManifest.fromJson(JSONObject("""{"format":"something-else"}"""))
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) { /* ok */ }
    }

    @Test
    fun headerFramingRoundTrips() {
        val m = sampleManifest()
        val out = ByteArrayOutputStream()
        BackupFormat.writeHeader(out, m)
        val back = BackupFormat.readHeader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(m.container.displayName, back.container.displayName)
    }

    @Test
    fun wrongMagicIsRejected() {
        val bytes = "NOPEx".toByteArray() + ByteArray(64)
        try {
            BackupFormat.readHeader(ByteArrayInputStream(bytes))
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) { /* ok */ }
    }

    @Test
    fun newerFormatVersionIsRejected() {
        val bytes = byteArrayOf('L'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), 0x02) +
            ByteArray(64)
        try {
            BackupFormat.readHeader(ByteArrayInputStream(bytes))
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("新しい"))
        }
    }

    @Test
    fun fileNameSanitizationKeepsJapaneseAndCapsLength() {
        assertEquals("Debian_XFCE", BackupFormat.sanitizeName("Debian XFCE"))
        assertEquals("日本語環境", BackupFormat.sanitizeName("日本語環境"))
        assertEquals("test_env", BackupFormat.sanitizeName("test/env"))
        // Leading/trailing junk trimmed, runs collapsed.
        assertEquals("a_b", BackupFormat.sanitizeName("///a???b///"))
        // Length cap 40.
        val long = "x".repeat(60)
        assertTrue(BackupFormat.sanitizeName(long).length <= 40)
        // Blank falls back.
        assertEquals("Debian", BackupFormat.sanitizeName("///"))
    }

    @Test
    fun fileNameComposition() {
        val name = BackupFormat.safeFileName("Debian XFCE", "20260825-2100", BackupManifest.Scope.FULL, false)
        assertEquals("LDFA-Debian_XFCE-20260825-2100.ldfa", name)
        val enc = BackupFormat.safeFileName("Debian XFCE", "20260825-2100", BackupManifest.Scope.DATA, true)
        assertEquals("LDFA-Debian_XFCE-20260825-2100-data-enc.ldfa", enc)
    }

    @Test
    fun exclusionsCoverTheCriticalCases() {
        val ex = BackupExclusions(outputRealSubtree = "/storage/emulated/0/LinuxDesktop/backups")
        // Runtime + mount + cache subtrees.
        assertTrue(ex.isExcluded("/proc"))
        assertTrue(ex.isExcluded("/proc/1/status"))
        assertTrue(ex.isExcluded("/mnt/android/photo.jpg"))
        assertTrue(ex.isExcluded("/tmp/x"))
        assertTrue(ex.isExcluded("/var/cache/apt/archives/foo.deb"))
        assertTrue(ex.isExcluded("/home/desktop/.cache/anything"))
        // Android host mount-point dirs proot creates in the rootfs (existing
        // environments have the full set; these are unreadable and not Debian data).
        assertTrue(ex.isExcluded("/apex"))
        assertTrue(ex.isExcluded("/apex/com.android.art/lib64"))
        assertTrue(ex.isExcluded("/system/bin/linker"))
        assertTrue(ex.isExcluded("/vendor/lib"))
        assertTrue(ex.isExcluded("/linkerconfig/ld.config.txt"))
        assertTrue(ex.isExcluded("/data/local/tmp"))
        assertTrue(ex.isExcluded("/sdcard/DCIM"))
        // But a Debian dir that merely shares a prefix must NOT be excluded.
        assertFalse(ex.isExcluded("/systemd-thing"))
        assertFalse(ex.isExcluded("/var/lib/dpkg/status"))
        // Chrome cache under a profile, but keep other profile files.
        assertTrue(ex.isExcluded("/home/desktop/.config/google-chrome/Default/Cache/x"))
        assertTrue(ex.isExcluded("/home/desktop/.config/google-chrome/Default/Service Worker/CacheStorage/y"))
        assertFalse(ex.isExcluded("/home/desktop/.config/google-chrome/Default/Cookies"))
        // Singleton locks anywhere.
        assertTrue(ex.isExcluded("/home/desktop/.config/google-chrome/SingletonLock"))
        // Self-recursion: the output file's own subtree, matched by real path.
        assertTrue(ex.isExcluded("/mnt/android/backups/LDFA.part", "/storage/emulated/0/LinuxDesktop/backups/LDFA.part"))
        // A normal home file survives.
        assertFalse(ex.isExcluded("/home/desktop/.bashrc"))
        assertFalse(ex.isExcluded("/home/desktop/Documents/report.txt"))
    }

    @Test
    fun tarRoundTripsPathsLinksAndContent() {
        val out = ByteArrayOutputStream()
        val w = Tar.Writer(out)
        // A dir, a file, a symlink, and a long UTF-8 path.
        w.putEntry(Tar.Entry("home/", Tar.Type.DIR, 0, 0x1ED))
        val content = "hello world".toByteArray()
        w.putEntry(Tar.Entry("home/greeting.txt", Tar.Type.FILE, content.size.toLong(), 0x1A4))
        w.writeContent(content, 0, content.size); w.pad(content.size.toLong())
        w.putEntry(Tar.Entry("home/link", Tar.Type.SYMLINK, 0, 0x1FF, linkTarget = "greeting.txt"))
        val longName = "home/" + "ディレクトリ/".repeat(12) + "日本語ファイル名.txt"
        w.putEntry(Tar.Entry(longName, Tar.Type.FILE, 3, 0x1A4))
        w.writeContent(byteArrayOf(1, 2, 3), 0, 3); w.pad(3)
        w.finish()

        val r = Tar.Reader(ByteArrayInputStream(out.toByteArray()))
        val e1 = r.next()!!; assertEquals("home/", e1.path); assertEquals(Tar.Type.DIR, e1.type)
        val e2 = r.next()!!; assertEquals("home/greeting.txt", e2.path); assertEquals(Tar.Type.FILE, e2.type)
        val got = ByteArrayOutputStream(); r.copyContentTo(got, e2.size)
        assertArrayEquals(content, got.toByteArray())
        val e3 = r.next()!!; assertEquals(Tar.Type.SYMLINK, e3.type); assertEquals("greeting.txt", e3.linkTarget)
        val e4 = r.next()!!; assertEquals(longName, e4.path); assertEquals(3L, e4.size)
        r.skipContent(e4.size)
        assertNull(r.next())
    }
}
