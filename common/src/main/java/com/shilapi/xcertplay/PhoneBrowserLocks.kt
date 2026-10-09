package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Phone + browser mode (integration E3): while the session service runs, the CPU stays awake and Wi-Fi stays in its
 * low-latency mode (high-performance before Android 10), so the car's browser keeps getting video and the iPhone's
 * session keeps running with the phone's screen off. Head-unit mode holds neither lock.
 *
 * Both locks are not reference counted, so [hold] and [release] are idempotent; every stop path of the service
 * calls [release].
 */
internal class PhoneBrowserLocks(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val held: Boolean get() = wakeLock?.isHeld == true || wifiLock?.isHeld == true

    /** Holds both locks in phone + browser mode, and releases them otherwise. */
    fun update(phoneBrowser: Boolean) {
        if (phoneBrowser) hold() else release()
    }

    // The session has no fixed length; ACTION_STOP, onTaskRemoved and onDestroy release the lock.
    @SuppressLint("WakelockTimeout")
    fun hold() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock = context.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply { setReferenceCounted(false); acquire() }
            }
            if (wifiLock?.isHeld != true) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifi?.createWifiLock(wifiMode(Build.VERSION.SDK_INT), WIFI_LOCK_TAG)
                    ?.apply { setReferenceCounted(false); acquire() }
            }
        } catch (error: RuntimeException) {
            // A missing lock costs smoothness with the screen off, never the session.
            Log.w(TAG, "could not hold the phone + browser locks", error)
        }
    }

    fun release() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLock = null
    }

    internal companion object {
        private const val TAG = "TiPlayLocks"
        private const val WAKE_LOCK_TAG = "TiPlay:browser-link"
        private const val WIFI_LOCK_TAG = "TiPlay:browser-link"

        /** Low latency from Android 10; high performance before it (deprecated later, but the only mode there). */
        @Suppress("DEPRECATION")
        fun wifiMode(sdk: Int): Int =
            if (sdk >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
