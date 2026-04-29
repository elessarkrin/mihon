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
    suspend fun upload(file: UniFile, mangaTitle: String, chapterNumber: Double, author: String? = null) {
        try {
            if (!file.exists()) {
                logcat(LogPriority.WARN) { "Drive upload skipped: file does not exist" }
                return
            }

            val driveFileName = buildDriveFileName(mangaTitle, chapterNumber)
            val service = buildService() ?: return

            val rootFolderId = getOrEnsureRootFolder(service)
            val mangaFolderId = getOrCreateFolder(service, sanitize(mangaTitle), rootFolderId)

            val existing = service.files().list()
                .setQ("name='$driveFileName' and '$mangaFolderId' in parents and trashed=false")
                .setFields("files(id)")
                .execute()
            if (existing.files.isNotEmpty()) {
                logcat { "Drive upload skipped (already exists): $driveFileName" }
                return
            }

            val driveMetadata = DriveFile().apply {
                name = driveFileName
                parents = listOf(mangaFolderId)
            }

            val comicInfo = buildComicInfoXml(mangaTitle, chapterNumber, author)
            val tempCbz = File(context.cacheDir, driveFileName)
            try {
                if (file.isDirectory) {
                    packDirectoryAsCbz(file, tempCbz, comicInfo)
                } else {
                    repackCbzWithMetadata(file, tempCbz, comicInfo)
                }
                service.files()
                    .create(driveMetadata, FileContent("application/x-cbz", tempCbz))
                    .setFields("id,name")
                    .execute()
            } finally {
                tempCbz.delete()
            }
            logcat { "Drive upload success: $driveFileName" }
        } catch (e: UserRecoverableAuthIOException) {
            logcat(LogPriority.WARN) {
                "Drive upload skipped: authorization required. " +
                    "Re-connect your account in Settings → Data & Storage → Google Drive."
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Drive upload failed for $mangaTitle ch.$chapterNumber" }
        }
    }

    /**
     * Returns the root folder ID to upload into.
     * Priority: user-specified folder ID → cached auto-created folder ID → create new folder.
     */
    private fun getOrEnsureRootFolder(service: Drive): String {
        drivePrefs.getManualFolderId()?.let { return it }
        drivePrefs.getRootFolderId()?.let { return it }
        val metadata = DriveFile().apply {
            name = sanitize(drivePrefs.getRootFolder())
            mimeType = "application/vnd.google-apps.folder"
        }
        val id = service.files().create(metadata).setFields("id").execute().id
        drivePrefs.setRootFolderId(id)
        return id
    }

    private fun packDirectoryAsCbz(sourceDir: UniFile, destFile: File, comicInfo: String) {
        ZipOutputStream(destFile.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("ComicInfo.xml"))
            zos.write(comicInfo.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            sourceDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { imageFile ->
                    zos.putNextEntry(ZipEntry(imageFile.name ?: return@forEach))
                    imageFile.openInputStream()?.use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun repackCbzWithMetadata(source: UniFile, destFile: File, comicInfo: String) {
        val inputStream = source.openInputStream() ?: return
        java.util.zip.ZipInputStream(inputStream.buffered()).use { zis ->
            ZipOutputStream(destFile.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                zos.write(comicInfo.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name != "ComicInfo.xml") {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zis.copyTo(zos)
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun buildComicInfoXml(mangaTitle: String, chapterNumber: Double, author: String?): String {
        val intPart = chapterNumber.toLong()
        val numStr = if (chapterNumber == intPart.toDouble()) {
            "%04d".format(intPart)
        } else {
            "%04d".format(intPart) + ".${chapterNumber.toString().substringAfter('.')}"
        }
        val writerTag = if (!author.isNullOrBlank()) "\n  <Writer>${author.escapeXml()}</Writer>" else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Series>${mangaTitle.escapeXml()}</Series>
  <Number>$numStr</Number>
  <Manga>YesAndRightToLeft</Manga>$writerTag
</ComicInfo>"""
    }

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

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
        val intPart = chapterNumber.toLong()
        val numStr = if (chapterNumber == intPart.toDouble()) {
            "%04d".format(intPart)
        } else {
            "%04d".format(intPart) + ".${chapterNumber.toString().substringAfter('.')}"
        }
        return "${sanitize(mangaTitle)}_$numStr.cbz"
    }

    private fun sanitize(name: String): String = name.replace(Regex("""[/\\:*?"<>|]"""), "_")
}
