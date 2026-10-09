package com.shilapi.xcertplay

import android.content.pm.PermissionInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNetworkPermissionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = LocalNetworkPermission.resetForTest()

    @After fun tearDown() {
        LocalNetworkPermission.resetForTest()
        context.getSharedPreferences("tiplay_browser_link", 0).edit().clear().commit()
    }

    @Test fun itAppliesOnlyFromAndroid17ForAnAppThatTargetsItOnAPlatformThatDefinesIt() {
        var asked = 0
        val defined = { asked++; true }
        assertTrue(LocalNetworkPermission.applies(37, 37, defined))
        assertTrue(LocalNetworkPermission.applies(38, 37, defined))
        assertFalse("A lower target keeps the implicit grant", LocalNetworkPermission.applies(37, 36, defined))
        assertFalse("Android 16 has no such permission", LocalNetworkPermission.applies(36, 37, defined))
        assertEquals("Only the API 37 cases ask the package manager", 2, asked)
        // A build without the feature never defines the permission; requesting it would block the wireless start forever.
        assertFalse(LocalNetworkPermission.applies(37, 37) { false })
    }

    @Test fun thePlatformDefinitionIsCheckedOnceAndCached() {
        assertFalse("Robolectric's platform does not define it", LocalNetworkPermission.isDefined(context))
        LocalNetworkPermission.resetForTest()
        shadowOf(context.packageManager).addPermissionInfo(PermissionInfo().apply {
            name = LocalNetworkPermission.PERMISSION
            packageName = "android"
        })
        assertTrue(LocalNetworkPermission.isDefined(context))
        assertEquals("Below Android 17 nothing is needed", LocalNetworkPermission.State.NOT_NEEDED,
            LocalNetworkPermission.state(context))
        assertEquals(emptyList<String>(), LocalNetworkPermission.required(context, phoneBrowser = true))
    }

    @Test fun theButtonAsksUntilAndroidStopsShowingTheDialogThenOpensTheAppSettings() {
        assertEquals(LocalNetworkPermission.Action.REQUEST, LocalNetworkPermission.action(requestedBefore = false, showRationale = false))
        assertEquals(LocalNetworkPermission.Action.REQUEST, LocalNetworkPermission.action(requestedBefore = true, showRationale = true))
        assertEquals(LocalNetworkPermission.Action.OPEN_APP_SETTINGS,
            LocalNetworkPermission.action(requestedBefore = true, showRationale = false))

        assertFalse(LocalNetworkPermission.requestedBefore(context))
        LocalNetworkPermission.markRequested(context)
        assertTrue(LocalNetworkPermission.requestedBefore(context))
    }
}
