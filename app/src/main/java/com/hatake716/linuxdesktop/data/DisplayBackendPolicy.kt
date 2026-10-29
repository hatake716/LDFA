package com.hatake716.linuxdesktop.data

/**
 * Native X11 is attempted on every supported Android release.
 *
 * v0.8 no longer launches Xorg through app_process. The native server lives in a dedicated
 * Android :x11 service process, so the Android 17 app_process failure that required the old SDK 37
 * bypass no longer applies. Runtime capability checks decide whether VNC fallback is necessary.
 */
internal object DisplayBackendPolicy {
    fun shouldAttemptNativeFirst(@Suppress("UNUSED_PARAMETER") androidSdk: Int): Boolean = true
}
