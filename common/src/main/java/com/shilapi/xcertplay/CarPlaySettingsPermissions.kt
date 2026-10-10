package com.shilapi.xcertplay

import android.widget.Toast
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Keeps the car-hotspot boot permission flow when its switch lives in the host menu. */
internal object CarPlaySettingsPermissions {
    private val pending = java.util.WeakHashMap<ComponentActivity, Boolean>()
    fun readyToSave(activity: ComponentActivity, bootStart: Boolean, mode: WirelessHotspotMode,
        onReady: () -> Unit): Boolean {
        val permission = CarHotspotSetup.Permission.BOOT_LAUNCH
        if (!bootStart || mode != WirelessHotspotMode.MANUAL || !CarHotspotSettings.enabled(activity) ||
            permission.granted(activity)) return true
        if (pending[activity] == true) return false
        pending[activity] = true
        Toast.makeText(activity, R.string.adb_checking_may_ask, Toast.LENGTH_SHORT).show()
        Thread({
            val ready = runCatching {
                CarHotspotSetup.grant(activity.applicationContext, listOf(permission)) == LocalAdb.Access.READY &&
                    permission.granted(activity)
            }.getOrDefault(false)
            activity.runOnUiThread {
                pending.remove(activity)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (ready) onReady()
                    else Toast.makeText(activity, R.string.adb_not_approved, Toast.LENGTH_LONG).show()
                }
            }
        }, "TiPlay-settings-permission").start()
        return false
    }
}
