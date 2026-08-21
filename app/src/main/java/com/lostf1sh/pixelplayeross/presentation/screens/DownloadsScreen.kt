package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.database.DownloadState
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackEntity
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DownloadsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onDownloadLiked: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.downloads_liked_action)) },
                supportingContent = { Text(stringResource(R.string.downloads_liked_summary)) },
                leadingContent = {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onDownloadLiked)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.downloads_wifi_only)) },
                supportingContent = { Text(stringResource(R.string.downloads_wifi_only_summary)) },
                trailingContent = {
                    Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                }
            )

            if (downloads.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.downloads_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(downloads, key = { it.videoId }) { entry ->
                    DownloadRow(
                        entry = entry,
                        progress = progress[entry.videoId],
                        onDelete = { viewModel.delete(entry.videoId) },
                        onCancel = { viewModel.cancel(entry.videoId) },
                        onRetry = { viewModel.retry(entry.videoId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadedTrackEntity,
    progress: Float?,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    ListItem(
        leadingContent = {
            SmartImage(
                model = entry.albumArtUri,
                contentDescription = entry.title,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        },
        headlineContent = {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (entry.state) {
                    DownloadState.RUNNING -> {
                        // A negative fraction means the server never sent a content length.
                        if (progress != null && progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                    DownloadState.QUEUED -> Text(
                        stringResource(R.string.downloads_state_queued),
                        style = MaterialTheme.typography.labelMedium
                    )
                    DownloadState.FAILED -> Text(
                        entry.errorMessage ?: stringResource(R.string.downloads_state_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.state == DownloadState.FAILED) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.downloads_retry))
                    }
                }
                IconButton(
                    onClick = if (entry.state == DownloadState.COMPLETED) onDelete else onCancel
                ) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.downloads_remove))
                }
            }
        }
    )
}
