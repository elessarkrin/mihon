package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class DriveOAuth(private val context: Context) {

    private val prefs = GoogleDrivePreferences(context)

    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val CODE_TIMEOUT_MS = 300_000 // 5 minutes
    }

    /**
     * Starts the browser-based PKCE OAuth2 flow using a loopback HTTP server.
     * GCP "Web application" type with authorized redirect URI `http://127.0.0.1` accepts
     * any loopback port, so no port needs to be registered in Cloud Console.
     */
    fun launchAuthFlow() {
        val clientId = prefs.getClientId()?.takeIf { it.isNotBlank() } ?: return
        val verifier = generateCodeVerifier()
        prefs.setCodeVerifier(verifier)

        val port = findFreePort()
        val redirectUri = "http://127.0.0.1:$port"

        val uri = Uri.Builder()
            .scheme("https")
            .authority("accounts.google.com")
            .path("/o/oauth2/v2/auth")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DRIVE_SCOPE)
            .appendQueryParameter("code_challenge", generateCodeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            val code = listenForCode(port)
            if (code != null) exchangeCode(code, redirectUri)
        }

        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun listenForCode(port: Int): String? = withContext(Dispatchers.IO) {
        try {
            ServerSocket(port).use { serverSocket ->
                serverSocket.soTimeout = CODE_TIMEOUT_MS
                serverSocket.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader().readLine()
                        ?: return@withContext null
                    val responseHtml =
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Connection: close\r\n\r\n" +
                            "<html><body style='font-family:sans-serif;text-align:center;margin-top:50px'>" +
                            "<h2>Authorization complete</h2>" +
                            "<p>You can close this tab and return to Mihon.</p>" +
                            "</body></html>"
                    socket.getOutputStream().write(responseHtml.toByteArray())
                    Regex("code=([^& ]+)").find(request)?.groupValues?.get(1)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Drive OAuth loopback listener failed" }
            null
        }
    }

    suspend fun exchangeCode(code: String, redirectUri: String) {
        val clientId = prefs.getClientId() ?: return
        val verifier = prefs.getCodeVerifier() ?: return
        prefs.clearCodeVerifier()

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
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
                    } else {
                        logcat(LogPriority.ERROR) { "Drive token exchange failed: ${response.code}" }
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
