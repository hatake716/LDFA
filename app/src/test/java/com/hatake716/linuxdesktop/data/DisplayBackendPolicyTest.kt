package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayBackendPolicyTest {
    @Test
    fun nativeServiceIsPreferredOnSupportedAndroidVersions() {
        assertTrue(DisplayBackendPolicy.shouldAttemptNativeFirst(26))
        assertTrue(DisplayBackendPolicy.shouldAttemptNativeFirst(36))
        assertTrue(DisplayBackendPolicy.shouldAttemptNativeFirst(37))
        assertTrue(DisplayBackendPolicy.shouldAttemptNativeFirst(38))
    }
}
