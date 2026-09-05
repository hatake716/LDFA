package com.hatake716.linuxdesktop.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Reads only this launch's additions, with bounded memory and UTF-8 safe line boundaries. */
internal class StartupLogReader(private val file: File) {
    private var offset = file.length()
    private var fileKey = key()
    private var pending = byteArrayOf()

    private fun key(): Any? = if (file.exists()) {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
    } else null

    fun read(): String {
        if (!file.isFile) return ""
        val currentKey = key()
        return RandomAccessFile(file, "r").use { input ->
            val end = input.length()
            if (currentKey != fileKey || end < offset) {
                offset = 0
                pending = byteArrayOf()
            }
            fileKey = currentKey
            val skipped = end - offset > MAX_BYTES
            if (skipped) {
                offset = end - MAX_BYTES
                pending = byteArrayOf()
            }
            input.seek(offset)
            val bytes = ByteArray((end - offset).toInt())
            input.readFully(bytes)
            offset = end
            var combined = pending + bytes
            if (skipped) {
                val firstBreak = combined.indexOfFirst { it == 10.toByte() || it == 13.toByte() }
                combined = if (firstBreak >= 0) combined.copyOfRange(firstBreak + 1, combined.size) else byteArrayOf()
            }
            val lastBreak = combined.indexOfLast { it == 10.toByte() || it == 13.toByte() }
            if (lastBreak < 0) {
                pending = combined.takeLast(MAX_BYTES).toByteArray()
                ""
            } else {
                pending = combined.copyOfRange(lastBreak + 1, combined.size)
                (if (skipped) "… 大量のログの一部を省略しました …\n" else "") +
                    combined.copyOfRange(0, lastBreak + 1).toString(Charsets.UTF_8)
            }
        }
    }

    companion object { private const val MAX_BYTES = 32 * 1024 }
}
