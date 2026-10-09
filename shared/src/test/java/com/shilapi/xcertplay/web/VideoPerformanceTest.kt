// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/test/java/com/shilapi/xcertplay/web/VideoPerformanceTest.kt at commit c1bd077. Modified for TeslaPlay, 2026-10.
package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPerformanceTest {
    @Test fun boundedTimingWindowAndIdleRateAreIndependentOfCumulativeCounts() {
        var now = 1L
        val metrics = VideoPerformance { now }
        repeat(300) { metrics.count("input"); metrics.timing("queue", it * 1_000_000L) }
        val snapshot = metrics.snapshot()
        assertEquals(300L, (snapshot["counts"] as Map<*, *>)["input"])
        val queue = (snapshot["timings"] as Map<*, *>)["queue"] as Map<*, *>
        assertEquals(179.5, queue["meanMs"])
        assertEquals(287.0, queue["p95Ms"])
        now += 3_000_000_000L
        assertEquals(0.0, (metrics.snapshot()["fps"] as Map<*, *>)["input"])
        assertEquals(300L, (metrics.snapshot()["counts"] as Map<*, *>)["input"])
    }

    @Test fun negativeTimingsAreIgnoredAndRatesCoverTwoSeconds() {
        var now = 0L
        val metrics = VideoPerformance { now }
        metrics.timing("write", -1)
        assertNull((metrics.snapshot()["timings"] as Map<*, *>)["write"])
        repeat(120) { now += 16_666_667; metrics.count("frames") }
        assertEquals(60.0, (metrics.snapshot()["fps"] as Map<*, *>)["frames"] as Double, 1.0)
    }
}
