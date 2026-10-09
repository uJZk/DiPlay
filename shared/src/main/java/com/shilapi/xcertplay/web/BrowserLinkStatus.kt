package com.shilapi.xcertplay.web

import kotlin.math.abs

data class BrowserSize(val width: Int, val height: Int)

/** A `vp` event: [width] × [height] device pixels of the page's video area, its CSS size and its pixel ratio. */
data class BrowserViewport(
    val width: Int,
    val height: Int,
    val cssWidth: Double,
    val cssHeight: Double,
    val devicePixelRatio: Double,
) {
    val size: BrowserSize get() = BrowserSize(width, height)

    companion object {
        fun isValid(width: Int, height: Int): Boolean =
            width in 320..4096 && height in 320..4096 && width.toDouble() / height in 0.5..4.0
    }
}

/** What the app reports to the page in every `POST /control` answer. */
data class BrowserLinkStatus(
    val state: State,
    /** The WebCodecs codec string of the current stream ([WebVideoHub.StreamInfo.codec]). */
    val codec: String? = null,
    /** The negotiated CarPlay canvas. */
    val display: BrowserSize? = null,
    /** The last valid viewport the phone has. */
    val viewport: BrowserSize? = null,
    /** False while no CarPlay session can take touches. */
    val touchAvailable: Boolean = false,
    /** Phone-side counters and timings for the page's stats overlay; the server adds the hub's and its own. */
    val phone: Map<String, Any?> = emptyMap(),
) {
    enum class State(val wire: String) { IDLE("idle"), CONNECTING("connecting"), STREAMING("streaming") }

    /**
     * The page offers "Apply and reconnect" while a session runs on a canvas that differs from the viewport
     * by more than 8 px on a side or 1 % in aspect ratio.
     */
    val fit: Boolean get() {
        if (state == State.IDLE) return false
        return differs(display ?: return false, viewport ?: return false)
    }

    companion object {
        /** More than 8 px apart on a side, or more than 1 % apart in aspect ratio. */
        fun differs(display: BrowserSize, viewport: BrowserSize): Boolean {
            val aspect = display.width.toDouble() / display.height
            val wanted = viewport.width.toDouble() / viewport.height
            return abs(display.width - viewport.width) > 8 || abs(display.height - viewport.height) > 8 ||
                abs(aspect - wanted) > 0.01 * wanted
        }
    }
}
