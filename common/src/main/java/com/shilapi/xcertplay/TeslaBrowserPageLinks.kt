package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.web.BrowserLinkServer
import java.net.URI
import java.net.URISyntaxException

/**
 * The links the Tesla browser card shows (integration E1, the owner decisions on other browsers and on the shortest
 * page link). Pure.
 *
 * - The HTTPS page, for the Tesla and other Chromium browsers: `<page address>[?t=<phone address>]#c=<code>`, the form
 *   `buildLink` in `site/play/link.js` makes. `t` names the phone (`:port` only when it is not 8080) and stays in the
 *   address bar, so a bookmark reopens the same phone. It is left out for the page's default phone at port 8080, so
 *   the usual link is the page address and the code.
 * - The page the phone serves itself, for other browsers (Safari, Firefox): `http://<address>:8080/play/#c=<code>`.
 *   It talks to its own origin, so it needs no `t`.
 *
 * The code is always in the fragment, which never reaches the page host; the page saves it and removes the fragment,
 * so it stays out of bookmarks and the browser history.
 */
internal object TeslaBrowserPageLinks {
    /** The page published from `site/play/` at the root of the GitHub Pages site. */
    const val DEFAULT_PAGE_ADDRESS = "https://ujzk.github.io/DiPlay/"

    /** The page's address before it moved to the site root; a saved copy of it means the default. */
    const val FORMER_DEFAULT_PAGE_ADDRESS = "https://ujzk.github.io/DiPlay/play/"

    /**
     * The phone the page connects to when a link has no `t` (`DEFAULT_HOST` in `site/play/link.js`, at port 8080): the
     * default extra hotspot address.
     */
    const val PAGE_DEFAULT_PHONE_ADDRESS = HotspotExtraAddress.DEFAULT

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

    /** The page address for a [saved] one: [DEFAULT_PAGE_ADDRESS] when nothing valid is saved, or the former default. */
    fun savedPageAddress(saved: String?): String =
        saved?.takeUnless { it == FORMER_DEFAULT_PAGE_ADDRESS }?.let(::pageAddress) ?: DEFAULT_PAGE_ADDRESS

    /**
     * The HTTPS page link for the car's browser. [phoneAddress] is the IPv4 address the car reaches the phone at. `t`
     * is left out for [PAGE_DEFAULT_PHONE_ADDRESS] at the default port, and when [phoneAddress] is null; the page then
     * connects to its default phone. The page address keeps its other query parameters; a `t` of its own is replaced.
     */
    fun pageLink(pageAddress: String, code: String, phoneAddress: String?, port: Int): String {
        val path = pageAddress.substringBefore('?')
        val others = pageAddress.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it != "t" && !it.startsWith("t=") }
        val defaultPort = port == BrowserLinkServer.DEFAULT_PORT
        val phone = phoneAddress?.takeUnless { it == PAGE_DEFAULT_PHONE_ADDRESS && defaultPort }
            ?.let { if (defaultPort) "t=$it" else "t=$it:$port" }
        val query = listOfNotNull(phone) + others
        return path + (if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")) + "#c=$code"
    }

    /** The page the phone serves at [address], for browsers without the HTTPS page's features. */
    fun phoneLink(address: String, code: String, port: Int): String = "http://$address:$port/play/#c=$code"
}
