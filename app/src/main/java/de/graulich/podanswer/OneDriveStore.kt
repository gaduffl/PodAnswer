package de.graulich.podanswer

import android.content.Context

/** OneDrive sync settings. The refresh token is encrypted with Android Keystore. */
class OneDriveStore(context: Context) {
    private val prefs = context.getSharedPreferences("onedrive_sync", Context.MODE_PRIVATE)
    private val token = ApiKeyStore(context, prefsName = "onedrive_secure", alias = "podanswer_onedrive_token")

    fun clientId(): String = prefs.getString(CLIENT_ID, "").orEmpty()
    fun saveClientId(value: String) = prefs.edit().putString(CLIENT_ID, value.trim()).apply()

    fun refreshToken(): String? = if (token.hasKey()) token.read() else null
    fun saveRefreshToken(value: String) = token.save(value)
    fun isConnected(): Boolean = clientId().isNotBlank() && token.hasKey()

    fun syncedIds(): Set<String> = prefs.getStringSet(SYNCED, emptySet()).orEmpty()
    fun markSynced(id: String) = prefs.edit().putStringSet(SYNCED, syncedIds() + id).apply()
    fun markUnsynced(id: String) = prefs.edit().putStringSet(SYNCED, syncedIds() - id).apply()

    fun disconnect() {
        token.clear()
        prefs.edit().remove(SYNCED).apply()
    }

    private companion object {
        const val CLIENT_ID = "client_id"
        const val SYNCED = "synced_ids"
    }
}
