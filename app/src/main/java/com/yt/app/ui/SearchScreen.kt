package com.yt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yt.app.VideoRepository
import com.yt.app.data.Video
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(repo: VideoRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var debugInfo by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        if (query.isBlank()) return
        keyboard?.hide()
        scope.launch {
            loading = true; error = null; searched = true; debugInfo = null
            try {
                val result = repo.searchWithDebug(query)
                videos = result.first
                debugInfo = result.second
            } catch (e: Exception) {
                error = e.message
                debugInfo = e.stackTraceToString()
            }
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                trailingIcon = {
                    IconButton(onClick = { doSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Error:", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(error ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (debugInfo != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(debugInfo!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                !searched -> Text(
                    "Search for videos, channels, or topics",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                videos.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("No results for \"$query\"",
                        style = MaterialTheme.typography.titleSmall)
                    if (debugInfo != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("yt-dlp output:", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(debugInfo!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(videos) { video ->
                        VideoCard(
                            video = video,
                            repo = repo,
                            onPlay = { com.yt.app.PlayerActivity.start(context, video.id, video.title) },
                            onRelated = {}
                        )
                    }
                }
            }
        }
    }
}
