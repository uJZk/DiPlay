package com.shilapi.xcertplay.network

import android.os.DeadObjectException
import com.shilapi.xcertplay.network.HotspotAddressBackend.Result
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotAddressBackendTest {
    private val address = HotspotExtraAddress.parse("100.109.220.253")!!

    @Test fun rootRunsTheSameScriptsAsBefore() {
        val scripts = mutableListOf<Pair<String, Long>>()
        var answer: RootShell.Result = RootShell.Result.Done(0, "")
        val root = RootHotspotAddressBackend { script, timeout -> scripts += script to timeout; answer }

        assertEquals(Result.Done, root.add("wlan2", address))
        assertTrue(root.remove("wlan2", address))

        assertEquals(listOf(
            "/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2" to RootShell.DEFAULT_TIMEOUT_MILLIS,
            "/system/bin/ip -4 addr del 100.109.220.253/32 dev wlan2" to RootShell.DEFAULT_TIMEOUT_MILLIS,
        ), scripts)
        assertEquals("root", root.id)
    }

    @Test fun rootResultsMapToWhatTheKeeperDoes() {
        var answer: RootShell.Result = RootShell.Result.Unavailable
        val root = RootHotspotAddressBackend { _, _ -> answer }

        assertEquals(Result.Denied, root.add("wlan2", address))
        answer = RootShell.Result.TimedOut
        assertEquals(Result.Failed("root shell timed out"), root.add("wlan2", address))
        answer = RootShell.Result.Done(2, "RTNETLINK answers: Operation not permitted")
        assertEquals(Result.Failed("ip exited with 2 on wlan2"), root.add("wlan2", address))
        assertFalse(root.remove("wlan2", address))
        answer = RootShell.Result.Unavailable
        assertFalse(root.remove("wlan2", address))
    }

    @Test fun shizukuCallsTheAliasWithAThirtyTwoBitPrefix() {
        val shizuku = FakeShizuku()
        val backend = ShizukuHotspotAddressBackend(shizuku)

        assertEquals(Result.Done, backend.add("wlan2", address))

        assertEquals(listOf(Triple("wlan2:tp", address, 32)), shizuku.calls)
        assertEquals("shizuku", backend.id)
    }

    @Test fun shizukuNeverCallsWithoutAnAliasThatFits() {
        val shizuku = FakeShizuku()
        val backend = ShizukuHotspotAddressBackend(shizuku)

        assertTrue(backend.add("abcdefghijklm", address) is Result.Failed)

        assertTrue(shizuku.calls.isEmpty())
        assertEquals(Result.Done, backend.add("abcdefghijkl", address))
        assertEquals("abcdefghijkl:tp", shizuku.calls.single().first)
    }

    @Test fun shizukuThatIsNotRunningOrNotAllowedIsNotCalled() {
        val shizuku = FakeShizuku()
        val backend = ShizukuHotspotAddressBackend(shizuku)

        shizuku.running = false
        assertEquals(Result.Unavailable, backend.add("wlan2", address))
        shizuku.running = true
        shizuku.granted = false
        assertEquals(Result.PermissionNeeded, backend.add("wlan2", address))
        // Shizuku stops between the two checks.
        shizuku.permissionError = RuntimeException(DeadObjectException())
        assertEquals(Result.Unavailable, backend.add("wlan2", address))

        assertTrue(shizuku.calls.isEmpty())
    }

    @Test fun fileExistsMeansTheAddressIsAlreadyThere() {
        val shizuku = FakeShizuku()
        val backend = ShizukuHotspotAddressBackend(shizuku)
        // As the framework proxy throws it through reflection.
        shizuku.error = InvocationTargetException(IllegalStateException(
            "android.os.ServiceSpecificException: File exists (code 17)"))

        assertEquals(Result.AlreadyPresent, backend.add("wlan2", address))
        shizuku.error = IllegalStateException(RuntimeException("EEXIST"))
        assertEquals(Result.AlreadyPresent, backend.add("wlan2", address))
    }

    @Test fun aSecurityExceptionMeansTheRomBlocksTheShellUser() {
        val shizuku = FakeShizuku()
        shizuku.error = InvocationTargetException(SecurityException("NETWORK_STACK or CONNECTIVITY_INTERNAL required"))

        assertEquals(Result.Denied, ShizukuHotspotAddressBackend(shizuku).add("wlan2", address))
    }

    @Test fun shizukuDyingDuringTheCallMeansItIsNotRunning() {
        val shizuku = FakeShizuku()
        val backend = ShizukuHotspotAddressBackend(shizuku)
        // Shizuku.transactRemote wraps the RemoteException in a RuntimeException.
        shizuku.error = InvocationTargetException(RuntimeException(DeadObjectException()))
        assertEquals(Result.Unavailable, backend.add("wlan2", address))

        // Its binder went away: requireService() throws before any transaction.
        shizuku.error = InvocationTargetException(IllegalStateException("binder haven't been received"))
        shizuku.stopsDuringTheCall = true
        assertEquals(Result.Unavailable, backend.add("wlan2", address))
    }

    @Test fun otherErrorsAreFailuresTheKeeperBacksOffFrom() {
        val shizuku = FakeShizuku()
        shizuku.error = InvocationTargetException(IllegalStateException("Null LinkAddress given"))

        assertEquals(Result.Failed("shizuku call failed: IllegalStateException"),
            ShizukuHotspotAddressBackend(shizuku).add("wlan2", address))
        shizuku.error = ClassNotFoundException("android.os.INetworkManagementService")
        assertEquals(Result.Failed("shizuku call failed: ClassNotFoundException"),
            ShizukuHotspotAddressBackend(shizuku).add("wlan2", address))
    }

    @Test fun shizukuCannotRemoveTheAddress() {
        val shizuku = FakeShizuku()

        assertFalse(ShizukuHotspotAddressBackend(shizuku).remove("wlan2", address))

        assertTrue(shizuku.calls.isEmpty())
    }

    @Test fun shizukuEventsReachTheWatcherUntilClosed() {
        val shizuku = FakeShizuku()
        var changes = 0
        val watch = ShizukuHotspotAddressBackend(shizuku).watch { changes++ }

        shizuku.onChange?.invoke()
        watch.close()

        assertEquals(1, changes)
        assertEquals(null, shizuku.onChange)
    }

    internal class FakeShizuku : ShizukuAccess {
        @Volatile var running = true
        @Volatile var granted = true
        @Volatile var error: Throwable? = null
        @Volatile var permissionError: Throwable? = null
        @Volatile var stopsDuringTheCall = false
        @Volatile var onChange: (() -> Unit)? = null
        @Volatile var onCall: (String, Inet4Address) -> Unit = { _, _ -> }
        val calls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Inet4Address, Int>>()

        override fun running(): Boolean = running
        override fun permissionGranted(): Boolean = permissionError?.let { throw it } ?: granted
        override fun setInterfaceConfig(name: String, address: Inet4Address, prefixLength: Int) {
            calls += Triple(name, address, prefixLength)
            if (stopsDuringTheCall) running = false
            error?.let { throw it }
            onCall(name, address)
        }
        override fun watch(onChange: () -> Unit): Closeable {
            this.onChange = onChange
            return Closeable { this.onChange = null }
        }
    }
}
