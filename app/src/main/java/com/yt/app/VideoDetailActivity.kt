package com.yt.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.yt.app.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class VideoDetailActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var repo: VideoRepository
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory
    private var videoId: String = ""

    // Shared player instance across portrait/landscape via companion
    companion object {
        const val EXTRA_VIDEO_ID    = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_CHANNEL     = "channel"
        const val EXTRA_VIEWS       = "views"
        const val EXTRA_FULLSCREEN  = "fullscreen"

        fun start(context: Context, video: Video) {
            context.startActivity(Intent(context, VideoDetailActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID,    video.id)
                putExtra(EXTRA_VIDEO_TITLE, video.title)
                putExtra(EXTRA_CHANNEL,     video.channel)
                putExtra(EXTRA_VIEWS,       video.views)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = VideoRepository(this)

        videoId            = intent.getStringExtra(EXTRA_VIDEO_ID) ?: run { finish(); return }
        val videoTitle     = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        val channel        = intent.getStringExtra(EXTRA_CHANNEL) ?: ""
        val views          = intent.getStringExtra(EXTRA_VIEWS) ?: ""
        val startFullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)

        repo.prefs.addHistory(videoId)

        val httpDs = DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)")
            .setDefaultRequestProperties(mapOf(
                "Origin"  to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"))
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDs))
            .build()

        // Store httpDs for reuse in loadStream
        this.httpDataSourceFactory = httpDs

        if (startFullscreen) enterFullscreen()

        setContent {
            YtAppTheme {
                VideoDetailScreen(
                    videoId        = videoId,
                    initialTitle   = videoTitle,
                    initialChannel = channel,
                    initialViews   = views,
                    player         = player,
                    repo           = repo,
                    isFullscreen   = startFullscreen,
                    onVideoClick   = { v -> start(this, v) },
                    onChannelClick = { cid -> ChannelActivity.start(this, cid) },
                    onFullscreen   = { enterFullscreen() },
                    onExitFullscreen = { exitFullscreen() }
                )
            }
        }

        lifecycleScope.launch { loadStream(videoId) }
    }

    private suspend fun loadStream(videoId: String, videoUrl: String? = null, audioUrl: String? = null) {
        val pos = player.currentPosition.takeIf { it > 0 } ?: 0L

        // If URLs provided directly (from quality picker), use them
        // Otherwise fetch from API
        val vUrl: String?
        val aUrl: String?
        if (videoUrl != null) {
            vUrl = videoUrl; aUrl = audioUrl
        } else {
            val info = repo.getStreamInfo(videoId)
            vUrl = info?.videoUrl; aUrl = info?.audioUrl
        }

        withContext(Dispatchers.Main) {
            if (vUrl == null) {
                android.app.AlertDialog.Builder(this@VideoDetailActivity)
                    .setTitle("Stream Error")
                    .setMessage(YtWebClient.lastStreamError.ifBlank { "Could not load video." })
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
                return@withContext
            }
            player.stop()
            try {
                val dsFactory = httpDataSourceFactory
                val videoSource = ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(MediaItem.fromUri(vUrl))
                if (aUrl != null) {
                    val audioSource = ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(aUrl))
                    player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    player.setMediaSource(videoSource)
                }
                player.prepare()
                if (pos > 0) player.seekTo(pos)
                player.playWhenReady = true
            } catch (e: Exception) {
                // Fallback to simple MediaItem
                player.setMediaItem(MediaItem.fromUri(vUrl))
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    fun reloadWithQualityOption(videoUrl: String, audioUrl: String?) {
        lifecycleScope.launch { loadStream(videoId, videoUrl, audioUrl) }
    }

    private fun enterFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun exitFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onPause()   { super.onPause();   player.pause() }
    override fun onResume()  { super.onResume();  if (player.playbackState != Player.STATE_IDLE) player.play() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoDetailScreen(
    videoId: String,
    initialTitle: String,
    initialChannel: String,
    initialViews: String,
    player: ExoPlayer,
    repo: VideoRepository,
    isFullscreen: Boolean,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    val context  = LocalContext.current
    val activity = context as? VideoDetailActivity
    val scope    = rememberCoroutineScope()

    var detail          by remember { mutableStateOf<YtWebClient.VideoDetail?>(null) }
    var comments        by remember { mutableStateOf<List<YtWebClient.Comment>?>(null) }
    var related         by remember { mutableStateOf<List<Video>?>(null) }
    var qualities       by remember { mutableStateOf<List<YtWebClient.QualityOption>>(emptyList()) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var isLiked         by remember { mutableStateOf(repo.prefs.isLiked(videoId)) }
    var isSub           by remember { mutableStateOf(false) }
    var descExpanded    by remember { mutableStateOf(false) }
    var fullscreen      by remember { mutableStateOf(isFullscreen) }

    LaunchedEffect(videoId) {
        launch { detail    = repo.getVideoDetail(videoId) }
        launch { comments  = repo.getComments(videoId) }
        launch { related   = repo.getRelated(videoId) }
        launch { qualities = repo.getQualities(videoId) }
    }
    LaunchedEffect(detail) {
        detail?.channelId?.let { isSub = repo.prefs.isSub(it) }
    }

    val title    = detail?.title ?: initialTitle
    val ch       = detail?.channelName ?: initialChannel
    val viewsRaw = detail?.viewCount ?: initialViews
    val viewsFmt = viewsRaw.toLongOrNull()?.let {
        NumberFormat.getNumberInstance(Locale.US).format(it) + " views"
    } ?: viewsRaw

    // Fullscreen: show only the player
    if (fullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // Exit fullscreen button
            IconButton(
                onClick = { fullscreen = false; onExitFullscreen() },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.Default.FullscreenExit, "Exit Fullscreen",
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Player ──────────────────────────────────────────────────────
            item {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Fullscreen button overlay
                    IconButton(
                        onClick = { fullscreen = true; onFullscreen() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, "Fullscreen",
                            tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // ── Title & views ────────────────────────────────────────────────
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { descExpanded = !descExpanded })
                    Spacer(Modifier.height(4.dp))
                    Text(viewsFmt, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                }
            }

            // ── Action row ───────────────────────────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionChip(
                        icon  = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (isLiked) "Liked" else "Like",
                        tint  = if (isLiked) Color.Red else Color.White
                    ) { isLiked = repo.prefs.toggleLike(videoId) }

                    ActionChip(Icons.Default.Download, "Save") {
                        scope.launch {
                            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS)
                            val ok = repo.download(videoId, dir)
                            Toast.makeText(context, if (ok) "Saved to Downloads" else "Download failed",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    ActionChip(Icons.Default.Share, "Share") {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
                            }, "Share video"))
                    }
                    ActionChip(Icons.Default.Tune, "Quality") { showQualitySheet = true }
                    ActionChip(Icons.Default.Fullscreen, "Full") { fullscreen = true; onFullscreen() }
                }
                Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── Channel row ──────────────────────────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { detail?.channelId?.let { onChannelClick(it) } },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(Color(0xFF333333)),
                        contentAlignment = Alignment.Center) {
                        Text(ch.firstOrNull()?.toString() ?: "C",
                            color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ch, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Tap to view channel", style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFAAAAAA))
                    }
                    Button(
                        onClick = {
                            detail?.channelId?.let { cid ->
                                isSub = repo.prefs.toggleSub(cid)
                                Toast.makeText(context, if (isSub) "Subscribed" else "Unsubscribed",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSub) Color(0xFF333333) else Color.White,
                            contentColor   = if (isSub) Color.White else Color.Black),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(if (isSub) "Subscribed" else "Subscribe",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── Description ──────────────────────────────────────────────────
            if (detail?.description?.isNotBlank() == true) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)
                        .clickable { descExpanded = !descExpanded }) {
                        Text(detail!!.description,
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC),
                            maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(if (descExpanded) "Show less" else "...more",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            // ── Comments ─────────────────────────────────────────────────────
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Comment, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Comments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (comments == null) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (comments != null && comments!!.isEmpty()) {
                item {
                    Text("No comments available",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            items(comments?.take(10) ?: emptyList()) { comment -> CommentRow(comment) }

            // ── Up next ───────────────────────────────────────────────────────
            item {
                Divider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Up Next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (related == null) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (related != null && related!!.isEmpty()) {
                item {
                    Text("No recommendations available",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            items(related ?: emptyList()) { video ->
                RelatedVideoRow(video = video, onClick = { onVideoClick(video) })
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ── Quality bottom sheet ──────────────────────────────────────────────────
    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            containerColor = Color(0xFF1A1A1A)
        ) {
            Text("Select Quality", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            if (qualities.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                qualities.forEach { q ->
                    ListItem(
                        headlineContent = { Text(q.label) },
                        supportingContent = if (!q.hasAudio) { {
                            Text("Video only — no audio", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF8800))
                        } } else null,
                        leadingContent = {
                            Icon(Icons.Default.Hd, null,
                                tint = if (q.hasAudio) Color.White else Color(0xFF888888))
                        },
                        modifier = Modifier.clickable {
                            showQualitySheet = false
                            activity?.reloadWithQualityOption(q.url, q.audioUrl)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCCCCCC))
    }
}

@Composable
private fun CommentRow(comment: YtWebClient.Comment) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF444444)),
            contentAlignment = Alignment.Center) {
            if (comment.avatarUrl.isNotBlank()) {
                AsyncImage(model = comment.avatarUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(comment.author.firstOrNull()?.toString() ?: "?",
                    color = Color.White, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = Color(0xFFCCCCCC))
                if (comment.likes.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ThumbUp, null, tint = Color(0xFF888888),
                        modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(comment.likes, style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEEEEEE))
        }
    }
}

@Composable
private fun RelatedVideoRow(video: Video, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box {
            AsyncImage(
                model = video.thumbnailUrl, contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(8.dp))
            )
            if (video.duration.isNotBlank()) {
                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text(video.duration, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(video.channel, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
            if (video.views.isNotBlank())
                Text(video.views, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        }
    }
}
