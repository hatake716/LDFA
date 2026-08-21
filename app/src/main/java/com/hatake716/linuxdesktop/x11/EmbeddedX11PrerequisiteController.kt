package com.hatake716.linuxdesktop.x11

import android.content.Context
import com.hatake716.linuxdesktop.data.TermuxCommandClient
import kotlin.time.Duration.Companion.minutes

/** Ensures Termux-side XKB data exists before the Android :x11 process starts Xorg. */
internal object EmbeddedX11PrerequisiteController {
    suspend fun ensure(context: Context) {
        val script = context.assets.open("ldfa-x11.sh").bufferedReader().use { it.readText() }
        TermuxCommandClient(context).runBundledX11Script(
            script = script,
            action = "prepare",
            timeout = 5.minutes,
        )
    }
}
