package com.ioslauncher.model

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    companion object { const val GRID_MIN = 3; const val GRID_MAX = 8 }

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

    /** Home Screen icon size, 0.5–1.3 × the iOS 60pt icon. */
    var homeIconScale: Float
        get() = sp.getFloat("home_icon_scale", 1f)
        set(v) = sp.edit { putFloat("home_icon_scale", v) }

    /** Dock icon size, independent of the Home Screen size. */
    var dockIconScale: Float
        get() = sp.getFloat("dock_icon_scale", 1f)
        set(v) = sp.edit { putFloat("dock_icon_scale", v) }

    var dockEnabled: Boolean
        get() = sp.getBoolean("dock_enabled", true)
        set(v) = sp.edit { putBoolean("dock_enabled", v) }

    var gridCols: Int
        get() = sp.getInt("grid_cols", 4).coerceIn(GRID_MIN, GRID_MAX)
        set(v) = sp.edit { putInt("grid_cols", v.coerceIn(GRID_MIN, GRID_MAX)) }

    var gridRows: Int
        get() = sp.getInt("grid_rows", 6).coerceIn(GRID_MIN, GRID_MAX)
        set(v) = sp.edit { putInt("grid_rows", v.coerceIn(GRID_MIN, GRID_MAX)) }

    /** Liquid Glass: refraction + specular rim on glass surfaces. */
    var liquidGlass: Boolean
        get() = sp.getBoolean("liquid_glass", true)
        set(v) = sp.edit { putBoolean("liquid_glass", v) }

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
