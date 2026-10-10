package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.web.BrowserLinkStatus
import com.shilapi.xcertplay.web.BrowserSize
import com.shilapi.xcertplay.web.BrowserViewport
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Phone + browser mode: the CarPlay canvas the car's browser asks for (integration E2). Pure, without Android.
 *
 * The canvas is the browser's last viewport in device pixels, rounded down to even numbers and scaled down, keeping its
 * shape, to fit 3840 × 2160 in either orientation; 1280 × 720 until a browser has reported a viewport. CarPlay's
 * physical size follows the page's CSS size at 96 CSS px per inch (0.2646 mm per CSS px), so CarPlay draws its
 * controls about as large as the browser draws its own; without a CSS size, the CarPlay-size preset's width applies.
 * The head unit's resolution, icon size, safe area, dock, split screen, turning screen and side panel do not apply.
 */
internal object TeslaBrowserCanvas {
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val MAX_LONG_SIDE = 3840
    const val MAX_SHORT_SIDE = 2160
    const val FPS = 60
    const val MILLIMETERS_PER_CSS_PIXEL = 0.2646

    data class Plan(
        val width: Int,
        val height: Int,
        val widthMm: Int,
        val heightMm: Int,
        /** False for the default canvas, before any browser reported its size. */
        val fromViewport: Boolean,
        /** True when the physical size comes from the page's CSS size, false for the preset. */
        val fromCss: Boolean,
    ) {
        val size: BrowserSize get() = BrowserSize(width, height)

        /** One line for the session log: sizes only. */
        fun describe(fps: Int = FPS): String = "Phone + browser canvas=${width}x$height " +
            (if (fromViewport) "from the browser's viewport" else "default (no browser size yet)") +
            " physical=${widthMm}x${heightMm}mm (${if (fromCss) "CSS size" else "CarPlay size preset"}) fps=$fps"
    }

    /** The canvas for [viewport] (null before any browser reported one); [presetWidthMm] is the CarPlay-size preset. */
    fun plan(viewport: BrowserViewport?, presetWidthMm: Int): Plan {
        val valid = viewport?.takeIf { BrowserViewport.isValid(it.width, it.height) }
        val size = size(valid?.size)
        val cssWidth = valid?.cssWidth?.takeIf { it.isFinite() && it > 0.0 }
        val widthMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            ((cssWidth?.times(MILLIMETERS_PER_CSS_PIXEL)) ?: presetWidthMm.toDouble()).roundToInt(),
        )
        val heightMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            (widthMm.toDouble() * size.height / size.width).roundToInt(),
        )
        return Plan(size.width, size.height, widthMm, heightMm, fromViewport = valid != null, fromCss = cssWidth != null)
    }

    /** The canvas for a viewport of [viewport] device pixels, or the default canvas for null or an invalid size. */
    fun size(viewport: BrowserSize?): BrowserSize {
        if (viewport == null || !BrowserViewport.isValid(viewport.width, viewport.height)) {
            return BrowserSize(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        }
        val long = maxOf(viewport.width, viewport.height)
        val short = minOf(viewport.width, viewport.height)
        val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / long, MAX_SHORT_SIDE.toDouble() / short)
        return BrowserSize(even(viewport.width * scale), even(viewport.height * scale))
    }

    /** A running canvas needs "Apply and reconnect" only when it is more than 8 px or 1 % in aspect off [wanted]. */
    fun differs(running: BrowserSize, wanted: BrowserSize): Boolean = BrowserLinkStatus.differs(running, wanted)

    /** The viewport for logs: sizes only. */
    fun describe(viewport: BrowserViewport): String = String.format(
        Locale.ROOT, "%dx%d css=%.0fx%.0f dpr=%.2f",
        viewport.width, viewport.height, viewport.cssWidth, viewport.cssHeight, viewport.devicePixelRatio,
    )

    private fun even(value: Double): Int = (floor(value).toInt() / 2 * 2).coerceAtLeast(2)
}
