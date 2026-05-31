package com.yt.app.ui

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
    var isLiked by remember { mutableStateOf(repo.prefs.isLiked(video.id)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = { showMenu = true })
    ) {
        // Thumbnail with duration badge
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            if (video.duration.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(video.duration, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Info row
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Channel avatar
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF444444)),
                contentAlignment = Alignment.Center
            ) {
                if (video.channelAvatar.isNotBlank()) {
                    AsyncImage(model = video.channelAvatar, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(video.channel.firstOrNull()?.toString() ?: "?",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))

            // Title + meta
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )
                Spacer(Modifier.height(3.dp))
                val meta = listOfNotNull(
                    video.channel.takeIf { it.isNotBlank() },
                    video.views.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                }
            }

            // Overflow menu button
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More",
                        tint = Color(0xFFAAAAAA), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isLiked) "Unlike" else "Like") },
                        leadingIcon = { Icon(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null, tint = if (isLiked) Color.Red else Color.Unspecified) },
                        onClick = {
                            showMenu = false
                            isLiked = repo.prefs.toggleLike(video.id)
                            Toast.makeText(context, if (isLiked) "Liked" else "Removed from likes",
                                Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Subscribe") },
                        leadingIcon = { Icon(Icons.Default.Notifications, null) },
                        onClick = {
                            showMenu = false
                            scope.launch {
                                val cid = repo.getChannelUrl(video.id)
                                if (cid != null) {
                                    val subbed = repo.prefs.toggleSub(cid)
                                    Toast.makeText(context, if (subbed) "Subscribed" else "Unsubscribed",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = {
                            showMenu = false
                            scope.launch {
                                val dir = Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS)
                                val ok = repo.download(video.id, dir)
                                Toast.makeText(context, if (ok) "Downloaded" else "Download failed",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}
