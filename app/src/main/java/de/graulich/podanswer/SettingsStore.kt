package de.graulich.podanswer

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun readDefaultContext(): String = prefs.getString(DEFAULT_CONTEXT, "").orEmpty()

    fun saveDefaultContext(value: String) {
        prefs.edit().putString(DEFAULT_CONTEXT, value).apply()
    }

    private companion object {
        const val DEFAULT_CONTEXT = "default_context"
    }
}
