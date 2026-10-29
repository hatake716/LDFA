package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ContainerInfoParserTest {
    @Test
    fun parsesJapaneseUbuntuXfceRecord() {
        val name = encode("仕事用Ubuntu")
        val message = encode("インストール中です")
        val output = "ubuntu-a1\t$name\tinstalling\t42\t$message\t1\t1724000000\t1\txfce"

        val container = ContainerInfoParser.parse(output).single()

        assertEquals("ubuntu-a1", container.id)
        assertEquals("仕事用Ubuntu", container.name)
        assertEquals(DesktopEnvironment.XFCE, container.desktopEnvironment)
        assertEquals(ContainerState.INSTALLING, container.state)
        assertEquals(42, container.progress)
        assertEquals("インストール中です", container.message)
        assertEquals(1, container.displayNumber)
        assertTrue(container.sessionAlive)
        assertFalse(container.canStart)
        assertTrue(container.canStop)
    }

    @Test
    fun treatsLegacyAndUnknownDesktopRecordsAsXfce() {
        val legacy = "old\t${encode("Old")}\tready\t150\t${encode("Ready")}\t1\t1\t0"
        val unknown = "new\t${encode("New")}\tready\t100\t${encode("Ready")}\t1\t2\t0\tcinnamon"
        val output = "broken\n$legacy\n$unknown"

        val containers = ContainerInfoParser.parse(output)

        assertEquals(2, containers.size)
        assertTrue(containers.all { it.desktopEnvironment == DesktopEnvironment.XFCE })
        assertEquals(100, containers.first { it.id == "old" }.progress)
        assertTrue(containers.all { it.canStart })
        assertTrue(containers.none { it.canStop })
    }

    @Test
    fun parsesUnifiedDoctorReport() {
        val report = DoctorReport.parse(
            """
            version=0.3.0
            host_ready=1
            tmux=1
            proot_distro=1
            embedded_x11=1
            storage=1
            shared_directory=/data/data/com.termux/files/home/storage/shared/LinuxDesktop
            """.trimIndent(),
        )

        assertTrue(report.hostReady)
        assertTrue(report.tmuxReady)
        assertTrue(report.prootReady)
        assertTrue(report.x11CommandReady)
        assertTrue(report.storageReady)
        assertEquals("0.3.0", report.hostVersion)
    }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
    )
}
