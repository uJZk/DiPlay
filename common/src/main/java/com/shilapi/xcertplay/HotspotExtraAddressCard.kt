package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.DigitsKeyListener
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotAddressVpn
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.network.ShizukuSystem
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The hotspot address rows of the Tesla browser card (phone + browser mode only): the hotspot's current IPv4, the
 * "Hotspot address · Method" chooser, the address row, and what the chosen method is doing.
 *
 * Building the search index ([indexing]) adds the two rows and nothing else: no hotspot probe, no `su`, no Shizuku, no
 * VpnService. The first root prompt, the Shizuku permission request and the VPN consent come only from a tap here.
 * Every method applies at once, so nothing here marks a reconnect.
 */
internal class HotspotExtraAddressCard(
    private val activity: Activity,
    private val indexing: Boolean,
    private val colors: Colors,
    /** A 14 sp note in the muted colour, not yet added to a parent. */
    private val note: (String) -> TextView,
    /** A "Title · Value" setting row, added to the parent. */
    private val settingRow: (parent: LinearLayout, text: String, click: () -> Unit) -> Unit,
    /** A plain action button, added to the parent and returned. */
    private val actionButton: (parent: LinearLayout, text: String, click: () -> Unit) -> Button,
    private val rerender: () -> Unit,
) {
    data class Colors(val muted: Int, val warning: Int, val ready: Int)

    private val app = activity.applicationContext
    private val probes = AtomicInteger()

    fun build(parent: LinearLayout) {
        val method = HotspotExtraAddressSettings.method(app)
        val address = HotspotExtraAddressSettings.address(app)
        val text = address.hostAddress.orEmpty()
        val current = note(activity.getString(R.string.settings_hotspot_address_checking)).also(parent::addView)
        val blocked = note(activity.getString(R.string.settings_hotspot_address_blocked)).apply {
            setTextColor(colors.warning)
            visibility = View.GONE
        }.also(parent::addView)
        settingRow(parent, "${activity.getString(R.string.settings_hotspot_address_method)} · ${label(method)}", ::chooseMethod)
        val description = if (method == HotspotAddressMethod.NORMAL) activity.getString(method.description)
            else activity.getString(method.description, text)
        parent.addView(note(description))
        if (method != HotspotAddressMethod.NORMAL) {
            settingRow(parent, "${activity.getString(R.string.settings_hotspot_extra_address_value)} · $text", ::editAddress)
        }
        if (method == HotspotAddressMethod.VPN && HotspotAddressVpn.probablyBlocked(address)) {
            parent.addView(note(activity.getString(R.string.settings_hotspot_vpn_unlikely_hint,
                HotspotExtraAddress.LINK_LOCAL_SUGGESTION)).apply { setTextColor(colors.warning) })
        }
        if (indexing) return
        val status = note("").also(parent::addView)
        val action = when (method) {
            HotspotAddressMethod.SHIZUKU -> actionButton(parent, activity.getString(R.string.settings_hotspot_shizuku_allow), ::allowShizuku)
            HotspotAddressMethod.VPN -> actionButton(parent, activity.getString(R.string.settings_hotspot_vpn_allow), ::allowVpn)
            else -> null
        }
        val refresh = { showStatus(method, status, action) }
        refresh()
        probe(current, blocked, method)
        val keeperListener: (HotspotExtraAddressKeeper.State) -> Unit = {
            activity.runOnUiThread {
                if (status.isAttachedToWindow) {
                    refresh()
                    probe(current, blocked, method) // the address list changes with it
                }
            }
        }
        val vpnListener: (HotspotAddressVpn.State) -> Unit = {
            activity.runOnUiThread { if (status.isAttachedToWindow) refresh() }
        }
        // Only while the rows are on screen; a new render replaces them.
        status.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                HotspotExtraAddressKeeper.addListener(keeperListener)
                HotspotAddressVpn.addListener(vpnListener)
                refresh() // it may have changed since build()
            }
            override fun onViewDetachedFromWindow(view: View) {
                HotspotExtraAddressKeeper.removeListener(keeperListener)
                HotspotAddressVpn.removeListener(vpnListener)
            }
        })
    }

    /** The chooser's label; the VPN gets a second line where Android drops hotspot traffic to its address. */
    private fun label(method: HotspotAddressMethod): String = activity.getString(method.label)

    private fun chooserItem(method: HotspotAddressMethod): CharSequence {
        val title = label(method)
        if (method != HotspotAddressMethod.VPN || !HotspotAddressVpn.probablyBlocked(HotspotExtraAddressSettings.address(app))) {
            return title
        }
        val hint = activity.getString(R.string.settings_hotspot_method_vpn_unlikely)
        return SpannableStringBuilder(title).append('\n').append(hint).apply {
            setSpan(RelativeSizeSpan(0.8f), length - hint.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun probe(current: TextView, blocked: TextView, method: HotspotAddressMethod) {
        val generation = probes.incrementAndGet()
        Thread({
            val hotspot = runCatching { HotspotAddresses.current(app) }.getOrNull()
            activity.runOnUiThread {
                if (generation == probes.get()) showHotspot(current, blocked, hotspot, method)
            }
        }, "tiplay-hotspot-probe").start()
    }

    private fun showHotspot(current: TextView, blocked: TextView, hotspot: HotspotAddresses.Current?,
        method: HotspotAddressMethod) {
        if (hotspot == null || hotspot.ipv4.isEmpty()) {
            current.text = activity.getString(R.string.settings_hotspot_not_found)
            current.setTextColor(colors.warning)
            blocked.visibility = View.GONE
            return
        }
        current.text = activity.getString(R.string.settings_hotspot_current_address,
            hotspot.ipv4.joinToString(", ") { it.hostAddress.orEmpty() })
        current.setTextColor(colors.muted)
        // With another method, its status line says what happens instead.
        blocked.visibility = if (method == HotspotAddressMethod.NORMAL &&
            hotspot.ipv4.none(HotspotExtraAddress::isCgnat)) View.VISIBLE else View.GONE
    }

    private fun showStatus(method: HotspotAddressMethod, line: TextView, action: Button?) {
        var showAction = false
        val (text, color) = when {
            rootCheckInProgress.get() -> activity.getString(R.string.settings_hotspot_extra_address_root_checking) to colors.muted
            method == HotspotAddressMethod.NORMAL -> null to colors.muted
            !HotspotExtraAddressSettings.manualHotspotLink(app) -> activity.getString(
                R.string.settings_hotspot_extra_address_manual_only,
                activity.getString(R.string.built_in_car_hotspot)) to colors.warning
            method == HotspotAddressMethod.VPN -> vpnStatus().also {
                showAction = HotspotAddressVpn.state == HotspotAddressVpn.State.NeedsConsent ||
                    HotspotAddressVpn.state == HotspotAddressVpn.State.Revoked
            }
            !HotspotExtraAddressKeeper.running -> activity.getString(R.string.settings_hotspot_extra_address_paused) to colors.muted
            else -> keeperStatus(HotspotExtraAddressKeeper.state).also {
                showAction = HotspotExtraAddressKeeper.state == HotspotExtraAddressKeeper.State.ShizukuPermissionNeeded
            }
        }
        line.text = text.orEmpty()
        line.setTextColor(color)
        line.visibility = if (text == null) View.GONE else View.VISIBLE
        action?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    private fun keeperStatus(state: HotspotExtraAddressKeeper.State): Pair<String, Int> = when (state) {
        HotspotExtraAddressKeeper.State.Off -> activity.getString(R.string.settings_hotspot_address_checking) to colors.muted
        HotspotExtraAddressKeeper.State.NoHotspot ->
            activity.getString(R.string.settings_hotspot_extra_address_waiting) to colors.muted
        is HotspotExtraAddressKeeper.State.Added ->
            activity.getString(R.string.settings_hotspot_extra_address_added, state.iface) to colors.ready
        HotspotExtraAddressKeeper.State.RootDenied ->
            activity.getString(R.string.settings_hotspot_extra_address_no_root) to colors.warning
        HotspotExtraAddressKeeper.State.Blocked ->
            activity.getString(R.string.settings_hotspot_shizuku_blocked) to colors.warning
        HotspotExtraAddressKeeper.State.ShizukuUnavailable ->
            activity.getString(R.string.settings_hotspot_shizuku_not_running) to colors.warning
        HotspotExtraAddressKeeper.State.ShizukuPermissionNeeded ->
            activity.getString(R.string.settings_hotspot_shizuku_permission) to colors.warning
        is HotspotExtraAddressKeeper.State.Failed ->
            activity.getString(R.string.settings_hotspot_extra_address_failed) to colors.warning
    }

    private fun vpnStatus(): Pair<String, Int> = when (HotspotAddressVpn.state) {
        HotspotAddressVpn.State.Up -> activity.getString(R.string.settings_hotspot_vpn_up,
            HotspotExtraAddressSettings.address(app).hostAddress.orEmpty()) to colors.ready
        HotspotAddressVpn.State.Starting -> activity.getString(R.string.settings_hotspot_vpn_starting) to colors.muted
        HotspotAddressVpn.State.NeedsConsent -> activity.getString(R.string.settings_hotspot_vpn_consent) to colors.warning
        HotspotAddressVpn.State.OtherVpn -> activity.getString(R.string.settings_hotspot_vpn_other) to colors.warning
        HotspotAddressVpn.State.WiredCarPlayVpn -> activity.getString(R.string.settings_hotspot_vpn_wired) to colors.warning
        HotspotAddressVpn.State.Revoked -> activity.getString(R.string.settings_hotspot_vpn_revoked) to colors.warning
        is HotspotAddressVpn.State.Failed -> activity.getString(R.string.settings_hotspot_vpn_failed) to colors.warning
        HotspotAddressVpn.State.Off -> activity.getString(R.string.settings_hotspot_extra_address_paused) to colors.muted
    }

    private fun chooseMethod() {
        if (rootCheckInProgress.get()) return
        val methods = HotspotAddressMethod.entries
        val saved = HotspotExtraAddressSettings.method(app)
        var pending = methods.indexOf(saved)
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_hotspot_address_method)
            .setSingleChoiceItems(methods.map(::chooserItem).toTypedArray(), pending) { _, index -> pending = index }
            .setPositiveButton(R.string.save) { _, _ -> choose(methods[pending], saved) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Choosing a method again retries its permission step (root, Shizuku, VPN consent) and what stopped it (a ROM that
     * blocked Shizuku, a revoked VPN).
     */
    private fun choose(next: HotspotAddressMethod, previous: HotspotAddressMethod) {
        if (next == HotspotAddressMethod.ROOT) return checkRootThenApply(previous)
        apply(next, previous, retry = true)
        when (next) {
            HotspotAddressMethod.VPN -> if (HotspotAddressVpn.state == HotspotAddressVpn.State.NeedsConsent) openVpnConsent()
            HotspotAddressMethod.SHIZUKU -> requestShizukuIfNeeded(quiet = true)
            else -> Unit
        }
    }

    private fun apply(next: HotspotAddressMethod, previous: HotspotAddressMethod, retry: Boolean = false) {
        HotspotExtraAddressSettings.saveMethod(app, next)
        HotspotExtraAddressSettings.sync(app, retry) // starts the new method, cleans up the old one
        // Root takes the address over (and removes it when the driver leaves Root), so only the others leave it behind.
        if (previous == HotspotAddressMethod.SHIZUKU && next != HotspotAddressMethod.SHIZUKU && next != HotspotAddressMethod.ROOT) {
            Toast.makeText(activity, R.string.settings_hotspot_shizuku_left, Toast.LENGTH_LONG).show()
        }
        rerender()
    }

    private fun checkRootThenApply(previous: HotspotAddressMethod) {
        if (!rootCheckInProgress.compareAndSet(false, true)) return
        rerender()
        // The first su call: the root manager may show its prompt, so it waits off the main thread.
        Thread({
            val granted = try {
                runCatching { HotspotExtraAddressKeeper.checkRoot() }.getOrDefault(false)
            } finally {
                rootCheckInProgress.set(false)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    if (granted) {
                        HotspotExtraAddressSettings.saveMethod(app, HotspotAddressMethod.ROOT)
                        HotspotExtraAddressSettings.sync(app, retry = true)
                    }
                    return@runOnUiThread
                }
                if (granted) {
                    apply(HotspotAddressMethod.ROOT, previous, retry = true)
                } else {
                    Toast.makeText(activity, R.string.settings_hotspot_extra_address_root_refused, Toast.LENGTH_LONG).show()
                    rerender()
                }
            }
        }, "tiplay-root-check").start()
    }

    /** The "Allow the VPN" button: checks again (another VPN, consent, a revoke), then asks Android if it must. */
    private fun allowVpn() {
        HotspotExtraAddressSettings.sync(app, retry = true)
        if (HotspotAddressVpn.state == HotspotAddressVpn.State.NeedsConsent) openVpnConsent()
    }

    /**
     * Opens Android's VPN consent. Only after a sync found no other VPN and no wired CarPlay VPN, which the consent
     * would switch off.
     */
    private fun openVpnConsent() {
        val consent = HotspotAddressVpn.consentIntent(activity)
        if (consent == null) {
            HotspotExtraAddressSettings.sync(app)
            return
        }
        // The dialog records the answer itself; onResume starts the tunnel when it was allowed.
        try {
            activity.startActivity(consent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.settings_hotspot_vpn_no_consent_screen, Toast.LENGTH_LONG).show()
        }
    }

    private fun allowShizuku() = requestShizukuIfNeeded(quiet = false)

    /** Shizuku's own dialog, from a tap. Its answer reaches the keeper, which adds the address. */
    private fun requestShizukuIfNeeded(quiet: Boolean) {
        Thread({
            val running = runCatching { ShizukuSystem.running() }.getOrDefault(false)
            val granted = running && runCatching { ShizukuSystem.permissionGranted() }.getOrDefault(false)
            val message = when {
                !running -> R.string.settings_hotspot_shizuku_not_running.takeUnless { quiet }
                granted -> null
                ShizukuSystem.permissionDeniedForGood() || !ShizukuSystem.requestPermission() -> R.string.settings_hotspot_shizuku_denied
                else -> null
            }
            HotspotExtraAddressKeeper.checkNow()
            if (message != null) activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }, "tiplay-shizuku-permission").start()
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
                    HotspotExtraAddressSettings.sync(app) // the running method moves to the new address at once
                }
                rerender()
            }
        }
        dialog.show()
    }

    companion object {
        /** Process-wide, so a render during the check keeps the chooser busy. */
        internal val rootCheckInProgress = AtomicBoolean(false)
    }
}
