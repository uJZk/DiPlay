package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.HotspotConfigurationAccess

/** Read-only. Never enables a hotspot, requests a grant, or logs credentials. */
internal object LocalHotspotSettings {
    class Credentials(val ssid: String, val password: String?) {
        override fun toString() = "Local hotspot credentials (redacted)"
    }

    fun read(context: Context): Credentials? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        configuration(wifi)?.let { return it }
        fromConfiguration(HotspotConfigurationAccess.read())?.let { return it }
        return runCatching {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
                else fromDump(adb.shell("dumpsys wifi").orEmpty())
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun configuration(service: Any?): Credentials? = runCatching {
        if (service == null) return@runCatching null
        val config = service.javaClass.getMethod("getSoftApConfiguration").invoke(service)
        fromConfiguration(config)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun fromConfiguration(config: Any?): Credentials? = runCatching {
        if (config == null) return@runCatching null
        if (config is WifiConfiguration) credentials(config.SSID, config.preSharedKey)
        else {
            val ssid = config.javaClass.getMethod("getSsid").invoke(config) as? String
            val password = runCatching { config.javaClass.getMethod("getPassphrase").invoke(config) as? String }.getOrNull()
            credentials(ssid, password)
        }
    }.getOrNull()

    fun credentials(ssid: String?, password: String?): Credentials? {
        if (ssid.isNullOrBlank() || '\u0000' in ssid || ssid.toByteArray(Charsets.UTF_8).size > 32) return null
        val usable = password?.takeIf {
            it.length in 8..63 && !it.startsWith("<") && it.any { char -> char != '*' && char != '•' }
        }
        return Credentials(ssid, usable)
    }

    /** Only the current AP's record; connected and remembered router names must never be imported. */
    fun fromDump(text: String): Credentials? {
        val name = Regex("(?m)^\\s*mCurrentSoftApConfiguration=ssid=\"([^\"\\r\\n]+)\"")
            .find(text)?.groupValues?.get(1) ?: return null
        return credentials(name, null) // dumpsys masks secrets on many ROMs; keep the manual password.
    }
}
