package org.yaop.ui.main

import android.content.Context
import android.content.SharedPreferences

/** Which quick-capture UI the dock's (+) opens: card grid (default) or the radial fan/wheel. */
object CaptureStyle {
    private const val PREFS = "yaop_ui_prefs"
    private const val KEY = "capture_style"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isCards(prefs: SharedPreferences): Boolean = prefs.getString(KEY, "cards") != "radial"

    fun set(prefs: SharedPreferences, cards: Boolean) {
        prefs.edit().putString(KEY, if (cards) "cards" else "radial").apply()
    }
}
