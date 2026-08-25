package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun knownIdsRoundTrip() {
        assertEquals(KeyboardLayout.JIS, KeyboardLayout.fromId("jis"))
        assertEquals(KeyboardLayout.US, KeyboardLayout.fromId("us"))
    }

    @Test
    fun missingOrUnknownIdFallsBackToDefault() {
        // v1.0.x-and-earlier environments have no keyboard_layout metadata.
        assertEquals(KeyboardLayout.JIS, KeyboardLayout.fromId(null))
        assertEquals(KeyboardLayout.JIS, KeyboardLayout.fromId(""))
        assertEquals(KeyboardLayout.JIS, KeyboardLayout.fromId("gb"))
        assertEquals(KeyboardLayout.JIS, KeyboardLayout.DEFAULT)
    }

    @Test
    fun idsAreTheStableStringsWrittenToMetadataAndScripts() {
        // These strings cross into ldfa-host (meta + LDFA_KEYBOARD_LAYOUT); they
        // must not drift or existing environments would silently re-default.
        assertEquals("jis", KeyboardLayout.JIS.id)
        assertEquals("us", KeyboardLayout.US.id)
    }
}
