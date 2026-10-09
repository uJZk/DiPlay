package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17's local network protection (developer.android.com/privacy-and-security/local-network-permission): an app
 * that targets API 37 needs the runtime permission `ACCESS_LOCAL_NETWORK` (in the Nearby devices group) to accept the
 * car browser's connections and the iPhone's on the hotspot. Before Android 17, or for a lower target, INTERNET grants
 * it implicitly, and the docs say not to request it.
 */
internal object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val SDK = 37

    enum class State(val wire: String) { NOT_NEEDED("not-needed"), GRANTED("granted"), DENIED("denied") }

    /** True on Android 17+ for an app that targets it: the permission must be granted at run time. */
    fun applies(context: Context): Boolean =
        Build.VERSION.SDK_INT >= SDK && context.applicationInfo.targetSdkVersion >= SDK

    fun state(context: Context): State = when {
        !applies(context) -> State.NOT_NEEDED
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED -> State.GRANTED
        else -> State.DENIED
    }

    /** The permission to request with the connection permissions: only in phone + browser mode, and only where it applies. */
    fun required(context: Context, phoneBrowser: Boolean): List<String> =
        if (phoneBrowser && applies(context)) listOf(PERMISSION) else emptyList()
}
