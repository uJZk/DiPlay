// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/test/java/com/shilapi/xcertplay/web/TouchLeaseTest.kt at commit c1bd077. Modified for TeslaPlay, 2026-10.
package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTouchSlotsTest {
    private val reports = mutableListOf<List<AirPlayContact>>()
    private var available = true
    private val slots = BrowserTouchSlots { reports.add(it); available }
    // Owners are session ids parsed from separate requests: equal strings, different instances.
    private val owner = String(charArrayOf('s', '1'))
    private fun finger(id: Int = 0, x: Double = .5, down: Boolean = true) = AirPlayContact(id, x, .5, down)

    @Test fun onlyTheOwnerCanTouchAndRelease() {
        val other = "s2"
        assertTrue(slots.claim(owner)); assertFalse(slots.claim(other))
        assertTrue("An equal session id is the same owner", slots.claim(String(charArrayOf('s', '1'))))
        assertFalse(slots.touch(other, listOf(finger()), 0))
        slots.drop(other); assertFalse(slots.claim(other))
        assertTrue(slots.touch(String(charArrayOf('s', '1')), listOf(finger()), 0))
        slots.drop(owner); assertTrue(reports.last().isEmpty()); assertTrue(slots.claim(other))
    }

    @Test fun aFailingReleaseStillFreesTheSlotsForTheNextSession() {
        val failing = BrowserTouchSlots { error("touch channel gone") }
        failing.claim(owner)
        assertThrows(IllegalStateException::class.java) { failing.drop(owner) }
        assertTrue(failing.claim("s2"))
    }

    @Test fun alwaysSendsBothSlotsInOrder() {
        slots.claim(owner)
        slots.touch(owner, listOf(finger(1, .8)), 0)
        assertEquals(listOf(AirPlayContact(0, 0.0, 0.0, false), finger(1, .8)), reports.last())
        slots.touch(owner, emptyList(), 1)
        assertEquals(listOf(AirPlayContact(0, 0.0, 0.0, false), AirPlayContact(1, 0.0, 0.0, false)), reports.last())
    }

    @Test fun firstFingerLiftingDoesNotMoveTheSecondIntoTheFirstSlot() {
        slots.claim(owner)
        slots.touch(owner, listOf(finger(0), finger(1, .8)), 0)
        slots.touch(owner, listOf(finger(1, .8), finger(0, down = false)), 1)
        assertFalse(reports.last()[0].down)
        assertEquals(1, reports.last()[1].id)
        assertEquals(.8, reports.last()[1].x, 0.0)
    }

    @Test fun watchdogReleasesAndHeartbeatKeepsALongPress() {
        slots.claim(owner); slots.touch(owner, listOf(finger()), 0)
        slots.touch(owner, listOf(finger()), 1400)
        slots.expire(2000); assertTrue(reports.last()[0].down)
        slots.expire(2901); assertTrue(reports.last().isEmpty())
        val count = reports.size; slots.expire(5000); assertEquals(count, reports.size)
    }

    @Test fun liftedFingersAndRefusedTouchesDoNotArmTheWatchdog() {
        slots.claim(owner)
        slots.touch(owner, listOf(finger(down = false)), 0)
        slots.expire(10_000)
        available = false
        assertFalse(slots.touch(owner, listOf(finger()), 10_000))
        slots.expire(20_000)
        assertEquals(2, reports.size)
    }

    @Test fun rejectsNonFiniteOutOfRangeDuplicateAndTooManyContacts() {
        slots.claim(owner)
        for (contacts in listOf(
            listOf(finger(x = Double.NaN)), listOf(finger(x = 1.1)), listOf(finger(x = -0.1)),
            listOf(finger(x = Double.POSITIVE_INFINITY)), listOf(finger(), finger()),
            listOf(finger(3)), listOf(finger(-1)), listOf(finger(), finger(1), finger(2)),
        )) {
            assertFalse(slots.touch(owner, contacts, 0))
        }
        assertTrue(reports.isEmpty())
    }
}
