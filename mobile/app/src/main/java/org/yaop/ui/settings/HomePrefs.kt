package org.yaop.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which optional Home sections show (Settings -> Home). Device-local. */
object HomePrefs {
    private const val PREFS = "yaop_ui_prefs"
    private var prefs: SharedPreferences? = null

    var showPinned by mutableStateOf(true); private set
    var showImages by mutableStateOf(true); private set
    var showRecent by mutableStateOf(true); private set
    var showHabits by mutableStateOf(true); private set
    var showContinue by mutableStateOf(true); private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        showPinned = p.getBoolean("home_pinned_v2", true)
        showImages = p.getBoolean("home_images_v2", true)
        showRecent = p.getBoolean("home_recent_v2", true)
        showHabits = p.getBoolean("home_habits", true)
        showContinue = p.getBoolean("home_continue", true)
    }

    fun setPinned(v: Boolean) { showPinned = v; prefs?.edit()?.putBoolean("home_pinned_v2", v)?.apply() }
    fun setImages(v: Boolean) { showImages = v; prefs?.edit()?.putBoolean("home_images_v2", v)?.apply() }
    fun setRecent(v: Boolean) { showRecent = v; prefs?.edit()?.putBoolean("home_recent_v2", v)?.apply() }
    fun setHabits(v: Boolean) { showHabits = v; prefs?.edit()?.putBoolean("home_habits", v)?.apply() }
    fun setContinue(v: Boolean) { showContinue = v; prefs?.edit()?.putBoolean("home_continue", v)?.apply() }
}
