package eu.kanade.tachiyomi.data.gdrive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

object DriveUploadQueue {

    private val _jobs = MutableStateFlow<List<DriveUploadJob>>(emptyList())
    val jobs: StateFlow<List<DriveUploadJob>> = _jobs.asStateFlow()

    val activeCount: Int
        get() = _jobs.value.count {
            it.stateFlow.value == DriveUploadJob.State.QUEUED ||
                it.stateFlow.value == DriveUploadJob.State.UPLOADING
        }

    fun enqueue(mangaTitle: String, driveFileName: String): DriveUploadJob {
        val job = DriveUploadJob(
            id = UUID.randomUUID().toString(),
            mangaTitle = mangaTitle,
            driveFileName = driveFileName,
        )
        _jobs.value = _jobs.value + job
        return job
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filter {
            it.stateFlow.value == DriveUploadJob.State.QUEUED ||
                it.stateFlow.value == DriveUploadJob.State.UPLOADING
        }
    }
}
