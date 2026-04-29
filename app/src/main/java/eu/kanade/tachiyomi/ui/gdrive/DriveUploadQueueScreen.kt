package eu.kanade.tachiyomi.ui.gdrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.gdrive.DriveUploadJob
import eu.kanade.tachiyomi.data.gdrive.DriveUploadQueue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

data object DriveUploadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { DriveUploadQueueScreenModel() }
        val jobs by screenModel.jobs.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_drive_upload_queue),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_clear_finished_uploads),
                                    icon = Icons.Outlined.ClearAll,
                                    onClick = screenModel::clearFinished,
                                ),
                            ),
                        )
                    },
                )
            },
        ) { paddingValues ->
            if (jobs.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_uploads,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(contentPadding = paddingValues) {
                    items(jobs, key = { it.id }) { job ->
                        DriveUploadJobItem(job)
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveUploadJobItem(job: DriveUploadJob) {
    val state by job.stateFlow.collectAsState()
    val progress by job.progressFlow.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(job.mangaTitle) },
            supportingContent = { Text(job.driveFileName) },
            trailingContent = {
                when (state) {
                    DriveUploadJob.State.QUEUED -> Icon(Icons.Outlined.Schedule, contentDescription = null)
                    DriveUploadJob.State.UPLOADING -> Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    DriveUploadJob.State.DONE -> Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    DriveUploadJob.State.ERROR -> Icon(Icons.Outlined.Error, contentDescription = null)
                }
            },
        )
        if (state == DriveUploadJob.State.UPLOADING) {
            if (progress > 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private class DriveUploadQueueScreenModel : ScreenModel {
    val jobs: StateFlow<List<DriveUploadJob>> = DriveUploadQueue.jobs
    fun clearFinished() = DriveUploadQueue.clearFinished()
}
