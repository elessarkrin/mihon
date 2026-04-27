package eu.kanade.tachiyomi.ui.setting.drive

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.gdrive.DriveOAuth
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import kotlinx.coroutines.launch

class DriveOAuthCallbackActivity : BaseOAuthLoginActivity() {

    override fun handleResult(uri: Uri) {
        val code = uri.getQueryParameter("code")
        lifecycleScope.launch {
            if (code != null) {
                DriveOAuth(applicationContext).exchangeCode(code)
            }
            returnToSettings()
        }
    }
}
