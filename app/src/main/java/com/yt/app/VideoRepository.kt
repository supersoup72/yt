package com.yt.app

import android.content.Context
import com.yt.app.data.Prefs
import com.yt.app.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(context: Context) {

    val prefs = Prefs(context)

    private fun YtWebClient.VideoResult.toVideo() = Video(id, title, channel, views, duration = duration, channelAvatar = channelAvatar)
    private fun List<YtWebClient.VideoResult>.toVideos() = map { it.toVideo() }

    suspend fun search(query: String) = withContext(Dispatchers.IO) { YtWebClient.search(query).toVideos() }

    suspend fun searchWithDebug(query: String): Pair<List<Video>, String> = withContext(Dispatchers.IO) {
        val r = YtWebClient.search(query)
        Pair(r.toVideos(), if (r.isEmpty()) "Innertube returned 0 results" else "")
    }

    suspend fun getHomeFeed(): List<Video> = withContext(Dispatchers.IO) {
        val history = prefs.getHistory()
        if (history.isEmpty()) YtWebClient.search("trending music").toVideos()
        else YtWebClient.getRelated(history.first()).toVideos()
    }

    suspend fun getChannelFeed(): List<Video> = withContext(Dispatchers.IO) {
        prefs.getSubs().flatMap { YtWebClient.getChannelVideos(it).toVideos() }
    }

    suspend fun getLikedVideos(): List<Video> = withContext(Dispatchers.IO) {
        prefs.getLikes().map { id ->
            val d = YtWebClient.getVideoDetail(id)
            if (d != null) Video(id, d.title, d.channelName, d.viewCount)
            else Video(id, id)
        }
    }

    suspend fun getRelated(videoId: String) = withContext(Dispatchers.IO) { YtWebClient.getRelated(videoId).toVideos() }

    suspend fun getVideoDetail(videoId: String) = withContext(Dispatchers.IO) { YtWebClient.getVideoDetail(videoId) }

    suspend fun getComments(videoId: String) = withContext(Dispatchers.IO) { YtWebClient.getComments(videoId) }

    suspend fun getQualities(videoId: String) = withContext(Dispatchers.IO) { YtWebClient.getQualities(videoId) }

    suspend fun getStreamUrl(videoId: String, preferredHeight: Int = 0) = withContext(Dispatchers.IO) {
        YtWebClient.getStreamUrl(videoId, preferredHeight)
    }

    suspend fun getStats(videoId: String) = withContext(Dispatchers.IO) { YtWebClient.getStats(videoId) }

    suspend fun getChannelUrl(videoId: String) = withContext(Dispatchers.IO) {
        YtWebClient.getVideoDetail(videoId)?.channelId
    }

    suspend fun download(videoId: String, outputDir: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val streamUrl = YtWebClient.getStreamUrl(videoId) ?: return@withContext false
        try {
            val conn = java.net.URL(streamUrl).openConnection() as java.net.HttpURLConnection
            conn.connect()
            val outFile = java.io.File(outputDir, "$videoId.mp4")
            conn.inputStream.use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
            conn.disconnect()
            prefs.addDownload(videoId)
            true
        } catch (e: Exception) { false }
    }
}
