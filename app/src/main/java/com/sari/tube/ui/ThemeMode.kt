package com.sari.tube.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sari.tube.engine.AppPrefs

/** Theme choice shared by the whole app. Changing it recomposes every screen at once. */
object ThemeMode {
    /** 0 = follow the phone, 1 = dark, 2 = light. */
    var value by mutableIntStateOf(0)
        private set

    fun load(context: Context) {
        value = AppPrefs.themeMode(context)
    }

    fun next(context: Context) {
        value = (value + 1) % 3
        AppPrefs.setThemeMode(context, value)
    }

    fun label(): String = when (value) {
        1 -> "ليلي"
        2 -> "نهاري"
        else -> "تلقائي حسب الهاتف"
    }
}
