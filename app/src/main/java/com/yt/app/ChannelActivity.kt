package com.yt.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.yt.app.data.Video
import com.yt.app.ui.VideoCard

class ChannelActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        fun start(context: Context, channelId: String) {
            context.startActivity(Intent(context, ChannelActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_ID, channelId)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: run { finish(); return }
        val repo = VideoRepository(this)

        setContent {
            YtAppTheme {
                ChannelScreen(
                    channelId = channelId,
                    repo = repo,
                    onBack = { finish() },
                    onVideoClick = { video -> VideoDetailActivity.start(this, video) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    repo: VideoRepository,
    onBack: () -> Unit,
    onVideoClick: (Video) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isSub by remember { mutableStateOf(repo.prefs.isSub(channelId)) }

    LaunchedEffect(channelId) {
        loading = true
        videos = YtWebClient.getChannelVideos(channelId).map {
            Video(it.id, it.title, it.channel, it.views, duration = it.duration)
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channelId.take(24)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    Button(
                        onClick = {
                            isSub = repo.prefs.toggleSub(channelId)
                            Toast.makeText(context, if (isSub) "Subscribed" else "Unsubscribed",
                                android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSub) Color(0xFF333333) else Color.White,
                            contentColor   = if (isSub) Color.White else Color.Black),
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (isSub) "Subscribed" else "Subscribe", fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No videos found for this channel.", color = Color(0xFFAAAAAA))
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp)) {
                items(videos) { video ->
                    VideoCard(video = video, repo = repo,
                        onPlay = { onVideoClick(video) }, onRelated = {})
                }
            }
        }
    }
}
