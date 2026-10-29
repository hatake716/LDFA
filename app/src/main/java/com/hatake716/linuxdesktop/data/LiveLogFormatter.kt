package com.hatake716.linuxdesktop.data

internal object LiveLogFormatter {
    private val ansiEscape = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun format(raw: String, maxLines: Int = 40): String {
        val lineLimit = maxLines.coerceIn(1, 200)
        val normalized = raw
            .replace(ansiEscape, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized
            .lineSequence()
            .map { it.trimEnd() }
            .toList()
            .dropLastWhile { it.isBlank() }

        return lines
            .takeLast(lineLimit)
            .joinToString("\n")
            .trimStart()
    }
}
