package com.hatake716.linuxdesktop.backup

import java.io.File
import java.io.IOException

/** API 26–28: move out of disposable staging before the service cleans it up. */
internal object BackupExport {
    fun retain(source: File, directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("保存先を作成できませんでした。")
        val destination = File(directory, source.name)
        if (destination.exists()) throw IOException("同じ名前のバックアップがすでに存在します。")
        // Both paths use the same external-files volume (or the same internal fallback).
        if (!source.renameTo(destination)) throw IOException("バックアップを保存できませんでした。")
        return destination
    }
}
