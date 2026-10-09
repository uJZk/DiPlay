package com.shilapi.xcertplay.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RootShellTest {
    @Test fun theScriptIsFollowedByItsExitStatusBehindTheMarker() {
        assertEquals(
            "id -u\nstatus=\$?\nprintf '\\n%s%s\\n' 'TIPLAY_ROOT_EXIT:' \"\$status\"\nexit\n",
            RootShell.wrap("id -u"),
        )
    }

    @Test fun onlyTheLastWholeMarkerLineCounts() {
        assertEquals(RootShell.Result.Done(0, "0"), RootShell.parse("0\n\nTIPLAY_ROOT_EXIT:0\n"))
        assertEquals(RootShell.Result.Done(2, "RTNETLINK answers: Operation not permitted"),
            RootShell.parse("RTNETLINK answers: Operation not permitted\n\nTIPLAY_ROOT_EXIT:2\n"))
        assertEquals(RootShell.Result.Done(1, "early\nTIPLAY_ROOT_EXIT:0\nlater"),
            RootShell.parse("early\nTIPLAY_ROOT_EXIT:0\nlater\nTIPLAY_ROOT_EXIT:1\r\n"))
        listOf(
            "", "Permission denied", "su: not found", "TIPLAY_ROOT_EXIT:", "TIPLAY_ROOT_EXIT:x",
            "TIPLAY_ROOT_EXIT:0 trailing", "xTIPLAY_ROOT_EXIT:0", "DIPLAY_HOTSPOT_EXIT:0", "TIPLAY_ROOT_EXIT:1234",
        ).forEach { assertEquals(it, RootShell.Result.Unavailable, RootShell.parse(it)) }
    }

    @Test fun scriptsWithShellSyntaxNeverReachTheShell() {
        listOf("id; reboot", "id && reboot", "id | sh", "\$(reboot)", "`reboot`", "id\nreboot", "id > /x",
            "echo 'x'", "echo \"x\"", "id #", "id &", "a=b", "", "x".repeat(257))
            .forEach { script ->
                assertThrows(script, IllegalArgumentException::class.java) {
                    RootShell.execute(listOf("/nonexistent/su"), script, 1_000)
                }
            }
    }

    @Test fun missingSuIsUnavailable() {
        assertEquals(RootShell.Result.Unavailable, RootShell.execute(listOf("/nonexistent/su"), "id -u", 1_000))
    }

    @Test fun aShellThatExitsWithoutRunningTheScriptIsUnavailable() {
        assumeTrue(File("/bin/true").canExecute())
        assertEquals(RootShell.Result.Unavailable, RootShell.execute(listOf("/bin/true"), "id -u", 2_000))
    }

    @Test fun aRealShellReportsTheScriptsOutputAndStatus() {
        assumeTrue(File("/bin/sh").canExecute())
        val result = RootShell.execute(listOf("/bin/sh"), "id -u", 5_000) as RootShell.Result.Done
        assertEquals(0, result.exitCode)
        assertTrue(result.output, result.output.matches(Regex("[0-9]+")))
        val failed = RootShell.execute(listOf("/bin/sh"), "ls /nonexistent-tiplay-path", 5_000) as RootShell.Result.Done
        assertTrue(failed.exitCode != 0)
    }

    @Test fun aHangingShellIsStoppedAtTheTimeout() {
        assumeTrue(File("/bin/sh").canExecute())
        val started = System.nanoTime()
        assertEquals(RootShell.Result.TimedOut, RootShell.execute(listOf("/bin/sh"), "sleep 30", 300))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test fun timeoutsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { RootShell.execute(listOf("/nonexistent/su"), "id -u", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RootShell.execute(listOf("/nonexistent/su"), "id -u", 120_000)
        }
    }
}
