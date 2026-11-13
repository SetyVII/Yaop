package org.yaop.core.network

import android.content.Context

/**
 * User network and privacy preferences.
 * Controls whether YAOP is permitted to use third-party helper resolvers/proxies
 * (such as vxreddit, ddinstagram, vxtwitter, arctic-shift) to bypass login walls.
 *
 * Designed to be directly exposed in the Settings screen so privacy-conscious users
 * can opt out and restrict all network traffic strictly to first-party origins.
 */
object NetworkPreferences {
    const val PREFS_NAME = "yaop_network_settings"
    const val KEY_ALLOW_THIRD_PARTY_LINK_PROXIES = "allow_third_party_link_proxies"

    /**
     * Returns true if the user permits helper resolvers/proxies to be queried.
     * Defaults to true for zero-friction previews, but respects explicit user preference.
     */
    fun areThirdPartyProxiesAllowed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_THIRD_PARTY_LINK_PROXIES, true)
    }

    /**
     * Updates the user's preference regarding third-party helper proxies.
     */
    fun setThirdPartyProxiesAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALLOW_THIRD_PARTY_LINK_PROXIES, allowed)
            .apply()
    }
}
