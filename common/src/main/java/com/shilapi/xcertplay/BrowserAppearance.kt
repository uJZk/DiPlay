package com.shilapi.xcertplay

/** Only an authenticated browser reports the appearance; Android's theme is unrelated. */
internal object BrowserAppearance {
    @Volatile var night = false
        private set

    fun update(stats: Map<String, Any?>): Boolean? {
        val next = stats["themeDark"] as? Boolean ?: return null
        night = next
        return next
    }
}
