package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Collections

class DriveOAuth(private val context: Context) {

    private val prefs = GoogleDrivePreferences(context)

    fun buildCredential(): GoogleAccountCredential? {
        val account = prefs.getAccountName() ?: return null
        return GoogleAccountCredential
            .usingOAuth2(context, Collections.singletonList(DriveScopes.DRIVE_FILE))
            .setSelectedAccountName(account)
    }

    /** Returns true if Drive scope has been granted, false if user needs to re-authorize. */
    suspend fun hasValidToken(): Boolean {
        val account = prefs.getAccountName() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.getToken(
                    context,
                    android.accounts.Account(account, "com.google"),
                    "oauth2:${DriveScopes.DRIVE_FILE}",
                )
                true
            } catch (_: UserRecoverableAuthException) {
                false
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Drive token check failed" }
                false
            }
        }
    }
}
