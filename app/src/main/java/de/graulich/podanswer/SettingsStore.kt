package de.graulich.podanswer

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun readDefaultContext(): String = prefs.getString(DEFAULT_CONTEXT, "").orEmpty()

    fun saveDefaultContext(value: String) {
        prefs.edit().putString(DEFAULT_CONTEXT, value).apply()
    }

    fun readInterestingFactsEnabled(): Boolean = prefs.getBoolean(INTERESTING_FACTS, false)

    fun saveInterestingFactsEnabled(value: Boolean) {
        prefs.edit().putBoolean(INTERESTING_FACTS, value).apply()
    }

    fun readThinkingLevel(): ThinkingLevel =
        ThinkingLevel.fromApiValue(prefs.getString(THINKING_LEVEL, null))

    fun saveThinkingLevel(value: ThinkingLevel) {
        prefs.edit().putString(THINKING_LEVEL, value.apiValue).apply()
    }

    private companion object {
        const val THINKING_LEVEL = "thinking_level"
        const val DEFAULT_CONTEXT = "default_context"
        const val INTERESTING_FACTS = "interesting_facts_enabled"
    }
}
