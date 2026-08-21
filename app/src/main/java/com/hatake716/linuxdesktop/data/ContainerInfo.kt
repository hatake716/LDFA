package com.hatake716.linuxdesktop.data

import java.nio.charset.StandardCharsets
import java.util.Base64

enum class DesktopEnvironment(
    val wireValue: String,
    val displayName: String,
) {
    XFCE("xfce", "XFCE");

    companion object {
        fun fromWire(@Suppress("UNUSED_PARAMETER") value: String?): DesktopEnvironment = XFCE
    }
}

enum class ContainerState {
    QUEUED,
    INSTALLING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    UNKNOWN;

    val isBusy: Boolean
        get() = this == QUEUED || this == INSTALLING || this == STARTING || this == STOPPING

    companion object {
        fun fromWire(value: String): ContainerState = when (value.lowercase()) {
            "queued" -> QUEUED
            "installing" -> INSTALLING
            "ready" -> READY
            "starting" -> STARTING
            "running" -> RUNNING
            "stopping" -> STOPPING
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

data class ContainerInfo(
    val id: String,
    val name: String,
    val desktopEnvironment: DesktopEnvironment = DesktopEnvironment.XFCE,
    val state: ContainerState,
    val progress: Int,
    val message: String,
    val displayNumber: Int,
    val createdAtEpochSeconds: Long,
    val sessionAlive: Boolean,
) {
    val canStart: Boolean
        get() = state == ContainerState.READY

    val canStop: Boolean
        get() = state == ContainerState.RUNNING || state == ContainerState.STARTING || sessionAlive
}

data class DoctorReport(
    val hostVersion: String = "",
    val hostReady: Boolean = false,
    val tmuxReady: Boolean = false,
    val prootReady: Boolean = false,
    val x11CommandReady: Boolean = false,
    val storageReady: Boolean = false,
    val sharedDirectory: String = "",
) {
    companion object {
        fun parse(output: String): DoctorReport {
            val values = output.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()

            fun enabled(key: String): Boolean = values[key] == "1"

            return DoctorReport(
                hostVersion = values["version"].orEmpty(),
                hostReady = enabled("host_ready"),
                tmuxReady = enabled("tmux"),
                prootReady = enabled("proot_distro"),
                x11CommandReady = enabled("embedded_x11") || enabled("termux_x11"),
                storageReady = enabled("storage"),
                sharedDirectory = values["shared_directory"].orEmpty(),
            )
        }
    }
}

object ContainerInfoParser {
    fun parse(output: String): List<ContainerInfo> = output.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull(::parseLine)
        .sortedByDescending { it.createdAtEpochSeconds }
        .toList()

    internal fun parseLine(line: String): ContainerInfo? {
        val fields = line.split('\t')
        if (fields.size < 8) return null

        return runCatching {
            ContainerInfo(
                id = fields[0],
                name = decode(fields[1]),
                desktopEnvironment = DesktopEnvironment.XFCE,
                state = ContainerState.fromWire(fields[2]),
                progress = fields[3].toIntOrNull()?.coerceIn(0, 100) ?: 0,
                message = decode(fields[4]),
                displayNumber = fields[5].toIntOrNull() ?: 1,
                createdAtEpochSeconds = fields[6].toLongOrNull() ?: 0L,
                sessionAlive = fields[7] == "1",
            )
        }.getOrNull()
    }

    private fun decode(value: String): String {
        if (value.isEmpty()) return ""
        return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }
}
