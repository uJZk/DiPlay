package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
class CarPlaySettingsNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun displayPageDoesNotExposeAndroidDisplayOrAppearanceControls() {
        val screen = open(SettingsCategory.DISPLAY)
        val labels = labels(screen)
        assertFalse(labels.any { it.startsWith(screen.getString(R.string.carplay_night_mode)) })
        assertFalse(labels.any { it.startsWith(screen.getString(R.string.settings_interface_size)) })
        for (id in listOf(R.string.resolution, R.string.frame_rate, R.string.carplay_size)) {
            assertFalse("Duplicate host control: ${screen.getString(id)}",
                labels.any { it.startsWith(screen.getString(id)) })
        }
        assertFalse(labels.contains(screen.getString(R.string.settings_overview)))
        assertTrue(labels.contains(screen.getString(R.string.settings_search)))
    }

    @Test fun connectionPageRetainsBrowserAndAutomationWithoutAnotherSetupWizard() {
        val screen = open(SettingsCategory.CONNECTION)
        val labels = labels(screen)
        assertFalse(labels.contains(screen.getString(R.string.settings_phone_browser_mode)))
        assertTrue(labels.contains(screen.getString(R.string.connect_when_diplay_opens)))
        assertFalse(labels.contains(screen.getString(R.string.open_connection_setup)))
        assertFalse(labels.contains(screen.getString(R.string.open_after_the_car_starts)))
    }

    @Test fun advancedPageKeepsAdditionalFeaturesWithoutDuplicatingHevcOrChannelMapping() {
        val screen = open(SettingsCategory.ADVANCED)
        val labels = labels(screen)
        assertFalse(labels.contains(screen.getString(R.string.call_echo_cancellation)))
        assertFalse(labels.contains(screen.getString(R.string.split_screen_areas)))
        assertFalse(labels.contains(screen.getString(R.string.efficient_video)))
        assertFalse(labels.contains(screen.getString(R.string.advanced_audio_channel_mapping)))
    }

    @Test fun backReturnsDirectlyToTheHostSettingsMenu() {
        val screen = open(SettingsCategory.AUDIO)
        descendants(screen.window.decorView).filterIsInstance<Button>()
            .single { it.text == screen.getString(R.string.back) }.performClick()
        val intent = shadowOf(screen).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(CarPlaySettingsNavigation.MENU, false))
        assertTrue(screen.isFinishing)
    }

    @Test fun searchIndexesAdditionalPagesWithoutReintroducingTheOldOverviewOrResolutionControl() {
        val screen = open(SettingsCategory.DISPLAY)
        @Suppress("UNCHECKED_CAST")
        val results = screen.javaClass.getDeclaredMethod("buildSettingsSearchIndex")
            .apply { isAccessible = true }.invoke(screen) as List<DiPlayActivity.SettingsSearchResult>
        assertFalse(results.any { it.title == screen.getString(R.string.settings_phone_browser_mode) })
        assertFalse(results.any { it.category == SettingsCategory.OVERVIEW })
        assertFalse(results.any { it.title.startsWith(screen.getString(R.string.resolution)) })
    }

    @Test fun homeSettingsButtonOpensTheHostMenu() {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get().also { activity = it }
        descendants(screen.window.decorView).filterIsInstance<Button>()
            .single { it.text == screen.getString(R.string.settings) }.performClick()
        val intent = shadowOf(screen).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(CarPlaySettingsNavigation.MENU, false))
    }

    private fun open(category: SettingsCategory) = Robolectric.buildActivity(DiPlayActivity::class.java,
        CarPlaySettingsNavigation.pageIntent(context, category)).setup().get().also { activity = it }

    private fun labels(screen: DiPlayActivity) = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
