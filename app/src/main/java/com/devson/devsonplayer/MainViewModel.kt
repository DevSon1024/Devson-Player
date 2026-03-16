package com.devson.devsonplayer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM, DYNAMIC_LIGHT, DYNAMIC_DARK
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString("themeMode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("themeMode", mode.name).apply()
    }

    fun toggleTheme() {
        val nextMode = when (_themeMode.value) {
            ThemeMode.DYNAMIC_LIGHT -> ThemeMode.DYNAMIC_DARK
            ThemeMode.DYNAMIC_DARK -> ThemeMode.DYNAMIC_LIGHT
            else -> ThemeMode.DYNAMIC_DARK // From System, default to Dark for the quick toggle
        }
        setThemeMode(nextMode)
    }
}
