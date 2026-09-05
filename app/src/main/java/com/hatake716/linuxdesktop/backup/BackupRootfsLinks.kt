package com.hatake716.linuxdesktop.backup

import java.io.File

/** PRoot's emulated hard links contain absolute Android paths, including the container ID. */
internal object BackupRootfsLinks {
    fun sourceRoots(container: BackupManifest.ContainerInfo): List<String> {
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(container.id)) return emptyList()
        val prefix = container.prefix.trimEnd('/')
        if (!Regex("^/data/(data|user/[0-9]+)/[^/]+/files/usr$").matches(prefix)) return emptyList()
        val files = prefix.removeSuffix("/usr")
        val aliases = linkedSetOf(files)
        if (files.startsWith("/data/data/")) aliases += files.replaceFirst("/data/data/", "/data/user/0/")
        if (files.startsWith("/data/user/0/")) aliases += files.replaceFirst("/data/user/0/", "/data/data/")
        return aliases.flatMap { root ->
            listOf(
                "$root/usr/var/lib/proot-distro/containers/${container.id}/rootfs",
                "$root/usr/var/lib/proot-distro/installed-rootfs/${container.id}",
                "$root/home/.local/share/proot-distro/containers/${container.id}/rootfs",
            )
        }
    }

    fun rebase(target: String, sourceRoots: List<String>, destination: File): String {
        val source = sourceRoots.firstOrNull { target.startsWith("$it/") } ?: return target
        val relative = target.removePrefix("$source/")
        val root = destination.toPath().toAbsolutePath().normalize()
        val rebased = root.resolve(relative).normalize()
        if (!rebased.startsWith(root)) throw BackupFormatException("バックアップ内のリンク先が不正です。")
        return rebased.toString()
    }
}
