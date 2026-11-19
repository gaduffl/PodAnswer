package de.graulich.podanswer

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Minimal Microsoft Graph client for OneDrive uploads.
 * Signs in with the OAuth device code flow (no redirect URI or app signature needed) and writes
 * into the app's own OneDrive folder `Apps/<app name>` (scope Files.ReadWrite.AppFolder).
 */
class OneDriveClient(
    private val http: OkHttpClient,
    private val authorityUrl: String = "https://login.microsoftonline.com/common/oauth2/v2.0",
    private val graphUrl: String = "https://graph.microsoft.com/v1.0",
) {
    data class DeviceCode(
        val userCode: String,
        val deviceCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Int)

    sealed interface PollResult {
        data object Pending : PollResult
        data object SlowDown : PollResult
        data class Success(val tokens: Tokens) : PollResult
        data class Failed(val message: String) : PollResult
    }

    fun startDeviceLogin(clientId: String): DeviceCode {
        val json = postForm("$authorityUrl/devicecode", mapOf("client_id" to clientId, "scope" to SCOPES))
        return DeviceCode(
            userCode = json.getString("user_code"),
            deviceCode = json.getString("device_code"),
            verificationUri = json.optString("verification_uri", "https://microsoft.com/devicelogin"),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
            expiresInSeconds = json.optInt("expires_in", 900),
        )
    }

    fun pollDeviceLogin(clientId: String, deviceCode: String): PollResult {
        val form = mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "client_id" to clientId,
            "device_code" to deviceCode,
        )
        val (code, json) = postFormRaw("$authorityUrl/token", form)
        if (code in 200..299) return PollResult.Success(tokens(json))
        return when (json.optString("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "expired_token" -> PollResult.Failed("Der Anmeldecode ist abgelaufen. Bitte erneut verbinden.")
            "authorization_declined" -> PollResult.Failed("Die Anmeldung wurde abgelehnt.")
            else -> PollResult.Failed(errorText(json, code))
        }
    }

    fun refresh(clientId: String, refreshToken: String): Tokens {
        val json = postForm("$authorityUrl/token", mapOf(
            "grant_type" to "refresh_token", "client_id" to clientId,
            "refresh_token" to refreshToken, "scope" to SCOPES,
        ))
        return tokens(json)
    }

    /** Creates or replaces [fileName] in the app folder. */
    fun upload(accessToken: String, fileName: String, content: String) {
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("$graphUrl/me/drive/special/approot:/$encoded:/content")
            .header("Authorization", "Bearer $accessToken")
            .put(content.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                val message = body.optJSONObject("error")?.optString("message").orEmpty()
                throw IOException("OneDrive-Upload fehlgeschlagen (${response.code})${if (message.isNotBlank()) ": $message" else ""}")
            }
        }
    }

    private fun tokens(json: JSONObject) = Tokens(
        accessToken = json.getString("access_token"),
        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
        expiresInSeconds = json.optInt("expires_in", 3600),
    )

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val (code, json) = postFormRaw(url, form)
        if (code !in 200..299) throw IOException(errorText(json, code))
        return json
    }

    private fun postFormRaw(url: String, form: Map<String, String>): Pair<Int, JSONObject> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to (runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
        }
    }

    private fun errorText(json: JSONObject, code: Int): String {
        val description = json.optString("error_description").lineSequence().firstOrNull().orEmpty()
        return "OneDrive-Anmeldung fehlgeschlagen ($code)${if (description.isNotBlank()) ": $description" else ""}"
    }

    companion object {
        const val SCOPES = "Files.ReadWrite.AppFolder offline_access"
    }
}
