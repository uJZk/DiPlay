package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LocalHotspotSettingsTest {
    @Test fun readsCurrentHotspotWithoutImportingRouterCredentials() {
        val dump = "SSID: \"Home router\"\n  mCurrentSoftApConfiguration=ssid=\"My phone\"\nPassphrase: <non-empty>"
        val result = LocalHotspotSettings.fromDump(dump)!!
        assertEquals("My phone", result.ssid)
        assertNull(result.password)
        assertNull(LocalHotspotSettings.fromDump("SSID: \"Home router\""))
    }
    @Test fun invalidNamesAndMaskedPasswordsAreNotImportedOrLogged() {
        assertNull(LocalHotspotSettings.credentials("", "password"))
        assertNull(LocalHotspotSettings.credentials("a".repeat(33), "password"))
        assertNull(LocalHotspotSettings.credentials("热点".repeat(6), "password"))
        assertNull(LocalHotspotSettings.credentials("Phone", "********")!!.password)
        assertNull(LocalHotspotSettings.credentials("Phone", "<non-empty>")!!.password)
        val result = LocalHotspotSettings.credentials("Phone", "secret123")!!
        assertFalse(result.toString().contains("secret123"))
        assertEquals("secret123", result.password)
    }
}
