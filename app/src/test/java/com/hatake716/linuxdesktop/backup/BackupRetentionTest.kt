package com.hatake716.linuxdesktop.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupRetentionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun retainedArchiveSurvivesStagingCleanup() {
        val staging = tmp.newFolder("staging")
        val source = File(staging, "desktop.ldfa").apply { writeText("archive payload") }
        val result = BackupExport.retain(source, File(tmp.root, "backups"))
        source.delete()
        staging.deleteRecursively()
        assertEquals("archive payload", result.readText())
    }

    @Test fun nameCollisionPreservesBothArchives() {
        val source = tmp.newFile("desktop.ldfa").apply { writeText("new archive") }
        val destination = tmp.newFolder("backups")
        File(destination, source.name).writeText("previous archive")
        assertThrows(java.io.IOException::class.java) { BackupExport.retain(source, destination) }
        assertEquals("new archive", source.readText())
        assertEquals("previous archive", File(destination, source.name).readText())
    }

    @Test fun missingOrInstallingMetadataNeverAllowsBackup() {
        val paths = BackupPaths(tmp.root)
        assertFalse(paths.isStopped("desktop"))
        paths.metaDir("desktop").mkdirs()
        File(paths.metaDir("desktop"), "installed").writeText("1")
        for (state in listOf("queued", "installing", "starting", "running", "stopping", "unknown")) {
            File(paths.metaDir("desktop"), "state").writeText(state)
            assertFalse(state, paths.isStopped("desktop"))
        }
        File(paths.metaDir("desktop"), "state").writeText("ready")
        assertTrue(paths.isStopped("desktop"))
    }

    @Test fun xdgRootfsIsIncludedInBackupLookup() {
        val root = File(tmp.root, "home/.local/share/proot-distro/containers/desktop/rootfs")
        File(root, "etc").mkdirs()
        assertEquals(root, BackupPaths(tmp.root).rootfsDir("desktop"))
    }
}
