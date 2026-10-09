// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/main/java/com/shilapi/xcertplay/web/VideoPerformance.kt at commit c1bd077. Modified for TiPlay, 2026-10.
package com.shilapi.xcertplay.web

/** Fixed-size recent samples, cumulative counters, and monotonic rates; no per-frame logging. */
internal class VideoPerformance(private val now: () -> Long = System::nanoTime) {
    private class Samples {
        val values = DoubleArray(240)
        var count = 0
        var cursor = 0
        fun add(value: Double) { values[cursor] = value; cursor = (cursor + 1) % values.size; count = minOf(count + 1, values.size) }
        fun summary(): Map<String, Double>? {
            if (count == 0) return null
            val sorted = values.copyOf(count).sorted()
            return mapOf("meanMs" to sorted.average(), "p95Ms" to sorted[kotlin.math.ceil(count * .95).toInt() - 1])
        }
    }
    private val timings = linkedMapOf<String, Samples>()
    private val counters = linkedMapOf<String, Long>()
    private val rates = linkedMapOf<String, ArrayDeque<Long>>()
    @Synchronized fun count(name: String) {
        counters[name] = (counters[name] ?: 0) + 1
        val time = now()
        rates.getOrPut(name) { ArrayDeque() }.apply {
            addLast(time)
            while (size > 240 || firstOrNull()?.let { time - it > 2_000_000_000 } == true) removeFirst()
        }
    }
    @Synchronized fun timing(name: String, nanos: Long) {
        if (nanos >= 0) timings.getOrPut(name) { Samples() }.add(nanos / 1_000_000.0)
    }
    @Synchronized fun snapshot(): Map<String, Any> {
        val time = now()
        return mapOf("counts" to counters.toMap(), "fps" to rates.mapValues { (_, times) ->
            times.count { time - it <= 2_000_000_000 } / 2.0
        }, "timings" to timings.mapValues { it.value.summary() })
    }
}
