package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BrowserAppearanceTest {
    @Test fun acceptsOnlyBooleanThemeReportsAndKeepsThemeDuringOtherStats() {
        assertEquals(true, BrowserAppearance.update(mapOf("themeDark" to true)))
        assertTrue(BrowserAppearance.night)
        assertNull(BrowserAppearance.update(mapOf("themeDark" to "false")))
        assertNull(BrowserAppearance.update(mapOf("fps" to 60)))
        assertTrue(BrowserAppearance.night)
        assertEquals(false, BrowserAppearance.update(mapOf("themeDark" to false)))
        assertFalse(BrowserAppearance.night)
    }
}
