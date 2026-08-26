package com.hatake716.linuxdesktop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun libDirWithProot(present: Boolean): File {
        val dir = tmp.newFolder("nativeLib")
        if (present) {
            for (name in listOf("libproot.so", "libproot-loader.so")) {
                File(dir, name).apply { writeText("#!"); setExecutable(true) }
            }
        }
        return dir
    }

    private fun runtime(present: Boolean) =
        ProotRuntime(libDirWithProot(present), tmp.newFolder("proot-tmp"))

    @Test
    fun `passes the command through unchanged when proot is absent`() {
        val rt = runtime(present = false)
        assertFalse(rt.available)
        val cmd = listOf("/data/user/0/app/files/usr/bin/bash", "-lc", "echo hi")
        assertEquals(cmd, rt.wrap(cmd))
        assertTrue(rt.environment().isEmpty())
    }

    @Test
    fun `prepends proot and preserves the original command when available`() {
        val libDir = libDirWithProot(present = true)
        val rt = ProotRuntime(libDir, tmp.newFolder("proot-tmp"))
        assertTrue(rt.available)

        val cmd = listOf("/files/usr/bin/bash", "-lc", "id")
        val wrapped = rt.wrap(cmd)

        assertEquals(File(libDir, "libproot.so").absolutePath, wrapped[0])
        assertEquals("--kill-on-exit", wrapped[1])
        // original command is the tail, unmodified and in order
        assertEquals(cmd, wrapped.takeLast(cmd.size))
    }

    @Test
    fun `inserts rootfs and binds in proot flag form`() {
        val libDir = libDirWithProot(present = true)
        val rootfs = tmp.newFolder("rootfs")
        val rt = ProotRuntime(libDir, tmp.newFolder("proot-tmp"))

        val wrapped = rt.wrap(
            command = listOf("/bin/true"),
            rootfs = rootfs,
            binds = listOf("/data/lib:/usr/lib", "/host/x:/guest/x"),
        )

        // -r <rootfs> present
        val r = wrapped.indexOf("-r")
        assertTrue(r > 0)
        assertEquals(rootfs.absolutePath, wrapped[r + 1])
        // each bind becomes a -b <spec> pair
        val firstB = wrapped.indexOf("-b")
        assertEquals("/data/lib:/usr/lib", wrapped[firstB + 1])
        assertEquals(2, wrapped.count { it == "-b" })
        // command still last
        assertEquals("/bin/true", wrapped.last())
    }

    @Test
    fun `environment carries the loader, tmp and lib path when available`() {
        val libDir = libDirWithProot(present = true)
        val tmpDir = tmp.newFolder("proot-tmp")
        val rt = ProotRuntime(libDir, tmpDir)
        val env = rt.environment()
        assertEquals(File(libDir, "libproot-loader.so").absolutePath, env["PROOT_LOADER"])
        assertEquals(libDir.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals(tmpDir.absolutePath, env["PROOT_TMP_DIR"])
    }

    @Test
    fun `empty command is returned as-is`() {
        val rt = runtime(present = true)
        assertEquals(emptyList<String>(), rt.wrap(emptyList()))
    }
}
