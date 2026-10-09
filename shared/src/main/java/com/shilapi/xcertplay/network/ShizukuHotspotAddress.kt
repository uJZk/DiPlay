package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.LinkAddress
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.system.OsConstants
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The Shizuku backend: no root. TiPlay calls `INetworkManagementService.setInterfaceConfig("<iface>:tp", <address>/32)`
 * on the `network_management` service through [ShizukuBinderWrapper], so the call runs as the user Shizuku runs as
 * (the ADB shell user, or root with Sui). The shell user holds CONNECTIVITY_INTERNAL, which the service accepts; it has
 * no NETWORK_STACK, so it can add the address but never remove it: the address goes when the hotspot restarts.
 *
 * Never a Shizuku UserService: Shizuku 13.6.0's bindUserService hangs on Android 17 (RikkaApps/Shizuku#2180).
 * Checked on the owner's Android 16 phone with `tools/shell-address/AddAddr.java`; see docs/HOTSPOT_ADDRESS.md.
 */
class ShizukuHotspotAddressBackend internal constructor(private val shizuku: ShizukuAccess) : HotspotAddressBackend {
    constructor() : this(ShizukuSystem)

    override val id: String get() = ID

    override fun add(iface: String, address: Inet4Address): HotspotAddressBackend.Result {
        // Without the alias netd first clears the interface's IPv4, which cuts every device off the hotspot.
        val alias = HotspotExtraAddress.aliasName(iface)
            ?: return HotspotAddressBackend.Result.Failed("interface name too long for the alias")
        if (!runCatching { shizuku.running() }.getOrDefault(false)) return HotspotAddressBackend.Result.Unavailable
        val granted = try {
            shizuku.permissionGranted()
        } catch (error: Throwable) {
            return classify(error)
        }
        if (!granted) return HotspotAddressBackend.Result.PermissionNeeded
        return try {
            shizuku.setInterfaceConfig(alias, address, PREFIX_LENGTH)
            HotspotAddressBackend.Result.Done
        } catch (error: Throwable) {
            classify(error)
        }
    }

    /** The shell user cannot remove an address (that needs NETWORK_STACK); it goes when the hotspot restarts. */
    override fun remove(iface: String, address: Inet4Address): Boolean = false

    /** Calls [onChange] when Shizuku starts, stops or answers the permission request. */
    override fun watch(onChange: () -> Unit): Closeable = shizuku.watch(onChange)

    /**
     * How a failed call ended. The permission is checked first, so a SecurityException here comes from the ROM: it does
     * not let the shell user change interface addresses.
     */
    internal fun classify(error: Throwable): HotspotAddressBackend.Result {
        val chain = generateSequence(error) { current ->
            (if (current is InvocationTargetException) current.targetException else current.cause)?.takeIf { it !== current }
        }.take(MAX_CAUSES).toList()
        return when {
            chain.any { it is SecurityException } -> HotspotAddressBackend.Result.Denied
            chain.any { it.message.orEmpty().let { text -> "File exists" in text || "EEXIST" in text } } ->
                HotspotAddressBackend.Result.AlreadyPresent
            chain.any { it is DeadObjectException } || !runCatching { shizuku.running() }.getOrDefault(false) ->
                HotspotAddressBackend.Result.Unavailable
            else -> HotspotAddressBackend.Result.Failed("shizuku call failed: ${chain.last().javaClass.simpleName}")
        }
    }

    companion object {
        const val ID = "shizuku"
        private const val PREFIX_LENGTH = 32
        private const val MAX_CAUSES = 8
    }
}

/** What the Shizuku backend needs from Shizuku and the system; tests replace it. */
internal interface ShizukuAccess {
    /** Shizuku's binder arrived and is alive. */
    fun running(): Boolean
    fun permissionGranted(): Boolean
    /** Throws what the remote call threw. */
    fun setInterfaceConfig(name: String, address: Inet4Address, prefixLength: Int)
    fun watch(onChange: () -> Unit): Closeable
}

/** The real Shizuku. Nothing here runs until the driver chooses the Shizuku method. */
object ShizukuSystem : ShizukuAccess {
    private const val PERMISSION_REQUEST_CODE = 0x7470
    private val call = NetworkManagementCall {
        SystemServiceHelper.getSystemService("network_management")?.let(::ShizukuBinderWrapper)
    }

    /** Shizuku 11 and later, binder received and alive. */
    override fun running(): Boolean = Shizuku.pingBinder() && !Shizuku.isPreV11()

    override fun permissionGranted(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** The driver refused with "don't ask again": only the Shizuku app can allow TiPlay now. */
    fun permissionDeniedForGood(): Boolean = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    /** Shows Shizuku's own dialog; only from a tap in the settings. The answer reaches [watch]ers. */
    fun requestPermission(): Boolean = runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }.isSuccess

    override fun setInterfaceConfig(name: String, address: Inet4Address, prefixLength: Int) =
        call.setInterfaceConfig(name, address, prefixLength)

    override fun watch(onChange: () -> Unit): Closeable {
        val received = Shizuku.OnBinderReceivedListener { onChange() }
        val dead = Shizuku.OnBinderDeadListener { onChange() }
        val answered = Shizuku.OnRequestPermissionResultListener { code, _ -> if (code == PERMISSION_REQUEST_CODE) onChange() }
        Shizuku.addBinderReceivedListener(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(answered)
        return Closeable {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(answered)
        }
    }
}

/**
 * `INetworkManagementService.setInterfaceConfig(name, cfg)` on the binder [binder] returns, through the framework's
 * own AIDL proxy (`Stub.asInterface`), so the transaction code always matches the device: TiPlay never hard-codes one.
 *
 * Non-SDK interfaces (Android 17 hidden API lists, targetSdk 37): `INetworkManagementService$Stub.asInterface` is on
 * the unsupported list, and `setInterfaceConfig`, `InterfaceConfiguration()` and `setLinkAddress` carry
 * `@UnsupportedAppUsage` without `maxTargetSdk`, so apps may call them at any target SDK. The `LinkAddress` constructors
 * are system APIs, which apps may not call; TiPlay builds the value with the public `LinkAddress.CREATOR` instead
 * ([linkAddress]). See docs/HOTSPOT_ADDRESS.md.
 */
internal class NetworkManagementCall(private val binder: () -> IBinder?) {
    @SuppressLint("PrivateApi")
    fun setInterfaceConfig(name: String, address: Inet4Address, prefixLength: Int) {
        // Only ever the labelled alias, whoever calls: on the bare interface netd first clears the hotspot's own IPv4.
        require(HotspotExtraAddress.aliasName(name.substringBefore(':')) == name) {
            "interface name must be <iface>:${HotspotExtraAddress.ALIAS}"
        }
        // A shorter prefix would also route a whole range to the hotspot.
        require(prefixLength == 32) { "prefix length must be 32" }
        require(HotspotExtraAddress.isAllowed(address)) { "address must be in 100.64.0.0/10 or 169.254.0.0/16" }
        val remote = binder() ?: throw IllegalStateException("network_management service not found")
        val service = Class.forName("android.os.INetworkManagementService")
        val stub = Class.forName("android.os.INetworkManagementService\$Stub")
        val configuration = Class.forName("android.net.InterfaceConfiguration")
        val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, remote)
            ?: throw IllegalStateException("network_management has no interface")
        val config = configuration.getConstructor().newInstance()
        configuration.getMethod("setLinkAddress", LinkAddress::class.java).invoke(config, linkAddress(address, prefixLength))
        service.getMethod("setInterfaceConfig", String::class.java, configuration).invoke(proxy, name, config)
    }
}

/**
 * [address]/[prefixLength] as a [LinkAddress], unparcelled through the public `CREATOR` in the layout its
 * `writeToParcel` uses (address bytes, prefix, flags, scope, then two lifetimes that Android 9 and 10 do not read).
 * The public getters confirm the result, so a future layout change fails here instead of sending a wrong address.
 */
internal fun linkAddress(address: Inet4Address, prefixLength: Int): LinkAddress {
    val scope = if (address.isLinkLocalAddress) OsConstants.RT_SCOPE_LINK else OsConstants.RT_SCOPE_UNIVERSE
    val parcel = Parcel.obtain()
    try {
        parcel.writeByteArray(address.address)
        parcel.writeInt(prefixLength)
        parcel.writeInt(0) // flags
        parcel.writeInt(scope)
        parcel.writeLong(LIFETIME_UNKNOWN) // deprecation time
        parcel.writeLong(LIFETIME_UNKNOWN) // expiration time
        parcel.setDataPosition(0)
        val link = LinkAddress.CREATOR.createFromParcel(parcel)
        check(link.address == address && link.prefixLength == prefixLength && link.flags == 0 && link.scope == scope) {
            "LinkAddress parcel layout changed"
        }
        return link
    } finally {
        parcel.recycle()
    }
}

private const val LIFETIME_UNKNOWN = -1L
