package com.yt.app

import android.content.Context
import com.yt.app.data.Prefs
import com.yt.app.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(context: Context) {

    val prefs = Prefs(context)

    private fun VideoResult.toVideo() = Video(
        id = id, title = title, channel = channel, views = views
    )

    private fun List<YtWebClient.VideoResult>.toVideos() = map { it.toVideo() }

    suspend fun search(query: String): List<Video> = withContext(Dispatchers.IO) {
        YtWebClient.search(query).toVideos()
    }

    suspend fun searchWithDebug(query: String): Pair<List<Video>, String> = withContext(Dispatchers.IO) {
        val results = YtWebClient.search(query)
        val debug = if (results.isEmpty()) "Innertube returned 0 results for: $query" else ""
        Pair(results.toVideos(), debug)
    }

    suspend fun getHomeFeed(): List<Video> = withContext(Dispatchers.IO) {
        val history = prefs.getHistory()
        if (history.isEmpty()) {
            YtWebClient.search("trending music").toVideos()
        } else {
            // Use last watched video to get related
            YtWebClient.getRelated(history.first()).toVideos()
        }
    }

    suspend fun getChannelFeed(): List<Video> = withContext(Dispatchers.IO) {
        val subs = prefs.getSubs() // stored as channelIds now
        subs.flatMap { channelId ->
            YtWebClient.getChannelVideos(channelId).toVideos()
        }
    }

    suspend fun getLikedVideos(): List<Video> = withContext(Dispatchers.IO) {
        prefs.getLikes().mapNotNull { id ->
            val stats = YtWebClient.getStats(id)
            if (stats["title"]?.isNotBlank() == true)
                Video(id = id, title = stats["title"] ?: id, channel = stats["channel"] ?: "")
            else Video(id = id, title = id)
        }
    }

    suspend fun getRelated(videoId: String): List<Video> = withContext(Dispatchers.IO) {
        YtWebClient.getRelated(videoId).toVideos()
    }

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        YtWebClient.getStreamUrl(videoId)
    }

    suspend fun getStats(videoId: String): Map<String, String> = withContext(Dispatchers.IO) {
        YtWebClient.getStats(videoId)
    }

    suspend fun getChannelUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        // Return videoId as channel reference (stats has channel name, not ID)
        val stats = YtWebClient.getStats(videoId)
        stats["channel"]
    }

    suspend fun download(videoId: String, outputDir: java.io.File): Boolean = withContext(Dispatchers.IO) {
        // Download via stream URL
        val streamUrl = YtWebClient.getStreamUrl(videoId) ?: return@withContext false
        try {
            val url = java.net.URL(streamUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connect()
            val outFile = java.io.File(outputDir, "$videoId.mp4")
            conn.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            prefs.addDownload(videoId)
            true
        } catch (e: Exception) { false }
    }
}

// Extension to use VideoResult type alias cleanly
private typealias VideoResult = YtWebClient.VideoResult
