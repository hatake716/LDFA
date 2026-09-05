package com.hatake716.linuxdesktop.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupLogReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun excludesEarlierRunsAndReadsTruncatedLogsFromTheBeginning() {
        val file = temporary.newFile().apply { writeText("old failure must not appear\n") }
        val reader = StartupLogReader(file)
        assertEquals("", reader.read())
        file.appendText("new startup\n")
        assertEquals("new startup\n", reader.read())
        assertEquals("", reader.read())
        file.writeText("reset\n")
        assertEquals("reset\n", reader.read())
    }

    @Test fun preservesJapaneseCharactersSplitAcrossWrites() {
        val file = temporary.newFile()
        val reader = StartupLogReader(file)
        val bytes = "日本語の起動ログ\n".toByteArray()
        file.appendBytes(bytes.copyOfRange(0, 2))
        assertEquals("", reader.read())
        file.appendBytes(bytes.copyOfRange(2, bytes.size))
        assertEquals("日本語の起動ログ\n", reader.read())
    }

    @Test fun readsAReplacementFileEvenWhenItIsLargerThanThePreviousFile() {
        val file = temporary.newFile().apply { writeText("old\n") }
        val reader = StartupLogReader(file)
        val replacement = temporary.newFile().apply { writeText("replacement starts here\n") }
        java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals("replacement starts here\n", reader.read())
    }

    @Test fun boundsLargeOutputAndHandlesFilesCreatedAfterStartup() {
        val file = File(temporary.root, "created-later.log")
        val reader = StartupLogReader(file)
        assertEquals("", reader.read())
        file.writeText((1..10_000).joinToString("\n", postfix = "\n") { "line $it" })
        val output = reader.read()
        assertTrue(output.length < 33_000)
        assertTrue(output.endsWith("line 10000\n"))
        assertFalse(output.contains("line 1\n"))
        assertEquals("", reader.read())
    }
}
