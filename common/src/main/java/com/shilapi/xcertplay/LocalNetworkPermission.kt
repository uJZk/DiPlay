package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17's local network protection (developer.android.com/privacy-and-security/local-network-permission): an app
 * that targets API 37 needs the runtime permission `ACCESS_LOCAL_NETWORK` (in the Nearby devices group) to accept the
 * car browser's connections and the iPhone's on the hotspot. Before Android 17, or for a lower target, INTERNET grants
 * it implicitly, and the docs say not to request it.
 *
 * The platform defines the permission only when it is built with the feature (`access_local_network_permission_enabled`).
 * Without it there is no protection, and the permission can never be granted: asking for it would block the wireless
 * start for good, so it does not apply there.
 */
internal object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val SDK = 37

    private const val PREFS = "tiplay_browser_link"
    private const val KEY_REQUESTED = "local_network_requested"

    enum class State(val wire: String) { NOT_NEEDED("not-needed"), GRANTED("granted"), DENIED("denied") }

    /** What the settings card's button does while the permission is denied. */
    enum class Action { REQUEST, OPEN_APP_SETTINGS }

    /** Whether this platform defines the permission; it cannot change while the app runs. */
    @Volatile private var defined: Boolean? = null

    /** True on Android 17+ for an app that targets it, on a platform with the feature: it must be granted at run time. */
    fun applies(context: Context): Boolean =
        applies(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion) { isDefined(context) }

    /** [defined] is asked only when the levels match, so older platforms make no package manager call. */
    internal fun applies(sdk: Int, targetSdk: Int, defined: () -> Boolean): Boolean =
        sdk >= SDK && targetSdk >= SDK && defined()

    internal fun isDefined(context: Context): Boolean {
        defined?.let { return it }
        val value = try {
            context.packageManager.getPermissionInfo(PERMISSION, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        defined = value
        return value
    }

    fun state(context: Context): State = when {
        !applies(context) -> State.NOT_NEEDED
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED -> State.GRANTED
        else -> State.DENIED
    }

    /** The permission to request with the connection permissions: only in phone + browser mode, and only where it applies. */
    fun required(context: Context, phoneBrowser: Boolean): List<String> =
        if (phoneBrowser && applies(context)) listOf(PERMISSION) else emptyList()

    /**
     * Asks again while Android still shows its dialog. Once it was asked and Android shows no rationale any more, the
     * driver denied it for good and a request would return at once without a dialog: only the app's settings page can
     * allow it then.
     */
    fun action(requestedBefore: Boolean, showRationale: Boolean): Action =
        if (requestedBefore && !showRationale) Action.OPEN_APP_SETTINGS else Action.REQUEST

    fun requestedBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) = prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tests: forget the cached platform check. */
    internal fun resetForTest() {
        defined = null
    }
}
