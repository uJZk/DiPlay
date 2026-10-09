package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.BrowserLinkServer
import java.net.URI
import java.net.URISyntaxException

/**
 * The links the Tesla browser card shows (integration E1, the owner decision on other browsers and the page link
 * format). Pure.
 *
 * - The HTTPS page, for the Tesla and other Chromium browsers: `<page address>?t=<phone address>#c=<code>`, the form
 *   `buildLink` in `site/play/link.js` makes. `t` names the phone (`:port` only when it is not 8080) and stays in the
 *   address bar, so a bookmark reopens the same phone.
 * - The page the phone serves itself, for other browsers (Safari, Firefox): `http://<address>:8080/play/#c=<code>`.
 *   It talks to its own origin, so it needs no `t`.
 *
 * The code is always in the fragment, which never reaches the page host; the page saves it and removes the fragment,
 * so it stays out of bookmarks and the browser history.
 */
internal object TeslaBrowserPageLinks {
    /** The page published from `site/play/` with the GitHub Pages site. */
    const val DEFAULT_PAGE_ADDRESS = "https://ujzk.github.io/DiPlay/play/"

    private const val MAX_LENGTH = 512

    /** [text] as a page address: an `https://` URL with a host and no user, fragment or spaces; null otherwise. */
    fun pageAddress(text: String): String? {
        val address = text.trim()
        if (address.isEmpty() || address.length > MAX_LENGTH || address.any { it.isWhitespace() || it == '#' }) return null
        val uri = try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return address
    }

    /**
     * The HTTPS page link for the car's browser. [phoneAddress] is the IPv4 address the car reaches the phone at; null
     * leaves `t` out, and the page then uses the address it saved, else its default. The page address keeps its other
     * query parameters; a `t` of its own is replaced.
     */
    fun pageLink(pageAddress: String, code: String, phoneAddress: String?, port: Int): String {
        val path = pageAddress.substringBefore('?')
        val others = pageAddress.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it != "t" && !it.startsWith("t=") }
        val phone = phoneAddress?.let { if (port == BrowserLinkServer.DEFAULT_PORT) "t=$it" else "t=$it:$port" }
        val query = listOfNotNull(phone) + others
        return path + (if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")) + "#c=$code"
    }

    /** The page the phone serves at [address], for browsers without the HTTPS page's features. */
    fun phoneLink(address: String, code: String, port: Int): String = "http://$address:$port/play/#c=$code"
}
