package com.yt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yt.app.VideoRepository
import com.yt.app.data.Video
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(repo: VideoRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
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
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch {
                            loading = true; error = null
                            try { videos = repo.getHomeFeed() } catch (e: Exception) { error = e.message }
                            loading = false
                        }
                    }) { Text("Retry") }
                }
            }
            videos.isEmpty() -> Text(
                "Watch some videos to build your feed!",
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(videos) { video ->
                    VideoCard(
                        video = video,
                        repo = repo,
                        onPlay = { com.yt.app.PlayerActivity.start(context, video.id, video.title) },
                        onRelated = { /* handled by parent nav */ }
                    )
                }
            }
        }
    }
}
