package com.shilapi.xcertplay.network

import java.io.Closeable
import java.net.Inet4Address

/**
 * How [HotspotExtraAddressKeeper] puts the extra address on the hotspot interface: as root ([RootHotspotAddressBackend])
 * or as the ADB shell user through Shizuku ([ShizukuHotspotAddressBackend]). Calls are blocking and run on the keeper's
 * worker thread; the keeper checks the interface afterwards, so a backend only reports how its call ended.
 */
interface HotspotAddressBackend {
    /** Distinguishes backends for the keeper and the logs: "root" or "shizuku". */
    val id: String

    /** Adds [address]/32 to [iface] (a validated interface name). */
    fun add(iface: String, address: Inet4Address): Result

    /** Removes the address once, best effort; false when it was not removed or this backend cannot remove it. */
    fun remove(iface: String, address: Inet4Address): Boolean

    /** Calls [onChange] when the backend's own state changes (Shizuku starts or stops); null when it has none. */
    fun watch(onChange: () -> Unit): Closeable? = null

    sealed interface Result {
        /** The call succeeded; the keeper confirms the address on the interface. */
        data object Done : Result
        /** The system reported that the address is already there ("File exists"). */
        data object AlreadyPresent : Result
        /** Root was refused, or the ROM does not let the shell user change addresses: stop until the driver chooses again. */
        data object Denied : Result
        /** Shizuku is not running (or stopped during the call); TiPlay keeps watching for it. */
        data object Unavailable : Result
        /** Shizuku runs but has not allowed TiPlay yet; only the driver can allow it, from the settings. */
        data object PermissionNeeded : Result
        data class Failed(val reason: String) : Result
    }
}

/** The root backend: `ip -4 addr replace|del … /32 dev <iface>` through [RootShell]. */
class RootHotspotAddressBackend(
    private val shell: (String, Long) -> RootShell.Result = { script, timeout -> RootShell.run(script, timeout) },
) : HotspotAddressBackend {
    override val id: String get() = ID

    override fun add(iface: String, address: Inet4Address): HotspotAddressBackend.Result =
        when (val result = shell(HotspotExtraAddress.addScript(iface, address), RootShell.DEFAULT_TIMEOUT_MILLIS)) {
            RootShell.Result.Unavailable -> HotspotAddressBackend.Result.Denied
            RootShell.Result.TimedOut -> HotspotAddressBackend.Result.Failed("root shell timed out")
            is RootShell.Result.Done -> if (result.exitCode == 0) HotspotAddressBackend.Result.Done
                else HotspotAddressBackend.Result.Failed("ip exited with ${result.exitCode} on $iface")
        }

    override fun remove(iface: String, address: Inet4Address): Boolean {
        val result = shell(HotspotExtraAddress.deleteScript(iface, address), RootShell.DEFAULT_TIMEOUT_MILLIS)
        return result is RootShell.Result.Done && result.exitCode == 0
    }

    companion object {
        const val ID = "root"
    }
}
