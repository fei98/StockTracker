package com.example.stocktracker

import android.content.Context

/** 外观主题偏好 */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** 应用设置持久化（主题等） */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    fun loadTheme(): ThemePreference =
        runCatching { ThemePreference.valueOf(prefs.getString(KEY_THEME, "SYSTEM") ?: "SYSTEM") }
            .getOrDefault(ThemePreference.SYSTEM)

    fun saveTheme(t: ThemePreference) {
        prefs.edit().putString(KEY_THEME, t.name).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
