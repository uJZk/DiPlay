package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaBrowserPageLinksTest {
    @Test fun theCarLinkCarriesTheCodeAndTheAddressOnlyWhenItIsNotThePagesDefault() {
        val page = TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS
        assertEquals("https://ujzk.github.io/DiPlay/play/", page)
        assertEquals("$page#c=123456", TeslaBrowserPageLinks.pageLink(page, "123456", null, 8080))
        assertEquals("$page#c=123456", TeslaBrowserPageLinks.pageLink(page, "123456", "100.109.220.253", 8080))
        assertEquals("$page#c=042001&h=100.64.7.9:8080", TeslaBrowserPageLinks.pageLink(page, "042001", "100.64.7.9", 8080))
        assertEquals("https://car.example/tiplay/#c=123456&h=100.109.220.253:8081",
            TeslaBrowserPageLinks.pageLink("https://car.example/tiplay/", "123456", "100.109.220.253", 8081))
    }

    @Test fun theOtherBrowsersLinkOpensThePhonesOwnCopyOfThePage() {
        assertEquals("http://10.176.81.135:8080/play/#c=123456", TeslaBrowserPageLinks.phoneLink("10.176.81.135", "123456", 8080))
        assertEquals("http://100.109.220.253:8080/play/#c=000001", TeslaBrowserPageLinks.phoneLink("100.109.220.253", "000001", 8080))
    }

    @Test fun aPageAddressIsAnHttpsUrlWithAHostAndNoFragment() {
        assertEquals("https://ujzk.github.io/DiPlay/play/", TeslaBrowserPageLinks.pageAddress("  https://ujzk.github.io/DiPlay/play/ "))
        assertEquals("https://car.example", TeslaBrowserPageLinks.pageAddress("https://car.example"))
        assertEquals("HTTPS://car.example:8443/p/?v=2", TeslaBrowserPageLinks.pageAddress("HTTPS://car.example:8443/p/?v=2"))
        for (bad in listOf(
            "", "   ", "http://car.example/play/", "car.example/play/", "https:///play/", "https://car.example/play/#c=1",
            "https://user:pass@car.example/", "https://car example/", "javascript:alert(1)", "ftp://car.example/",
            "https://car.example/${"a".repeat(600)}", "https://[::1/",
        )) {
            assertNull(bad, TeslaBrowserPageLinks.pageAddress(bad))
        }
    }
}
