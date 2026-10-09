// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/main/java/com/shilapi/xcertplay/web/TouchLease.kt at commit c1bd077. Modified for TeslaPlay, 2026-10.
package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact

/**
 * Serializes browser ownership and releases fingers when a client disappears.
 *
 * The page sends the full contact list with `id` = slot (0 or 1). [send] always gets both slots in order,
 * because the HID report places contacts by list position: without that, lifting finger 0 would move
 * finger 1 into slot 0. An empty list releases both fingers.
 */
class BrowserTouchSlots(private val send: (List<AirPlayContact>) -> Boolean) {
    private var owner: Any? = null
    private var lastTouch = 0L
    private var pressed = false

    @Synchronized fun claim(client: Any): Boolean {
        if (owner != null && owner != client) return false
        owner = client
        return true
    }

    /** Returns false for a caller that is not the owner, more than two contacts, or values outside 0..1. */
    @Synchronized fun touch(client: Any, contacts: List<AirPlayContact>, now: Long): Boolean {
        if (owner != client || contacts.size > 2 || contacts.any {
            !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 || it.id !in 0..1
        } || contacts.map { it.id }.distinct().size != contacts.size) return false
        val slots = (0..1).map { id -> contacts.find { it.id == id } ?: AirPlayContact(id, 0.0, 0.0, false) }
        val accepted = send(slots)
        pressed = accepted && contacts.any { it.down }
        lastTouch = now
        return accepted
    }

    /** Releases held fingers [EXPIRY_MILLIS] after the last update; the page re-sends held contacts every 400 ms. */
    @Synchronized fun expire(now: Long) {
        if (pressed && now - lastTouch > EXPIRY_MILLIS) releaseFingers()
    }

    @Synchronized fun drop(client: Any) {
        if (owner != client) return
        // Free the slots first: a failing release must not leave every later session without touch.
        owner = null; releaseFingers()
    }

    private fun releaseFingers() { send(emptyList()); pressed = false }

    companion object {
        const val EXPIRY_MILLIS = 1500L
    }
}
