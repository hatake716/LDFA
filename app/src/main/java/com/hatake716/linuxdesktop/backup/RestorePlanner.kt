package com.hatake716.linuxdesktop.backup

import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Plans a restore: it always mints a FRESH container id (so restoring the same
 * backup twice never collides) and returns the destination paths. Because the
 * guest rootfs bakes in NO container id — the shared folder mounts at the fixed
 * `/mnt/android` — nothing inside the extracted rootfs needs rewriting. Only the
 * Android-side names differ (meta dir, rootfs dir, shared folder), and those come
 * straight from the new id.
 */
class RestorePlanner(private val paths: BackupPaths) {

    data class Plan(
        val newId: String,
        val rootfsDir: File,
        val metaDir: File,
        val sharedDir: File,
    )

    /** Build a plan for restoring an environment whose original display name is [displayName]. */
    fun plan(displayName: String): Plan {
        val id = mintId(displayName)
        return Plan(
            newId = id,
            rootfsDir = paths.newRootfsDir(id),
            metaDir = paths.metaDir(id),
            sharedDir = sharedDir(id),
        )
    }

    /** A restore-safe display name: append " (2)", " (3)"… when the base name is taken. */
    fun uniqueDisplayName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var n = 2
        while ("$base ($n)" in existing) n++
        return "$base ($n)"
    }

    private fun sharedDir(id: String): File =
        File("/data/data/com.hatake716.linuxdesktop/files/home/storage/shared/LinuxDesktop/$id")

    /**
     * Same shape as LinuxDesktopRepository.createContainerId: `<ascii16>-<uuid8>`.
     * Kept in sync deliberately so restored ids look identical to created ones and
     * satisfy validate_id (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`).
     */
    private fun mintId(displayName: String): String {
        val asciiPrefix = displayName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(16)
            .ifBlank { "debian-xfce" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$asciiPrefix-$suffix"
    }
}
