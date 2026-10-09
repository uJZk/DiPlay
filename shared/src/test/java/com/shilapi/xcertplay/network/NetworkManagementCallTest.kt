package com.shilapi.xcertplay.network

import android.net.LinkAddress
import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import android.system.OsConstants
import com.shilapi.xcertplay.network.HotspotAddressBackend.Result
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Shizuku call through the framework's own INetworkManagementService proxy, against a recording binder. */
@RunWith(RobolectricTestRunner::class)
class NetworkManagementCallTest {
    private val address = InetAddress.getByName("100.109.220.253") as Inet4Address

    /** Stands in for network_management behind Shizuku: records the transaction and answers like the service. */
    private class FakeNetworkManagement(private val answer: (Parcel) -> Unit) : Binder() {
        val codes = mutableListOf<Int>()
        val names = mutableListOf<String?>()
        val links = mutableListOf<LinkAddress?>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            data.enforceInterface(DESCRIPTOR)
            names += data.readString()
            val config = if (data.readInt() != 0) {
                val creator = Class.forName("android.net.InterfaceConfiguration").getField("CREATOR").get(null)
                (creator as Parcelable.Creator<*>).createFromParcel(data)
            } else null
            links += config?.let { it.javaClass.getMethod("getLinkAddress").invoke(it) as LinkAddress? }
            reply?.let(answer)
            return true
        }
    }

    @Test
    @Config(sdk = [29, 34])
    fun theCallUsesTheFrameworksTransactionCodeAndTheAlias() {
        val service = FakeNetworkManagement { it.writeNoException() }

        NetworkManagementCall { service }.setInterfaceConfig("wlan2:tp", address, 32)

        // Read from the framework's own Stub: TiPlay never hard-codes it.
        val expected = Class.forName("android.os.INetworkManagementService\$Stub")
            .getDeclaredField("TRANSACTION_setInterfaceConfig").apply { isAccessible = true }.getInt(null)
        assertEquals(listOf(expected), service.codes)
        assertEquals(listOf<String?>("wlan2:tp"), service.names)
        val link = service.links.single()!!
        assertEquals(address, link.address)
        assertEquals(32, link.prefixLength)
    }

    @Test
    @Config(sdk = [34])
    fun theServicesExceptionsReachTheBackendAsTheyDidOnThePhone() {
        fun backendFor(answer: (Parcel) -> Unit): ShizukuHotspotAddressBackend {
            val call = NetworkManagementCall { FakeNetworkManagement(answer) }
            return ShizukuHotspotAddressBackend(object : ShizukuAccess {
                override fun running() = true
                override fun permissionGranted() = true
                override fun setInterfaceConfig(name: String, address: Inet4Address, prefixLength: Int) =
                    call.setInterfaceConfig(name, address, prefixLength)
                override fun watch(onChange: () -> Unit) = Closeable {}
            })
        }

        assertEquals(Result.Done, backendFor { it.writeNoException() }.add("wlan2", address))
        assertEquals(Result.AlreadyPresent, backendFor {
            it.writeException(IllegalStateException("android.os.ServiceSpecificException: File exists (code 17)"))
        }.add("wlan2", address))
        assertEquals(Result.Denied, backendFor {
            it.writeException(SecurityException("NETWORK_STACK or CONNECTIVITY_INTERNAL required"))
        }.add("wlan2", address))
    }

    @Test
    @Config(sdk = [34])
    fun noServiceOrAnUnsafeValueIsRefusedBeforeAnyTransaction() {
        assertThrows(IllegalStateException::class.java) {
            NetworkManagementCall { null }.setInterfaceConfig("wlan2:tp", address, 32)
        }
        val service = FakeNetworkManagement { it.writeNoException() }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkManagementCall { service }.setInterfaceConfig("wlan2;reboot:tp", address, 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkManagementCall { service }.setInterfaceConfig("wlan2:tp",
                InetAddress.getByName("10.176.81.135") as Inet4Address, 32)
        }
        // Without the alias netd would clear the hotspot's own IPv4 first: never sent, whoever calls.
        for (name in listOf("wlan2", "wlan2:", "wlan2:xy", "wlan2:tp:tp", ":tp", "abcdefghijklm:tp")) {
            assertThrows(name, IllegalArgumentException::class.java) {
                NetworkManagementCall { service }.setInterfaceConfig(name, address, 32)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkManagementCall { service }.setInterfaceConfig("wlan2:tp", address, 8)
        }
        assertTrue(service.codes.isEmpty())
    }

    @Test
    @Config(sdk = [28, 29, 30, 34])
    fun theLinkAddressIsBuiltFromPublicApisOnEveryVersion() {
        val cgnat = linkAddress(address, 32)
        assertEquals(address, cgnat.address)
        assertEquals(32, cgnat.prefixLength)
        assertEquals(0, cgnat.flags)
        assertEquals(OsConstants.RT_SCOPE_UNIVERSE, cgnat.scope)

        val linkLocal = InetAddress.getByName("169.254.220.253") as Inet4Address
        assertEquals(OsConstants.RT_SCOPE_LINK, linkAddress(linkLocal, 32).scope)
        assertEquals("100.109.220.253/32", cgnat.toString())
    }

    private companion object {
        const val DESCRIPTOR = "android.os.INetworkManagementService"
    }
}
