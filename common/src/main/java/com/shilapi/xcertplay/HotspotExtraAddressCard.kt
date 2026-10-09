package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The hotspot address rows of the Tesla browser card (phone + browser mode only): the hotspot's current IPv4, the
 * root-only extra address toggle, its value row and what the keeper is doing.
 *
 * The hotspot probe and the root check run on threads. Building the search index ([indexing]) starts neither, and
 * only the toggle makes the first `su` call. The change applies at once, so nothing here marks a reconnect.
 */
internal class HotspotExtraAddressCard(
    private val activity: Activity,
    private val indexing: Boolean,
    private val colors: Colors,
    /** A 14 sp note in the muted colour, not yet added to a parent. */
    private val note: (String) -> TextView,
    private val toggle: (parent: LinearLayout, title: String, description: String, checked: Boolean,
        enabled: Boolean, save: (Boolean) -> Unit) -> Unit,
    /** A "Title · Value" setting row, added to the parent. */
    private val settingRow: (parent: LinearLayout, text: String, click: () -> Unit) -> Unit,
    private val rerender: () -> Unit,
) {
    data class Colors(val muted: Int, val warning: Int, val ready: Int)

    private val app = activity.applicationContext
    private val probes = AtomicInteger()

    fun build(parent: LinearLayout) {
        val enabled = HotspotExtraAddressSettings.enabled(app)
        val checkingRoot = rootCheckInProgress.get()
        val address = HotspotExtraAddressSettings.address(app).hostAddress.orEmpty()
        val current = note(activity.getString(R.string.settings_hotspot_address_checking)).also(parent::addView)
        val blocked = note(activity.getString(R.string.settings_hotspot_address_blocked)).apply {
            setTextColor(colors.warning)
            visibility = View.GONE
        }.also(parent::addView)
        toggle(parent, activity.getString(R.string.settings_hotspot_extra_address),
            activity.getString(R.string.settings_hotspot_extra_address_description, address),
            enabled || checkingRoot, !checkingRoot, ::onToggle)
        settingRow(parent, "${activity.getString(R.string.settings_hotspot_extra_address_value)} · $address", ::editAddress)
        val keeper = note("").also(parent::addView)
        showKeeper(keeper, HotspotExtraAddressKeeper.state)
        if (indexing) return
        probe(current, blocked)
        val listener: (HotspotExtraAddressKeeper.State) -> Unit = { state ->
            activity.runOnUiThread {
                if (keeper.isAttachedToWindow) {
                    showKeeper(keeper, state)
                    probe(current, blocked) // the address list changes with it
                }
            }
        }
        // Only while the rows are on screen; a new render replaces them.
        keeper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                HotspotExtraAddressKeeper.addListener(listener)
                showKeeper(keeper, HotspotExtraAddressKeeper.state) // it may have changed since build()
            }
            override fun onViewDetachedFromWindow(view: View) = HotspotExtraAddressKeeper.removeListener(listener)
        })
    }

    private fun probe(current: TextView, blocked: TextView) {
        val generation = probes.incrementAndGet()
        Thread({
            val hotspot = runCatching { HotspotAddresses.current(app) }.getOrNull()
            activity.runOnUiThread {
                if (generation == probes.get()) showHotspot(current, blocked, hotspot)
            }
        }, "tiplay-hotspot-probe").start()
    }

    private fun showHotspot(current: TextView, blocked: TextView, hotspot: HotspotAddresses.Current?) {
        if (hotspot == null || hotspot.ipv4.isEmpty()) {
            current.text = activity.getString(R.string.settings_hotspot_not_found)
            current.setTextColor(colors.warning)
            blocked.visibility = View.GONE
            return
        }
        current.text = activity.getString(R.string.settings_hotspot_current_address,
            hotspot.ipv4.joinToString(", ") { it.hostAddress.orEmpty() })
        current.setTextColor(colors.muted)
        // With the setting on, the keeper line says what happens instead.
        blocked.visibility = if (hotspot.ipv4.none(HotspotExtraAddress::isCgnat) &&
            !HotspotExtraAddressSettings.enabled(app)) View.VISIBLE else View.GONE
    }

    private fun showKeeper(line: TextView, state: HotspotExtraAddressKeeper.State) {
        val enabled = HotspotExtraAddressSettings.enabled(app)
        val (text, color) = when {
            rootCheckInProgress.get() -> activity.getString(R.string.settings_hotspot_extra_address_root_checking) to colors.muted
            !enabled -> null to colors.muted
            !HotspotExtraAddressSettings.manualHotspotLink(app) -> activity.getString(
                R.string.settings_hotspot_extra_address_manual_only,
                activity.getString(R.string.built_in_car_hotspot)) to colors.warning
            !HotspotExtraAddressKeeper.running -> activity.getString(R.string.settings_hotspot_extra_address_paused) to colors.muted
            else -> when (state) {
                HotspotExtraAddressKeeper.State.Off ->
                    activity.getString(R.string.settings_hotspot_address_checking) to colors.muted
                HotspotExtraAddressKeeper.State.NoHotspot ->
                    activity.getString(R.string.settings_hotspot_extra_address_waiting) to colors.muted
                is HotspotExtraAddressKeeper.State.Added ->
                    activity.getString(R.string.settings_hotspot_extra_address_added, state.iface) to colors.ready
                HotspotExtraAddressKeeper.State.RootDenied ->
                    activity.getString(R.string.settings_hotspot_extra_address_no_root) to colors.warning
                is HotspotExtraAddressKeeper.State.Failed ->
                    activity.getString(R.string.settings_hotspot_extra_address_failed) to colors.warning
            }
        }
        line.text = text.orEmpty()
        line.setTextColor(color)
        line.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun onToggle(on: Boolean) {
        if (!on) {
            HotspotExtraAddressSettings.setEnabled(app, false)
            HotspotExtraAddressSettings.sync(app) // stops the keeper and removes the address
            rerender()
            return
        }
        if (!rootCheckInProgress.compareAndSet(false, true)) return
        rerender()
        // The first su call: the root manager may show its prompt, so it waits off the main thread.
        Thread({
            val granted = try {
                runCatching { HotspotExtraAddressKeeper.checkRoot() }.getOrDefault(false).also { granted ->
                    if (granted) {
                        HotspotExtraAddressSettings.setEnabled(app, true)
                        HotspotExtraAddressSettings.sync(app, retryRootDenied = true)
                    }
                }
            } finally {
                rootCheckInProgress.set(false)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!granted) {
                    Toast.makeText(activity, R.string.settings_hotspot_extra_address_root_refused, Toast.LENGTH_LONG).show()
                }
                rerender()
            }
        }, "tiplay-root-check").start()
    }

    private fun editAddress() {
        val saved = HotspotExtraAddressSettings.address(app)
        val input = EditText(activity).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(saved.hostAddress)
            hint = HotspotExtraAddress.DEFAULT
        }
        val padding = (24 * activity.resources.displayMetrics.density).roundToInt()
        val body = FrameLayout(activity).apply {
            setPadding(padding, padding / 3, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_hotspot_extra_address_value)
            .setMessage(R.string.settings_hotspot_extra_address_value_hint)
            .setView(body)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = HotspotExtraAddress.parse(input.text.toString().trim())
                if (address == null) {
                    input.error = activity.getString(R.string.settings_hotspot_extra_address_invalid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (address != saved) {
                    HotspotExtraAddressSettings.saveAddress(app, address)
                    HotspotExtraAddressSettings.sync(app) // a running keeper moves to the new address at once
                }
                rerender()
            }
        }
        dialog.show()
    }

    companion object {
        /** Process-wide, so a render during the check keeps the switch busy. */
        internal val rootCheckInProgress = AtomicBoolean(false)
    }
}
