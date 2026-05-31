package com.yt.app.ui

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yt.app.VideoRepository
import com.yt.app.data.Video
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoCard(
    video: Video,
    repo: VideoRepository,
    onPlay: () -> Unit,
    onRelated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<Map<String, String>?>(null) }
    var isLiked by remember { mutableStateOf(repo.prefs.isLiked(video.id)) }
    var downloading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            // Thumbnail
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            )

            // Title + action row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Like button
                IconButton(onClick = {
                    isLiked = repo.prefs.toggleLike(video.id)
                    val msg = if (isLiked) "Liked!" else "Removed from likes"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // More options
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
            }

            if (video.channel.isNotBlank()) {
                Text(
                    text = video.channel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
            }
        }
    }

    // Dropdown menu (long-press)
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("Play") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { showMenu = false; onPlay() }
        )
        DropdownMenuItem(
            text = { Text("Stats") },
            leadingIcon = { Icon(Icons.Default.Info, null) },
            onClick = {
                showMenu = false
                showStats = true
                scope.launch {
                    stats = repo.getStats(video.id)
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Subscribe to channel") },
            leadingIcon = { Icon(Icons.Default.Notifications, null) },
            onClick = {
                showMenu = false
                scope.launch {
                    val url = repo.getChannelUrl(video.id)
                    if (url != null) {
                        val subbed = repo.prefs.toggleSub(url)
                        val msg = if (subbed) "Subscribed!" else "Unsubscribed"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Couldn't get channel URL", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        DropdownMenuItem(
            text = { Text(if (downloading) "Downloading…" else "Download") },
            leadingIcon = { Icon(Icons.Default.Download, null) },
            enabled = !downloading,
            onClick = {
                showMenu = false
                downloading = true
                scope.launch {
                    val dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val ok = repo.download(video.id, dir)
                    downloading = false
                    Toast.makeText(
                        context,
                        if (ok) "Downloaded to Downloads/" else "Download failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // Stats dialog
    if (showStats) {
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text("Video Stats") },
            text = {
                if (stats == null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        stats!!.forEach { (key, value) ->
                            if (value.isNotBlank()) {
                                Row {
                                    Text(
                                        "${key.replaceFirstChar { it.uppercase() }}: ",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(value)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStats = false }) { Text("Close") }
            }
        )
    }
}
