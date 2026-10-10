package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Optional import alongside the original editable fields. Results never overwrite concurrent typing. */
internal object LocalHotspotAutofill {
    fun attach(parent: LinearLayout, ssid: EditText, password: EditText) {
        val status = TextView(parent.context).apply {
            setText(R.string.settings_hotspot_read_hint)
            setTextColor(ssid.currentTextColor)
        }
        val button = Button(parent.context).apply { setText(R.string.settings_hotspot_read); isAllCaps = false }
        parent.addView(status)
        parent.addView(button)
        var reading = false
        fun read(replace: Boolean) {
            if (reading || !parent.isShown || !parent.isAttachedToWindow) return
            reading = true
            val beforeName = ssid.text.toString()
            val beforePassword = password.text.toString()
            button.isEnabled = false
            Thread({
                val result = LocalHotspotSettings.read(parent.context.applicationContext)
                button.post {
                    reading = false
                    button.isEnabled = true
                    if (!parent.isShown || !parent.isAttachedToWindow) return@post
                    status.setText(if (result == null) R.string.settings_hotspot_read_unavailable else R.string.settings_hotspot_read_hint)
                    if (result != null) {
                        if (ssid.text.toString() == beforeName && (replace || beforeName.isBlank())) ssid.setText(result.ssid)
                        if (password.text.toString() == beforePassword && (replace || beforePassword.isBlank())) {
                            result.password?.let(password::setText)
                        }
                    }
                }
            }, "tiplay-hotspot-read").apply { isDaemon = true; start() }
        }
        button.setOnClickListener { read(true) }
        parent.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { read(false) } }
            override fun onViewDetachedFromWindow(view: View) {}
        })
        parent.post { read(false) }
    }

    fun attach(parent: LinearLayout) {
        fun inputs(view: View): List<EditText> = when (view) {
            is EditText -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
            else -> emptyList()
        }
        val fields = inputs(parent)
        if (fields.size >= 3) attach(parent, fields.first(), fields.last())
    }
}
