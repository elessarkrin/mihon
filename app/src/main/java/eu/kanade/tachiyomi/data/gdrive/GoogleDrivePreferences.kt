package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class GoogleDrivePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)

    fun setClientId(id: String) = prefs.edit { putString(KEY_CLIENT_ID, id) }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun setAccessToken(token: String) = prefs.edit { putString(KEY_ACCESS_TOKEN, token) }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String?) = prefs.edit {
        if (token != null) putString(KEY_REFRESH_TOKEN, token) else remove(KEY_REFRESH_TOKEN)
    }

    fun getTokenExpiryMs(): Long = prefs.getLong(KEY_TOKEN_EXPIRY_MS, 0L)

    fun setTokenExpiryMs(ms: Long) = prefs.edit { putLong(KEY_TOKEN_EXPIRY_MS, ms) }

    fun getCodeVerifier(): String? = prefs.getString(KEY_CODE_VERIFIER, null)

    fun setCodeVerifier(verifier: String) = prefs.edit { putString(KEY_CODE_VERIFIER, verifier) }

    fun clearCodeVerifier() = prefs.edit { remove(KEY_CODE_VERIFIER) }

    fun isAuthorized(): Boolean = getRefreshToken() != null

    /** Root Drive folder name (default "MihonBackup"). User-configurable. */
    fun getRootFolder(): String = prefs.getString(KEY_ROOT_FOLDER, DEFAULT_ROOT_FOLDER) ?: DEFAULT_ROOT_FOLDER

    fun setRootFolder(path: String) =
        prefs.edit { putString(KEY_ROOT_FOLDER, path.trim().ifEmpty { DEFAULT_ROOT_FOLDER }) }

    fun isDriveEnabledForManga(mangaId: Long): Boolean =
        prefs.getBoolean(mangaKey(mangaId), false)

    fun setDriveEnabledForManga(mangaId: Long, enabled: Boolean) =
        prefs.edit { putBoolean(mangaKey(mangaId), enabled) }

    fun clearAllTokens() = prefs.edit {
        remove(KEY_ACCESS_TOKEN)
        remove(KEY_REFRESH_TOKEN)
        remove(KEY_TOKEN_EXPIRY_MS)
        remove(KEY_CODE_VERIFIER)
    }

    private fun mangaKey(mangaId: Long) = "drive_manga_$mangaId"

    companion object {
        private const val PREFS_NAME = "gdrive_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY_MS = "token_expiry_ms"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_ROOT_FOLDER = "root_folder"
        private const val DEFAULT_ROOT_FOLDER = "MihonBackup"
    }
}
