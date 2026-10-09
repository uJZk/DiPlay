package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaBrowserPageLinksTest {
    /** The form `buildLink` in site/play/link.js makes: the phone in `?t=` (no port for 8080), the code in the fragment. */
    @Test fun theCarLinkNamesThePhoneInTAndKeepsTheCodeInTheFragment() {
        val page = TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS
        assertEquals("https://ujzk.github.io/DiPlay/play/", page)
        assertEquals("$page?t=100.109.220.253#c=123456", TeslaBrowserPageLinks.pageLink(page, "123456", "100.109.220.253", 8080))
        assertEquals("$page?t=10.176.81.135#c=042001", TeslaBrowserPageLinks.pageLink(page, "042001", "10.176.81.135", 8080))
        assertEquals("Without an address the page uses the one it saved", "$page#c=123456",
            TeslaBrowserPageLinks.pageLink(page, "123456", null, 8080))
        assertEquals("https://car.example/tiplay/?t=100.64.7.9:8081#c=123456",
            TeslaBrowserPageLinks.pageLink("https://car.example/tiplay/", "123456", "100.64.7.9", 8081))
    }

    @Test fun thePageAddressKeepsItsOwnQueryButNotItsOwnT() {
        assertEquals("https://car.example/p/?t=100.64.7.9&v=2&tab=1#c=123456",
            TeslaBrowserPageLinks.pageLink("https://car.example/p/?v=2&t=1.2.3.4&tab=1&t", "123456", "100.64.7.9", 8080))
        assertEquals("https://car.example/p/?v=2#c=123456",
            TeslaBrowserPageLinks.pageLink("https://car.example/p/?v=2&t=1.2.3.4", "123456", null, 8080))
        assertEquals("https://car.example/p/#c=123456", TeslaBrowserPageLinks.pageLink("https://car.example/p/?", "123456", null, 8080))
        for (link in listOf(
            TeslaBrowserPageLinks.pageLink("https://car.example/p/?c=999999", "123456", "100.64.7.9", 8080),
            TeslaBrowserPageLinks.pageLink(TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS, "123456", "100.64.7.9", 8080),
        )) {
            assertEquals(link, "123456", link.substringAfter('#').removePrefix("c="))
            assertFalse("The code never goes into the part the page host sees: $link", "123456" in link.substringBefore('#'))
        }
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
