package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.web.BrowserLinkServer
import java.net.URI
import java.net.URISyntaxException

/**
 * The links the Tesla browser card shows (integration E1 and the owner decision on other browsers). Pure.
 *
 * - The HTTPS page, for the Tesla and other Chromium browsers: `<page address>#c=<code>`, plus `&h=<address>:8080`
 *   only when the car must reach the phone at another address than the page's default `100.109.220.253`.
 * - The page the phone serves itself, for other browsers (Safari, Firefox): `http://<address>:8080/play/#c=<code>`.
 *
 * The page reads both parameters from the fragment, stores them and removes the fragment, so the code never reaches
 * a server or the browser history.
 */
internal object TeslaBrowserPageLinks {
    /** The page published from `site/play/` with the GitHub Pages site. */
    const val DEFAULT_PAGE_ADDRESS = "https://ujzk.github.io/DiPlay/play/"

    /** The address the page tries when the link names none (`DEFAULT_HOST` in `site/play/link.js`). */
    const val DEFAULT_BROWSER_ADDRESS = HotspotExtraAddress.DEFAULT

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

    /** The HTTPS page link for the car's browser. [browserAddress] is the IPv4 address the car reaches the phone at. */
    fun pageLink(pageAddress: String, code: String, browserAddress: String?, port: Int): String {
        val host = browserAddress?.takeIf { it != DEFAULT_BROWSER_ADDRESS || port != BrowserLinkServer.DEFAULT_PORT }
        return "$pageAddress#c=$code" + (host?.let { "&h=$it:$port" } ?: "")
    }

    /** The page the phone serves at [address], for browsers without the HTTPS page's features. */
    fun phoneLink(address: String, code: String, port: Int): String = "http://$address:$port/play/#c=$code"
}
