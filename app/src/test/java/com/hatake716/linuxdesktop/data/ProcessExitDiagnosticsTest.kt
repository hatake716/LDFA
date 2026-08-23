package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessExitDiagnosticsTest {
    @Test
    fun parsesOnlyProcessesOwnedByTheApplicationUid() {
        val status = """
            Name: chrome
            Pid: 1234
            PPid: 1200
            Uid: 10234 10234 10234 10234
            VmRSS: 245760 kB
            VmSwap: 32768 kB
        """.trimIndent()

        assertEquals(
            UidProcessMemory(
                name = "chrome",
                rssKiB = 245760,
                swapKiB = 32768,
                pid = 1234,
                parentPid = 1200,
            ),
            ProcessExitDiagnostics.parseProcStatus(status, expectedUid = 10234),
        )
        assertNull(ProcessExitDiagnostics.parseProcStatus(status, expectedUid = 10235))
    }

    @Test
    fun acceptsMissingOptionalMemoryFields() {
        val status = """
            Name: com.termux
            Uid: 10123 10123 10123 10123
        """.trimIndent()

        assertEquals(
            UidProcessMemory(name = "com.termux", rssKiB = 0, swapKiB = 0),
            ProcessExitDiagnostics.parseProcStatus(status, expectedUid = 10123),
        )
    }

    @Test
    fun cleansInterruptedExitLogAndDeduplicatesEntries() {
        assertEquals(
            "time=one\ntime=two\n",
            ProcessExitDiagnostics.mergeExitLog(
                existing = "time=one\n\u0000\u0000",
                newEntries = listOf("time=two", "time=two"),
            ),
        )
    }
}
