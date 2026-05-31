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
import com.yt.app.PlayerActivity
import com.yt.app.VideoRepository
import com.yt.app.data.Video
import kotlinx.coroutines.launch

enum class LibraryTab { Likes, History, Downloads }

@Composable
fun LibraryScreen(repo: VideoRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(LibraryTab.History) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun load(tab: LibraryTab) {
        scope.launch {
            loading = true
            videos = when (tab) {
                LibraryTab.Likes -> repo.getLikedVideos()
                LibraryTab.History -> repo.prefs.getHistory().map { id ->
                    Video(id = id, title = id) // Title shown as ID until tapped
                }
                LibraryTab.Downloads -> repo.prefs.getDownloads().map { id ->
                    Video(id = id, title = id)
                }
            }
            loading = false
        }
    }

    LaunchedEffect(selectedTab) { load(selectedTab) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            LibraryTab.values().forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.name) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                videos.isEmpty() -> Text(
                    when (selectedTab) {
                        LibraryTab.Likes -> "No liked videos yet. Like a video to save it here."
                        LibraryTab.History -> "No watch history yet."
                        LibraryTab.Downloads -> "No downloads yet."
                    },
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(videos) { video ->
                        VideoCard(
                            video = video,
                            repo = repo,
                            onPlay = { PlayerActivity.start(context, video.id, video.title) },
                            onRelated = {}
                        )
                    }
                }
            }
        }
    }
}
