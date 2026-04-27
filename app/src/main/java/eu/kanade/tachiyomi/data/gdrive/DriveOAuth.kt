package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class DriveOAuth(private val context: Context) {

    private val prefs = GoogleDrivePreferences(context)

    companion object {
        const val REDIRECT_URI = "mihon://drive-auth"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    fun launchAuthFlow() {
        val clientId = prefs.getClientId()?.takeIf { it.isNotBlank() } ?: return
        val verifier = generateCodeVerifier()
        prefs.setCodeVerifier(verifier)

        val uri = Uri.Builder()
            .scheme("https")
            .authority("accounts.google.com")
            .path("/o/oauth2/v2/auth")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DRIVE_SCOPE)
            .appendQueryParameter("code_challenge", generateCodeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    suspend fun exchangeCode(code: String) {
        val clientId = prefs.getClientId() ?: return
        val verifier = prefs.getCodeVerifier() ?: return
        prefs.clearCodeVerifier()

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()

        withContext(Dispatchers.IO) {
            try {
                OkHttpClient().newCall(
                    Request.Builder().url(TOKEN_ENDPOINT).post(body).build(),
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body!!.string())
                        prefs.setAccessToken(json.getString("access_token"))
                        json.optString("refresh_token").takeIf { it.isNotBlank() }
                            ?.let { prefs.setRefreshToken(it) }
                        prefs.setTokenExpiryMs(System.currentTimeMillis() + json.getLong("expires_in") * 1000L)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Drive OAuth token exchange failed" }
            }
        }
    }

    suspend fun getValidAccessToken(): String? {
        val token = prefs.getAccessToken() ?: return null
        if (System.currentTimeMillis() < prefs.getTokenExpiryMs() - 60_000L) return token
        return refreshToken()
    }

    private suspend fun refreshToken(): String? {
        val clientId = prefs.getClientId() ?: return null
        val refreshToken = prefs.getRefreshToken() ?: return null

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                OkHttpClient().newCall(
                    Request.Builder().url(TOKEN_ENDPOINT).post(body).build(),
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body!!.string())
                        val newToken = json.getString("access_token")
                        prefs.setAccessToken(newToken)
                        prefs.setTokenExpiryMs(
                            System.currentTimeMillis() + json.getLong("expires_in") * 1000L,
                        )
                        newToken
                    } else {
                        logcat(LogPriority.WARN) { "Drive token refresh failed: ${response.code}" }
                        null
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Drive token refresh exception" }
                null
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
