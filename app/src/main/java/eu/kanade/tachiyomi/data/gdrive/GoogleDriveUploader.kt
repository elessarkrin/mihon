package eu.kanade.tachiyomi.data.gdrive

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.hippo.unifile.UniFile
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.google.api.services.drive.model.File as DriveFile

class GoogleDriveUploader(private val context: Context) {

    private val drivePrefs = GoogleDrivePreferences(context)

    private fun buildService(): Drive? {
        val credential = DriveOAuth(context).buildCredential() ?: run {
            logcat(LogPriority.WARN) {
                "Drive upload skipped: no account configured. " +
                    "Connect one in Settings → Data & Storage → Google Drive."
            }
            return null
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Mihon")
            .build()
    }

    /**
     * Upload [file] to Drive as `{rootFolder}/{mangaTitle}/{mangaTitle}_{chapterNumber}.cbz`.
     * Handles both pre-archived CBZ files and raw image directories.
     * Skips silently if the file already exists (idempotent).
     * All exceptions are caught — Drive errors never affect download state.
     */
    suspend fun upload(file: UniFile, mangaTitle: String, chapterNumber: Double) {
        try {
            val filePath = file.filePath ?: run {
                logcat(LogPriority.WARN) { "Drive upload skipped: could not resolve file path" }
                return
            }
            val localFile = File(filePath)
            if (!localFile.exists()) {
                logcat(LogPriority.WARN) { "Drive upload skipped: local file does not exist at $filePath" }
                return
            }

            val driveFileName = buildDriveFileName(mangaTitle, chapterNumber)
            val service = buildService() ?: return

            val rootFolderId = getOrCreateFolder(service, sanitize(drivePrefs.getRootFolder()), "root")
            val mangaFolderId = getOrCreateFolder(service, sanitize(mangaTitle), rootFolderId)

            val existing = service.files().list()
                .setQ("name='$driveFileName' and '$mangaFolderId' in parents and trashed=false")
                .setFields("files(id)")
                .execute()
            if (existing.files.isNotEmpty()) {
                logcat { "Drive upload skipped (already exists): $driveFileName" }
                return
            }

            val uploadFile: File
            val tempCbz: File?
            if (localFile.isDirectory) {
                tempCbz = File(context.cacheDir, driveFileName)
                packDirectoryAsCbz(localFile, tempCbz)
                uploadFile = tempCbz
            } else {
                uploadFile = localFile
                tempCbz = null
            }

            try {
                val metadata = DriveFile().apply {
                    name = driveFileName
                    parents = listOf(mangaFolderId)
                }
                service.files()
                    .create(metadata, FileContent("application/x-cbz", uploadFile))
                    .setFields("id,name")
                    .execute()
                logcat { "Drive upload success: $driveFileName" }
            } finally {
                tempCbz?.delete()
            }
        } catch (e: UserRecoverableAuthIOException) {
            logcat(LogPriority.WARN) {
                "Drive upload skipped: authorization required. " +
                    "Re-connect your account in Settings → Data & Storage → Google Drive."
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Drive upload failed for $mangaTitle ch.$chapterNumber" }
        }
    }

    private fun packDirectoryAsCbz(sourceDir: File, destFile: File) {
        ZipOutputStream(destFile.outputStream().buffered()).use { zos ->
            sourceDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { imageFile ->
                    zos.putNextEntry(ZipEntry(imageFile.name))
                    imageFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun getOrCreateFolder(service: Drive, name: String, parentId: String): String {
        val q = "name='$name' and '$parentId' in parents and " +
            "mimeType='application/vnd.google-apps.folder' and trashed=false"
        val result = service.files().list().setQ(q).setFields("files(id)").execute()
        result.files.firstOrNull()?.id?.let { return it }

        val metadata = DriveFile().apply {
            this.name = name
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentId)
        }
        return service.files().create(metadata).setFields("id").execute().id
    }

    private fun buildDriveFileName(mangaTitle: String, chapterNumber: Double): String {
        val numStr = if (chapterNumber == chapterNumber.toLong().toDouble()) {
            chapterNumber.toLong().toString()
        } else {
            chapterNumber.toString()
        }
        return "${sanitize(mangaTitle)}_$numStr.cbz"
    }

    private fun sanitize(name: String): String = name.replace(Regex("""[/\\:*?"<>|]"""), "_")
}
