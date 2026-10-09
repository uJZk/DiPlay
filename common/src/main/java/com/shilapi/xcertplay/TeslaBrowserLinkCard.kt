package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The browser link rows of the Tesla browser card (phone + browser mode only, integration E4): what the link is doing,
 * the local network permission (Android 17), the page address, the pairing code, and the two links with Copy buttons:
 * the HTTPS page for the Tesla and other Chromium browsers, and the page the phone serves itself for other browsers.
 *
 * Building the search index ([indexing]) adds the row titles only: it starts no server, creates no pairing code and
 * reads no network interface. Otherwise the links probe the hotspot's address, on a thread.
 */
internal class TeslaBrowserLinkCard(
    private val activity: Activity,
    private val indexing: Boolean,
    private val colors: HotspotExtraAddressCard.Colors,
    /** A 14 sp note in the muted colour, not yet added to a parent. */
    private val note: (String) -> TextView,
    /** A "Title · Value" setting row, added to the parent. */
    private val settingRow: (parent: LinearLayout, text: String, click: () -> Unit) -> Unit,
    /** A plain button, added to the parent. */
    private val actionRow: (parent: LinearLayout, text: String, click: () -> Unit) -> Unit,
    private val requestLocalNetwork: () -> Unit,
    private val rerender: () -> Unit,
) {
    private val app = activity.applicationContext
    private val probes = AtomicInteger()

    fun build(parent: LinearLayout) {
        val status = note("").also(parent::addView)
        showState(status, TeslaBrowserLink.state)
        localNetworkRows(parent)
        settingRow(parent, "${activity.getString(R.string.settings_browser_page_address)} · " +
            TeslaBrowserLink.pageAddress(app), ::editPageAddress)
        // The index needs the title only; a first build of the card creates the code, which needs no network.
        val code = if (indexing) "" else TeslaBrowserLink.pairingCode(app)
        settingRow(parent, "${activity.getString(R.string.settings_browser_pairing_code)} · $code") { showPairingCode() }
        if (indexing) return

        parent.addView(note(activity.getString(R.string.settings_browser_link_car)).withTopPadding())
        // Final at once with the extra address; otherwise the probe adds the hotspot's own address as `t`.
        var copyPageLink = TeslaBrowserLink.pageLink(app, hotspotAddress = null)
        val pageLink = linkText(copyPageLink).also(parent::addView)
        actionRow(parent, activity.getString(R.string.settings_browser_link_copy)) { copy(copyPageLink) }

        parent.addView(note(activity.getString(R.string.settings_browser_link_other)).withTopPadding())
        val phoneLink = linkText(activity.getString(R.string.settings_hotspot_address_checking)).also(parent::addView)
        var copyPhoneLink: String? = null
        actionRow(parent, activity.getString(R.string.settings_browser_link_copy)) {
            copyPhoneLink?.let(::copy) ?: Toast.makeText(activity, R.string.settings_hotspot_not_found, Toast.LENGTH_SHORT).show()
        }
        val showLinks: (String, String?) -> Unit = { page, phone ->
            copyPageLink = page
            pageLink.text = page
            copyPhoneLink = phone
            phoneLink.text = phone ?: activity.getString(R.string.settings_hotspot_not_found)
            phoneLink.setTextColor(if (phone == null) colors.warning else colors.muted)
        }
        probe(showLinks)

        val stateListener: (TeslaBrowserLink.State) -> Unit = { state ->
            activity.runOnUiThread { if (status.isAttachedToWindow) showState(status, state) }
        }
        val keeperListener: (HotspotExtraAddressKeeper.State) -> Unit = {
            activity.runOnUiThread { if (status.isAttachedToWindow) probe(showLinks) } // the address list changes with it
        }
        // Only while the rows are on screen; a new render replaces them.
        status.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                TeslaBrowserLink.addListener(stateListener)
                HotspotExtraAddressKeeper.addListener(keeperListener)
                showState(status, TeslaBrowserLink.state) // it may have changed since build()
            }
            override fun onViewDetachedFromWindow(view: View) {
                TeslaBrowserLink.removeListener(stateListener)
                HotspotExtraAddressKeeper.removeListener(keeperListener)
            }
        })
    }

    private fun showState(line: TextView, state: TeslaBrowserLink.State) {
        val (text, color) = when (state) {
            is TeslaBrowserLink.State.Listening ->
                activity.getString(R.string.settings_browser_link_listening, state.port) to colors.ready
            TeslaBrowserLink.State.PortInUse ->
                activity.getString(R.string.settings_browser_link_port_in_use, TeslaBrowserLink.PORT) to colors.warning
            TeslaBrowserLink.State.Failed -> activity.getString(R.string.settings_browser_link_failed) to colors.warning
            TeslaBrowserLink.State.Stopped -> activity.getString(if (TeslaBrowserLink.active)
                R.string.settings_browser_link_starting else R.string.settings_browser_link_stopped) to colors.muted
        }
        line.text = text
        line.setTextColor(color)
    }

    private fun localNetworkRows(parent: LinearLayout) {
        when (LocalNetworkPermission.state(app)) {
            LocalNetworkPermission.State.NOT_NEEDED -> Unit
            LocalNetworkPermission.State.GRANTED ->
                parent.addView(note(activity.getString(R.string.settings_browser_local_network_granted)))
            LocalNetworkPermission.State.DENIED -> {
                parent.addView(note(activity.getString(R.string.settings_browser_local_network_denied)).apply {
                    setTextColor(colors.warning)
                })
                actionRow(parent, activity.getString(R.string.settings_browser_local_network_allow), ::allowLocalNetwork)
            }
        }
    }

    // After a denial for good Android answers a request at once, without a dialog: the button would do nothing.
    private fun allowLocalNetwork() {
        val action = LocalNetworkPermission.action(LocalNetworkPermission.requestedBefore(app),
            activity.shouldShowRequestPermissionRationale(LocalNetworkPermission.PERMISSION))
        if (action == LocalNetworkPermission.Action.REQUEST) {
            LocalNetworkPermission.markRequested(app)
            requestLocalNetwork()
            return
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.open_this_setting_from_your_car_s_settings_app, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Both links, on a thread: the HTTPS page names the extra address when the driver set one, else the hotspot's own
     * address; the phone-served page is at the extra address once it is on the hotspot, else at the hotspot's own.
     */
    private fun probe(show: (page: String, phone: String?) -> Unit) {
        val generation = probes.incrementAndGet()
        val code = TeslaBrowserLink.pairingCode(app)
        val extra = HotspotExtraAddressSettings.address(app).hostAddress
            .takeIf { HotspotExtraAddressSettings.enabled(app) && HotspotExtraAddressKeeper.state is HotspotExtraAddressKeeper.State.Added }
        Thread({
            // An added extra address answers both links, so the interfaces need no look.
            val hotspot = if (extra != null) null
            else runCatching { HotspotAddresses.current(app) }.getOrNull()?.ipv4?.firstOrNull()?.hostAddress
            val page = TeslaBrowserLink.pageLink(app, hotspot)
            val phone = (extra ?: hotspot)?.let { TeslaBrowserPageLinks.phoneLink(it, code, TeslaBrowserLink.PORT) }
            activity.runOnUiThread { if (generation == probes.get()) show(page, phone) }
        }, "tiplay-browser-link-probe").start()
    }

    private fun linkText(text: String): TextView = note(text).apply {
        setTextIsSelectable(true)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun TextView.withTopPadding(): TextView = apply { setPadding(0, dp(12), 0, 0) }

    private fun copy(link: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(activity.getString(R.string.app_name), link)
        // The link carries the pairing code: keep it out of the clipboard preview.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.settings_browser_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editPageAddress() {
        val saved = TeslaBrowserLink.pageAddress(app)
        val input = EditText(activity).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(saved)
            hint = TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS
        }
        val body = FrameLayout(activity).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_browser_page_address)
            .setMessage(R.string.settings_browser_page_address_hint)
            .setView(body)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!TeslaBrowserLink.savePageAddress(app, input.text.toString())) {
                    input.error = activity.getString(R.string.settings_browser_page_address_invalid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                rerender()
            }
        }
        dialog.show()
    }

    private fun showPairingCode() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_browser_pairing_code)
            .setMessage(activity.getString(R.string.settings_browser_pairing_code_description, TeslaBrowserLink.pairingCode(app)))
            .setPositiveButton(R.string.settings_browser_pairing_code_new) { _, _ ->
                TeslaBrowserLink.newPairingCode(app)
                rerender()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
}
