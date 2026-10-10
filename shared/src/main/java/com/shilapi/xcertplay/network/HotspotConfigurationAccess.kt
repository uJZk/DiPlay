package com.shilapi.xcertplay.network

import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Uses an existing grant only; this read never prompts for Shizuku authorization. */
object HotspotConfigurationAccess {
    fun read(): Any? = runCatching {
        if (!ShizukuSystem.running() || !ShizukuSystem.permissionGranted()) return@runCatching null
        val binder = SystemServiceHelper.getSystemService("wifi") ?: return@runCatching null
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(binder))
        service.javaClass.getMethod("getSoftApConfiguration").invoke(service)
    }.getOrNull()
}
