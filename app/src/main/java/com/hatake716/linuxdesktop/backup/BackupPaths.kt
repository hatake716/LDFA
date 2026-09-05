package com.hatake716.linuxdesktop.backup

import android.content.Context
import java.io.File

/**
 * Resolves the on-disk locations a backup reads and a restore writes. The app's
 * data dir is the fixed Termux prefix root ($PREFIX = filesDir/usr, $HOME =
 * filesDir/home), so these paths are direct File handles — no PRoot, no root.
 */
class BackupPaths(private val files: File) {
    constructor(context: Context) : this(context.filesDir)

    /** $HOME/.local/share/linux-desktop-for-android */
    private val base = File(files, "home/.local/share/linux-desktop-for-android")

    fun metaDir(id: String): File = File(base, "containers/$id")

    /** The proot-distro rootfs for [id], new layout preferred, legacy fallback. */
    fun rootfsDir(id: String): File? {
        val prootBase = File(files, "usr/var/lib/proot-distro")
        val candidates = listOf(
            File(files, "home/.local/share/proot-distro/containers/$id/rootfs"),
            File(prootBase, "containers/$id/rootfs"),
            File(prootBase, "installed-rootfs/$id"),
        )
        return candidates.firstOrNull { File(it, "etc").isDirectory }
    }

    /** Where a fresh restore lays down its rootfs (always the new layout). */
    fun newRootfsDir(id: String): File =
        File(files, "usr/var/lib/proot-distro/containers/$id/rootfs")

    /** Read a single metadata value, or null if absent. */
    fun readMeta(id: String, key: String): String? {
        val f = File(metaDir(id), key)
        return if (f.isFile) f.readText().trim().ifEmpty { null } else null
    }

    /** The container's stored lifecycle state (`ready`, `running`, …) or null. */
    fun state(id: String): String? = readMeta(id, "state")

    /** The guest architecture as `debian` distro records it (unused meta today, derived). */
    fun distro(id: String): String = readMeta(id, "image") ?: "debian:12"

    fun displayName(id: String): String = readMeta(id, "name") ?: id

    fun createdAt(id: String): String? = readMeta(id, "created_at")

    /** True when the container is safely stopped (no writer touching the rootfs). */
    fun isStopped(id: String): Boolean {
        val s = state(id)?.lowercase()
        if ((s != "ready" && s != "failed") || readMeta(id, "installed") != "1") return false
        return listOf("ldfa-install-$id", "ldfa-run-$id").none { session ->
            val pid = File(base, "run/$session.pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            pid != null && pid > 0 && File("/proc/$pid").exists()
        }
    }
}
