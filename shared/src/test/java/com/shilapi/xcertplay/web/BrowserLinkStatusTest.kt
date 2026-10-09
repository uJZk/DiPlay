package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.web.BrowserLinkStatus.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLinkStatusTest {
    private fun status(display: BrowserSize?, viewport: BrowserSize?, state: State = State.STREAMING) =
        BrowserLinkStatus(state, display = display, viewport = viewport)

    @Test fun fitIsOfferedOnlyForARunningSessionOnAnotherCanvas() {
        val canvas = BrowserSize(1182, 920)
        assertFalse(status(canvas, canvas).fit)
        assertFalse("8 px is close enough", status(canvas, BrowserSize(1190, 926)).fit)
        assertTrue(status(canvas, BrowserSize(1191, 920)).fit)
        assertTrue(status(canvas, BrowserSize(1182, 911)).fit)
        // Same size class, different shape: 1.5 % aspect.
        assertTrue(status(BrowserSize(1000, 800), BrowserSize(1008, 794)).fit)
        assertTrue(status(canvas, BrowserSize(1280, 720), State.CONNECTING).fit)
        assertFalse(status(canvas, BrowserSize(1280, 720), State.IDLE).fit)
        assertFalse(status(null, BrowserSize(1280, 720)).fit)
        assertFalse(status(canvas, null).fit)
    }

    @Test fun viewportsAre320To4096PixelsWithASaneAspect() {
        assertTrue(BrowserViewport.isValid(1182, 920))
        assertTrue(BrowserViewport.isValid(320, 320))
        assertTrue(BrowserViewport.isValid(4096, 2048))
        assertTrue(BrowserViewport.isValid(1280, 320))
        assertFalse(BrowserViewport.isValid(319, 600))
        assertFalse(BrowserViewport.isValid(4098, 2000))
        assertFalse(BrowserViewport.isValid(1600, 399))
        assertFalse(BrowserViewport.isValid(320, 641))
    }
}
