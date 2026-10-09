package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.BrowserSize
import com.shilapi.xcertplay.web.BrowserViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaBrowserCanvasTest {
    private fun viewport(width: Int, height: Int, cssWidth: Double = 0.0, cssHeight: Double = 0.0, dpr: Double = 0.0) =
        BrowserViewport(width, height, cssWidth, cssHeight, dpr)

    @Test fun theCanvasIsTheBrowsersViewportInDevicePixels() {
        // Model Y, report d73a0959: a 773 × 601 CSS px window at DPR 1.53.
        val plan = TeslaBrowserCanvas.plan(viewport(1182, 920, 773.0, 601.0, 1.53), presetWidthMm = 300)
        assertEquals(1182, plan.width)
        assertEquals(920, plan.height)
        assertTrue(plan.fromViewport)
        assertTrue(plan.fromCss)
        // 773 CSS px × 0.2646 mm = 204.5 mm; the height keeps the canvas's shape.
        assertEquals(205, plan.widthMm)
        assertEquals(160, plan.heightMm)
        assertEquals(60, TeslaBrowserCanvas.FPS)
    }

    @Test fun withoutAViewportTheCanvasIs720pAndThePresetSetsThePhysicalSize() {
        val plan = TeslaBrowserCanvas.plan(null, presetWidthMm = 300)
        assertEquals(BrowserSize(1280, 720), plan.size)
        assertFalse(plan.fromViewport)
        assertFalse(plan.fromCss)
        assertEquals(300, plan.widthMm)
        assertEquals(169, plan.heightMm)
        assertEquals(BrowserSize(1280, 720), TeslaBrowserCanvas.size(null))
        // An invalid saved size is no viewport at all.
        assertEquals(BrowserSize(1280, 720), TeslaBrowserCanvas.plan(viewport(100, 920), 300).size)
        assertEquals(BrowserSize(1280, 720), TeslaBrowserCanvas.size(BrowserSize(5000, 1000)))
    }

    @Test fun withoutACssSizeThePresetSetsThePhysicalSize() {
        val plan = TeslaBrowserCanvas.plan(viewport(1600, 900), presetWidthMm = 250)
        assertEquals(BrowserSize(1600, 900), plan.size)
        assertTrue(plan.fromViewport)
        assertFalse(plan.fromCss)
        assertEquals(250, plan.widthMm)
        assertEquals(141, plan.heightMm)
        assertFalse(TeslaBrowserCanvas.plan(viewport(1600, 900, Double.NaN, 0.0, 1.0), 250).fromCss)
        assertFalse(TeslaBrowserCanvas.plan(viewport(1600, 900, -5.0, 0.0, 1.0), 250).fromCss)
    }

    @Test fun sidesAreEvenAndTheCanvasFits4kInEitherOrientationKeepingItsShape() {
        assertEquals(BrowserSize(1182, 920), TeslaBrowserCanvas.size(BrowserSize(1183, 921)))
        assertEquals(BrowserSize(3840, 2160), TeslaBrowserCanvas.size(BrowserSize(4096, 2304)))
        assertEquals(BrowserSize(3840, 960), TeslaBrowserCanvas.size(BrowserSize(4000, 1000)))
        // 1500 × 0.9375 = 1406.25, rounded down to even.
        assertEquals(BrowserSize(3840, 1406), TeslaBrowserCanvas.size(BrowserSize(4096, 1500)))
        // A portrait window (a phone browser) is capped the same way.
        assertEquals(BrowserSize(2160, 3840), TeslaBrowserCanvas.size(BrowserSize(2304, 4096)))
        assertEquals(BrowserSize(2160, 2880), TeslaBrowserCanvas.size(BrowserSize(2400, 3200)))
        for (candidate in listOf(BrowserSize(4095, 2303), BrowserSize(3999, 2999), BrowserSize(321, 641), BrowserSize(2999, 4001))) {
            val size = TeslaBrowserCanvas.size(candidate)
            assertTrue("$candidate -> $size", size.width % 2 == 0 && size.height % 2 == 0)
            assertTrue("$candidate -> $size", maxOf(size.width, size.height) <= 3840 && minOf(size.width, size.height) <= 2160)
            assertTrue("$candidate -> $size keeps its shape",
                kotlin.math.abs(size.width.toDouble() / size.height - candidate.width.toDouble() / candidate.height) < 0.01)
        }
    }

    @Test fun aRunningCanvasNeedsAReconnectOnlyWhenItIsVisiblyOff() {
        val running = BrowserSize(1182, 920)
        assertFalse(TeslaBrowserCanvas.differs(running, running))
        assertFalse("8 px is close enough", TeslaBrowserCanvas.differs(running, BrowserSize(1190, 926)))
        assertTrue(TeslaBrowserCanvas.differs(running, BrowserSize(1256, 706)))
        assertTrue(TeslaBrowserCanvas.differs(running, BrowserSize(1192, 920)))
    }

    @Test fun theLogLinesCarrySizesOnly() {
        val line = TeslaBrowserCanvas.plan(viewport(1182, 920, 773.0, 601.0, 1.53), 300).describe()
        assertEquals("Phone + browser canvas=1182x920 from the browser's viewport physical=205x160mm (CSS size) fps=60", line)
        assertEquals("1182x920 css=773x601 dpr=1.53", TeslaBrowserCanvas.describe(viewport(1182, 920, 773.0, 601.0, 1.53)))
        assertEquals(line, DiagnosticRedactor.redact(line))
    }
}
