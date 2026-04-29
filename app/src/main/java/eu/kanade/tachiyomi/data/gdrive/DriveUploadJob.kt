package eu.kanade.tachiyomi.data.gdrive

import kotlinx.coroutines.flow.MutableStateFlow

data class DriveUploadJob(
    val id: String,
    val mangaTitle: String,
    val driveFileName: String,
) {
    val stateFlow = MutableStateFlow(State.QUEUED)
    val progressFlow = MutableStateFlow(0)

    enum class State { QUEUED, UPLOADING, DONE, ERROR }
}
