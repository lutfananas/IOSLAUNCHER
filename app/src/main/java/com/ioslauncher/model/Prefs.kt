package com.ioslauncher.model

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var iconPack: String
        get() = sp.getString("icon_pack", "") ?: ""
        set(v) = sp.edit { putString("icon_pack", v) }

    /** Re-scale icon pack artwork so its visible content fills the iOS squircle, then clip it. */
    var normalizePackIcons: Boolean
        get() = sp.getBoolean("normalize_pack", true)
        set(v) = sp.edit { putBoolean("normalize_pack", v) }

    var showLabels: Boolean
        get() = sp.getBoolean("show_labels", true)
        set(v) = sp.edit { putBoolean("show_labels", v) }

    var autoAddNewApps: Boolean
        get() = sp.getBoolean("auto_add", true)
        set(v) = sp.edit { putBoolean("auto_add", v) }

    var unlockAnimation: Boolean
        get() = sp.getBoolean("unlock_anim", true)
        set(v) = sp.edit { putBoolean("unlock_anim", v) }

    var hideStatusBar: Boolean
        get() = sp.getBoolean("hide_status_bar", true)
        set(v) = sp.edit { putBoolean("hide_status_bar", v) }

    var layoutJson: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit { putString("layout", v) }

    fun recordLaunch(key: String) {
        val count = sp.getInt("launch_count:$key", 0)
        sp.edit {
            putInt("launch_count:$key", count + 1)
            putLong("launch_time:$key", System.currentTimeMillis())
        }
    }

    fun launchCount(key: String) = sp.getInt("launch_count:$key", 0)
    fun lastLaunch(key: String) = sp.getLong("launch_time:$key", 0L)

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)
}
