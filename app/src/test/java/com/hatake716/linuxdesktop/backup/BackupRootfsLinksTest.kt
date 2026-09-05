package com.hatake716.linuxdesktop.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRootfsLinksTest {
    private val container = BackupManifest.ContainerInfo("debian-test", "Test", "debian:12", "arm64", "/data/data/com.termux/files/usr")
    private val destination = File("/new/rootfs")

    @Test fun `rebases legacy modern XDG and Android alias paths`() {
        val roots = BackupRootfsLinks.sourceRoots(container)
        assertEquals(6, roots.size)
        roots.forEach { root ->
            assertEquals("/new/rootfs/lib/.l2s.data.0001", BackupRootfsLinks.rebase("$root/lib/.l2s.data.0001", roots, destination))
        }
    }

    @Test fun `preserves guest relative links and links outside the source container`() {
        val roots = BackupRootfsLinks.sourceRoots(container)
        listOf("../lib", "/usr/bin/bash", roots.first() + "-other/lib").forEach {
            assertEquals(it, BackupRootfsLinks.rebase(it, roots, destination))
        }
    }

    @Test(expected = BackupFormatException::class)
    fun `rejects a rebased target escaping the destination`() {
        val roots = BackupRootfsLinks.sourceRoots(container)
        BackupRootfsLinks.rebase(roots.first() + "/../../outside", roots, destination)
    }
}
