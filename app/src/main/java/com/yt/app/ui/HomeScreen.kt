package com.yt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yt.app.VideoRepository
import com.yt.app.data.Video
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(repo: VideoRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // Reload feed every time the screen resumes (e.g. after watching a video)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        loading = true; error = null
        try {
            videos = repo.getHomeFeed()
        } catch (e: Exception) {
            error = "Failed to load feed: ${e.message}"
        }
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            error != null -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    scope.launch {
                        loading = true; error = null
                        try { videos = repo.getHomeFeed() } catch (e: Exception) { error = e.message }
                        loading = false
                    }
                }) { Text("Retry") }
            }
            videos.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No recommendations yet.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Watch a video and come back!", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        loading = true
                        try { videos = repo.getHomeFeed() } catch (e: Exception) { error = e.message }
                        loading = false
                    }
                }) { Text("Refresh") }
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(videos) { video ->
                    VideoCard(
                        video = video,
                        repo = repo,
                        onPlay = { com.yt.app.VideoDetailActivity.start(context, video) },
                        onRelated = {}
                    )
                }
            }
        }
    }
}
