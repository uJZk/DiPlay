package com.shilapi.xcertplay.web

import java.security.MessageDigest

/**
 * Who may use the browser link.
 *
 * The pairing [code] is compared in constant time. Wrong codes are counted globally: after [MAX_FAILURES] within
 * [FAILURE_WINDOW_MILLIS], every request is refused for [LOCKOUT_MILLIS], the right code included. A right code
 * does not clear the count, so a paired page polling every 400 ms cannot reopen the window for a guesser.
 *
 * Sessions (one per page load): the latest session with the right code wins. A session it replaced is refused
 * until the current one goes stale: [STALE_MILLIS] without a request and without a live video stream.
 */
class BrowserPairingGate(private val code: () -> String?) {
    sealed interface Decision {
        /** The request may proceed; [replaced] is the session it took over from, whose video and touches end now. */
        data class Accepted(val replaced: String? = null) : Decision
        data object Unauthorized : Decision
        data object Throttled : Decision
        data object Replaced : Decision
    }

    private val failures = ArrayDeque<Long>()
    private val replaced = LinkedHashSet<String>()
    private var lockedUntil: Long? = null
    private var lastSeen = 0L

    @get:Synchronized var current: String? = null
        private set
    /** How many times wrong codes started a lockout. */
    @get:Synchronized var lockouts = 0
        private set

    /** An authenticated request from [session]; [currentHasVideo] says whether the current session streams video. */
    @Synchronized fun admit(code: String?, session: String, now: Long, currentHasVideo: Boolean): Decision {
        check(code, now)?.let { return it }
        if (session == current) {
            lastSeen = now
            return Decision.Accepted()
        }
        val stale = current == null || (now - lastSeen > STALE_MILLIS && !currentHasVideo)
        if (session in replaced && !stale) return Decision.Replaced
        val previous = current
        replaced.remove(session)
        if (previous != null) {
            replaced.add(previous)
            if (replaced.size > MAX_REPLACED) replaced.remove(replaced.first())
        }
        current = session
        lastSeen = now
        return Decision.Accepted(previous)
    }

    /** `/bye`: [session] is no longer current. It never takes over. */
    @Synchronized fun leave(code: String?, session: String, now: Long): Decision {
        check(code, now)?.let { return it }
        if (session in replaced) return Decision.Replaced
        if (session == current) current = null
        return Decision.Accepted()
    }

    private fun check(code: String?, now: Long): Decision? {
        lockedUntil?.let { if (now < it) return Decision.Throttled else lockedUntil = null }
        val expected = this.code() ?: return Decision.Unauthorized
        if (code != null && MessageDigest.isEqual(expected.toByteArray(), code.toByteArray())) return null
        while (failures.firstOrNull()?.let { now - it >= FAILURE_WINDOW_MILLIS } == true) failures.removeFirst()
        failures.addLast(now)
        if (failures.size >= MAX_FAILURES) {
            failures.clear()
            lockedUntil = now + LOCKOUT_MILLIS
            lockouts++
        }
        return Decision.Unauthorized
    }

    companion object {
        const val MAX_FAILURES = 5
        const val FAILURE_WINDOW_MILLIS = 30_000L
        const val LOCKOUT_MILLIS = 30_000L
        const val STALE_MILLIS = 6_000L
        private const val MAX_REPLACED = 16

        /** 16 to 64 characters of `[A-Za-z0-9_-]`. */
        fun isSessionId(value: String?): Boolean =
            value != null && value.length in 16..64 &&
                value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }
}
