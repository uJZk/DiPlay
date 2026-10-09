package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.web.BrowserPairingGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPairingGateTest {
    private var code: String? = "123456"
    private val gate = BrowserPairingGate { code }
    private val a = "session-a-0123456789"
    private val b = "session-b-0123456789"
    private val c = "session-c-0123456789"

    @Test fun theRightCodeAdmitsAndTheFirstSessionBecomesCurrent() {
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 0, false))
        assertEquals(a, gate.current)
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 100, false))
        assertEquals(Decision.Unauthorized, gate.admit("654321", a, 200, false))
        assertEquals(Decision.Unauthorized, gate.admit(null, a, 300, false))
        assertEquals(Decision.Unauthorized, gate.admit("1234567", a, 400, false))
    }

    @Test fun pairingOffRefusesEveryoneWithoutCountingFailures() {
        code = null
        repeat(10) { assertEquals(Decision.Unauthorized, gate.admit("123456", a, it.toLong(), false)) }
        code = "123456"
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 20, false))
        assertEquals(0, gate.lockouts)
    }

    @Test fun fiveWrongCodesWithinThirtySecondsLockEveryoneOutForThirtySeconds() {
        repeat(4) { assertEquals(Decision.Unauthorized, gate.admit("000000", a, it * 1_000L, false)) }
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 4_500, false))
        assertEquals("A right code does not reset the count", Decision.Unauthorized, gate.admit("000000", a, 5_000, false))
        assertEquals(1, gate.lockouts)
        assertEquals(Decision.Throttled, gate.admit("123456", a, 5_001, false))
        assertEquals(Decision.Throttled, gate.leave("123456", a, 5_002))
        assertEquals(Decision.Throttled, gate.admit("000000", b, 34_999, false))
        assertEquals(1, gate.lockouts)
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 35_000, false))
    }

    @Test fun failuresOlderThanTheWindowDoNotCount() {
        repeat(4) { gate.admit("000000", a, it * 1_000L, false) }
        assertEquals(Decision.Unauthorized, gate.admit("000000", a, 30_500, false))
        assertEquals(Decision.Accepted(), gate.admit("123456", a, 30_600, false))
        assertEquals(0, gate.lockouts)
    }

    @Test fun theLatestSessionWinsAndTheReplacedOneIsRefusedWhileTheCurrentIsFresh() {
        gate.admit("123456", a, 0, false)
        assertEquals(Decision.Accepted(replaced = a), gate.admit("123456", b, 1_000, true))
        assertEquals(b, gate.current)
        assertEquals(Decision.Replaced, gate.admit("123456", a, 2_000, false))
        assertEquals("A brand-new session still takes over at once", Decision.Accepted(replaced = b), gate.admit("123456", c, 3_000, false))
        assertEquals(Decision.Replaced, gate.admit("123456", b, 3_100, false))
        assertEquals("A wrong code is a wrong code, replaced or not", Decision.Unauthorized, gate.admit("000000", b, 3_200, false))
    }

    @Test fun aReplacedSessionMayReturnOnceTheCurrentOneIsStale() {
        gate.admit("123456", a, 0, false)
        gate.admit("123456", b, 1_000, false)
        assertEquals("Exactly 6 s is not yet stale", Decision.Replaced, gate.admit("123456", a, 7_000, false))
        assertEquals("A live video stream keeps the current session fresh", Decision.Replaced, gate.admit("123456", a, 8_000, true))
        assertEquals(Decision.Accepted(replaced = b), gate.admit("123456", a, 8_001, false))
        assertEquals(a, gate.current)
        assertEquals(Decision.Replaced, gate.admit("123456", b, 8_100, false))
    }

    @Test fun byeEndsOnlyTheCurrentSessionAndNeverTakesOver() {
        gate.admit("123456", a, 0, false)
        gate.admit("123456", b, 100, false)
        assertEquals(Decision.Replaced, gate.leave("123456", a, 200))
        assertEquals(b, gate.current)
        assertEquals(Decision.Accepted(), gate.leave("123456", c, 300))
        assertEquals(b, gate.current)
        assertEquals(Decision.Unauthorized, gate.leave("000000", b, 400))
        assertEquals(Decision.Accepted(), gate.leave("123456", b, 500))
        assertNull(gate.current)
        assertEquals("After a bye anyone may become current", Decision.Accepted(), gate.admit("123456", c, 600, false))
    }

    @Test fun sessionIdsAre16To64UrlSafeCharacters() {
        assertTrue(BrowserPairingGate.isSessionId("A".repeat(16)))
        assertTrue(BrowserPairingGate.isSessionId("aZ09_-".repeat(10) + "abcd"))
        assertFalse(BrowserPairingGate.isSessionId("A".repeat(15)))
        assertFalse(BrowserPairingGate.isSessionId("A".repeat(65)))
        assertFalse(BrowserPairingGate.isSessionId("A".repeat(15) + "="))
        assertFalse(BrowserPairingGate.isSessionId("A".repeat(15) + "é"))
        assertFalse(BrowserPairingGate.isSessionId(null))
    }
}
