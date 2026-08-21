package com.hatake716.linuxdesktop

import com.hatake716.linuxdesktop.data.LinuxDesktopRepository
import com.termux.app.TermuxApplication

class LinuxDesktopApplication : TermuxApplication() {
    val repository: LinuxDesktopRepository by lazy { LinuxDesktopRepository(this) }
}
