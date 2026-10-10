package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream

/**
 * The default "back to car" button that CarPlay shows in its app list (/info oemIconLabel and oemIcons).
 *
 * A head unit keeps upstream's BYD logo (res/raw/ic_car_home.png) and label. In phone + browser mode, TiPlay's
 * default for cars such as Tesla, the BYD logo would be wrong, so CarPlay gets a neutral car drawn from the Vehicle
 * settings icon and, while the driver keeps the default label, the label "TiPlay". A custom icon or label always
 * wins. Like the other /info fields, the button changes at the next CarPlay connection.
 */
internal object CarButtonDefaults {
    /** The label phone + browser mode sends instead of the head-unit default [AirPlayPersistence.DEFAULT_OEM_LABEL]. */
    const val PHONE_BROWSER_LABEL = "TiPlay"

    /** The size of the packaged head-unit icon, so the iPhone gets the same icon size in both modes. */
    const val ICON_SIZE_PX = 192

    // A dark car on a light tile, like the packaged icon's white tile; CarPlay rounds the corners itself.
    private const val TILE_COLOR = 0xFFF2F2F7.toInt()
    private const val CAR_COLOR = 0xFF1C1C1E.toInt()
    private const val CAR_SIZE_PX = 120

    /** The label CarPlay shows under the button: [saved], or "TiPlay" in phone + browser mode for the BYD default. */
    fun label(saved: String, phoneBrowser: Boolean): String =
        if (phoneBrowser && saved == AirPlayPersistence.DEFAULT_OEM_LABEL) PHONE_BROWSER_LABEL else saved

    /**
     * PNG bytes of the phone + browser icon, [ICON_SIZE_PX] square; null only if it cannot be drawn, so the
     * caller can keep its packaged icon instead of failing the connection.
     */
    fun phoneBrowserIconPng(context: Context): ByteArray? {
        val bitmap = phoneBrowserIcon(context) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray().takeIf { it.isNotEmpty() } else null
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The phone + browser icon, [ICON_SIZE_PX] square; null only if it cannot be drawn. */
    fun phoneBrowserIcon(context: Context): Bitmap? = runCatching {
        val car = checkNotNull(context.getDrawable(R.drawable.ic_dp_vehicle)).mutate()
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(TILE_COLOR)
        val inset = (ICON_SIZE_PX - CAR_SIZE_PX) / 2
        car.setTint(CAR_COLOR)
        car.setBounds(inset, inset, inset + CAR_SIZE_PX, inset + CAR_SIZE_PX)
        car.draw(canvas)
        bitmap
    }.getOrNull()
}
