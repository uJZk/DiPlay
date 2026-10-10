package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** Additional pages belong to the host menu; controls owned by that menu stay there. */
internal object CarPlaySettingsNavigation {
    const val MENU = "carplay_settings_menu"
    private const val CATEGORY = "carplay_settings_category"

    fun menuIntent(context: Context) = Intent(context, CarPlayHostActivity::class.java)
        .putExtra(MENU, true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    fun category(intent: Intent): SettingsCategory? = intent.getStringExtra(CATEGORY)
        ?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name && it != SettingsCategory.OVERVIEW } }

    fun pageIntent(context: Context, category: SettingsCategory) = Intent(context, DiPlayActivity::class.java)
        .putExtra("page", "settings").putExtra(MENU, true).putExtra(CATEGORY, category.name)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private val hostSections = setOf(SettingsSection.CONNECTION_SETUP, SettingsSection.CARPLAY_CONTROLS,
        SettingsSection.CAR_BUTTON, SettingsSection.LOCATION)

    fun additionalSections(sections: Set<SettingsSection>) = (sections - hostSections).filterTo(linkedSetOf(), BrowserSettingsPolicy::shows)

    fun addPage(parent: LinearLayout, category: SettingsCategory, open: (SettingsCategory) -> Unit) {
        val context = parent.context
        val title = context.getString(when (category) {
            SettingsCategory.CONNECTION -> R.string.connection
            SettingsCategory.DISPLAY -> R.string.settings_display
            SettingsCategory.AUDIO -> R.string.audio
            SettingsCategory.NAVIGATION -> R.string.settings_navigation
            SettingsCategory.VEHICLE -> R.string.settings_vehicle
            SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
            SettingsCategory.ADVANCED -> R.string.settings_advanced
            SettingsCategory.OVERVIEW -> error("Overview is not an additional settings page")
        })
        parent.addView(Button(context).apply {
            text = context.getString(R.string.settings_more_options, title)
            isAllCaps = false
            setOnClickListener { open(category) }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (12 * context.resources.displayMetrics.density).toInt()
        })
    }
}
