package com.shilapi.xcertplay

import android.graphics.BitmapFactory
import android.graphics.Color
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarButtonDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun phoneBrowserModeRenamesOnlyTheBydDefault() {
        assertEquals("TiPlay", CarButtonDefaults.label(AirPlayPersistence.DEFAULT_OEM_LABEL, phoneBrowser = true))
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL,
            CarButtonDefaults.label(AirPlayPersistence.DEFAULT_OEM_LABEL, phoneBrowser = false))
        assertEquals("My car", CarButtonDefaults.label("My car", phoneBrowser = true))
        assertEquals("My car", CarButtonDefaults.label("My car", phoneBrowser = false))
    }

    @Test fun thePhoneIconIsADarkCarOnALightTileOfThePackagedIconSize() {
        val png = requireNotNull(CarButtonDefaults.phoneBrowserIconPng(context))
        val packaged = context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
        assertFalse(png.contentEquals(packaged))

        val icon = BitmapFactory.decodeByteArray(png, 0, png.size)
        val bydIcon = BitmapFactory.decodeByteArray(packaged, 0, packaged.size)
        assertEquals(bydIcon.width, icon.width)
        assertEquals(bydIcon.height, icon.height)
        assertEquals(CarButtonDefaults.ICON_SIZE_PX, icon.width)

        // ic_dp_vehicle (24 x 24) drawn 120 px wide in the middle: 5 px per unit from (36, 36).
        fun at(x: Float, y: Float) = icon.getPixel((36 + x * 5).toInt(), (36 + y * 5).toInt())
        fun light(color: Int) = Color.red(color) > 200 && Color.green(color) > 200 && Color.blue(color) > 200
        fun dark(color: Int) = Color.red(color) < 64 && Color.green(color) < 64 && Color.blue(color) < 64
        assertTrue("tile", light(icon.getPixel(0, 0)))
        assertTrue("tile", light(icon.getPixel(icon.width - 1, icon.height - 1)))
        assertTrue("car body", dark(at(12f, 15f)))
        assertTrue("windscreen", light(at(12f, 9f)))
        assertEquals("opaque, like the packaged icon", 255, Color.alpha(icon.getPixel(0, 0)))
    }
}
