package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class GoogleDrivePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAccountName(): String? = prefs.getString(KEY_ACCOUNT_NAME, null)

    fun setAccountName(name: String) = prefs.edit { putString(KEY_ACCOUNT_NAME, name) }

    fun clearAccount() = prefs.edit { remove(KEY_ACCOUNT_NAME) }

    fun isAuthorized(): Boolean = getAccountName() != null

    /** Root Drive folder name (default "MihonBackup"). User-configurable. */
    fun getRootFolder(): String = prefs.getString(KEY_ROOT_FOLDER, DEFAULT_ROOT_FOLDER) ?: DEFAULT_ROOT_FOLDER

    fun setRootFolder(path: String) =
        prefs.edit { putString(KEY_ROOT_FOLDER, path.trim().ifEmpty { DEFAULT_ROOT_FOLDER }) }

    fun isDriveEnabledForManga(mangaId: Long): Boolean =
        prefs.getBoolean(mangaKey(mangaId), false)

    fun setDriveEnabledForManga(mangaId: Long, enabled: Boolean) =
        prefs.edit { putBoolean(mangaKey(mangaId), enabled) }

    private fun mangaKey(mangaId: Long) = "drive_manga_$mangaId"

    companion object {
        private const val PREFS_NAME = "gdrive_prefs"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ROOT_FOLDER = "root_folder"
        private const val DEFAULT_ROOT_FOLDER = "MihonBackup"
    }
}
