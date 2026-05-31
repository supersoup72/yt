package com.yt.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YtWebClient {

    private const val TAG = "YtWebClient"
    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private val CONTEXT_WEB = JSONObject("""{"client":{"clientName":"WEB","clientVersion":"2.20240101","hl":"en","gl":"US"}}""")

    private var cachedVisitorData: String? = null
    var lastStreamError: String = ""

    private fun post(endpoint: String, body: JSONObject, userAgent: String = "Mozilla/5.0"): JSONObject? {
        return try {
            val url = URL("$BASE/$endpoint?prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("X-Goog-Api-Format-Version", "2")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.outputStream.write(body.toString().toByteArray())
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "POST $endpoint failed: ${e.message}")
            null
        }
    }

    private fun fetchVisitorData(): String? {
        return try {
            val conn = URL("https://www.youtube.com/").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Regex(""""visitorData":"([^"]+)"""").find(html)?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun search(query: String): List<VideoResult> {
        val body = JSONObject().apply {
            put("context", CONTEXT_WEB); put("query", query); put("params", "EgIQAQ==")
        }
        return post("search", body)?.let { parseSearchResults(it) } ?: emptyList()
    }

    // ── Stream URL ────────────────────────────────────────────────────────────
    // Returns StreamInfo with separate video+audio URLs for high quality DASH playback

    data class StreamInfo(
        val videoUrl: String,
        val audioUrl: String?,   // null = videoUrl already has audio (combined)
        val height: Int
    )

    fun getStreamInfo(videoId: String): StreamInfo? {
        if (cachedVisitorData == null) cachedVisitorData = fetchVisitorData()
        val vd = cachedVisitorData ?: ""

        data class Cfg(val name: String, val ver: String, val ua: String,
                       val extra: Map<String,Any> = emptyMap(), val params: String = "")
        val configs = listOf(
            Cfg("ANDROID_VR","1.56.21",
                "Mozilla/5.0 (Linux; Android 14; Oculus Quest) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                mapOf("androidSdkVersion" to 34, "deviceModel" to "Quest 3")),
            Cfg("ANDROID","19.02.39",
                "com.google.android.youtube/19.02.39 (Linux; U; Android 14) gzip",
                mapOf("androidSdkVersion" to 34), "CgIQBg=="),
            Cfg("IOS","19.09.3",
                "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
                mapOf("deviceModel" to "iPhone16,2"))
        )
        for (cfg in configs) {
            val clientObj = JSONObject().apply {
                put("clientName", cfg.name); put("clientVersion", cfg.ver)
                put("hl", "en"); put("gl", "US")
                if (vd.isNotBlank()) put("visitorData", vd)
                cfg.extra.forEach { (k, v) -> put(k, v) }
            }
            val body = JSONObject().apply {
                put("context", JSONObject().apply { put("client", clientObj) })
                put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
                if (cfg.params.isNotBlank()) put("params", cfg.params)
            }
            val json = post("player", body, cfg.ua) ?: continue
            val status = json.optJSONObject("playabilityStatus")
            if (!json.has("streamingData")) {
                lastStreamError = "${cfg.name}: no streamingData status=${status?.optString("status")} reason=${status?.optString("reason")}"
                continue
            }
            val info = extractStreamInfo(json)
            if (info != null) { Log.d(TAG, "Stream from ${cfg.name} ${info.height}p audio=${info.audioUrl != null}"); return info }
            lastStreamError = "${cfg.name}: streamingData present but no usable urls"
        }
        return null
    }

    // Legacy single-url getter used by quality picker
    fun getStreamUrl(videoId: String, preferredHeight: Int = 0): String? = getStreamInfo(videoId)?.videoUrl

    private fun extractStreamInfo(json: JSONObject): StreamInfo? {
        return try {
            val sd = json.getJSONObject("streamingData")
            val formats         = sd.optJSONArray("formats")
            val adaptiveFormats = sd.optJSONArray("adaptiveFormats")

            // Collect combined (video+audio) streams
            data class Fmt(val height: Int, val url: String, val mime: String, val bitrate: Int)
            val combined = mutableListOf<Fmt>()
            val videoOnly = mutableListOf<Fmt>()
            val audioOnly = mutableListOf<Fmt>()

            fun collectFmt(arr: JSONArray?, bucket: MutableList<Fmt>) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val mime = f.optString("mimeType","")
                    val url  = f.optString("url",""); if (url.isBlank()) continue
                    val h    = f.optInt("height", 0)
                    val br   = f.optInt("bitrate", 0)
                    bucket.add(Fmt(h, url, mime, br))
                }
            }
            collectFmt(formats, combined)
            // adaptiveFormats: video-only has height>0, audio-only has height==0
            val adaptive = mutableListOf<Fmt>()
            collectFmt(adaptiveFormats, adaptive)
            for (f in adaptive) {
                if (f.height > 0) videoOnly.add(f)
                else if (f.mime.startsWith("audio/")) audioOnly.add(f)
            }

            // Best combined (fallback, has audio but max 720p usually)
            val bestCombined = combined.filter { it.mime.startsWith("video/mp4") && it.url.isNotBlank() }
                .maxByOrNull { it.height }

            // Best adaptive video (can be 1080p/1440p)
            val bestVideo = videoOnly.filter { it.mime.startsWith("video/mp4") && it.url.isNotBlank() }
                .maxByOrNull { it.height }

            // Best adaptive audio (prefer mp4/aac over webm)
            val bestAudio = audioOnly
                .filter { it.url.isNotBlank() }
                .sortedWith(compareByDescending<Fmt> { it.mime.contains("mp4") }.thenByDescending { it.bitrate })
                .firstOrNull()

            when {
                // Prefer adaptive video+audio (best quality with sound)
                bestVideo != null && bestAudio != null ->
                    StreamInfo(bestVideo.url, bestAudio.url, bestVideo.height)
                // Fallback: combined stream (360p/720p but has audio)
                bestCombined != null ->
                    StreamInfo(bestCombined.url, null, bestCombined.height)
                // Last resort: video only
                bestVideo != null ->
                    StreamInfo(bestVideo.url, null, bestVideo.height)
                else -> null
            }
        } catch (e: Exception) { Log.e(TAG,"extractStreamInfo: ${e.message}"); null }
    }

    // Quality options — returns all heights with their video+audio urls
    fun getQualities(videoId: String): List<QualityOption> {
        if (cachedVisitorData == null) cachedVisitorData = fetchVisitorData()
        val vd = cachedVisitorData ?: ""
        val clientObj = JSONObject().apply {
            put("clientName","ANDROID_VR"); put("clientVersion","1.56.21")
            put("hl","en"); put("gl","US"); put("androidSdkVersion", 34)
            if (vd.isNotBlank()) put("visitorData", vd)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val ua = "Mozilla/5.0 (Linux; Android 14; Oculus Quest) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        val json = post("player", body, ua) ?: return emptyList()
        if (!json.has("streamingData")) return emptyList()
        val sd = json.getJSONObject("streamingData")

        // Get best audio URL to pair with each video quality
        val audioUrl = sd.optJSONArray("adaptiveFormats")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("url","").isNotBlank() && it.optInt("height",0) == 0 }
                .filter { it.optString("mimeType","").contains("mp4") || it.optString("mimeType","").contains("aac") }
                .maxByOrNull { it.optInt("bitrate",0) }
                ?.optString("url","")
        }

        val options = mutableListOf<QualityOption>()
        val seen = mutableSetOf<Int>()

        // Combined formats (already have audio)
        sd.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("mimeType","").startsWith("video/mp4")) continue
                val url = f.optString("url",""); if (url.isBlank()) continue
                val h = f.optInt("height", 0); if (h == 0 || seen.contains(h)) continue
                seen.add(h)
                options.add(QualityOption("${h}p", h, url, audioUrl = null, hasAudio = true))
            }
        }
        // Adaptive video formats — pair with audio
        sd.optJSONArray("adaptiveFormats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("mimeType","").startsWith("video/mp4")) continue
                val url = f.optString("url",""); if (url.isBlank()) continue
                val h = f.optInt("height", 0); if (h == 0 || seen.contains(h)) continue
                seen.add(h)
                // Pair with audio url — ExoPlayer will merge them
                options.add(QualityOption("${h}p", h, url, audioUrl = audioUrl, hasAudio = audioUrl != null))
            }
        }
        return options.sortedByDescending { it.height }
    }

    // ── Video detail ──────────────────────────────────────────────────────────

    fun getVideoDetail(videoId: String): VideoDetail? {
        val body = JSONObject().apply { put("context", CONTEXT_WEB); put("videoId", videoId) }
        val json = post("player", body) ?: return null
        return try {
            val d = json.getJSONObject("videoDetails")
            VideoDetail(videoId, d.optString("title"), d.optString("channelId"),
                d.optString("author"), d.optString("viewCount"),
                d.optString("shortDescription"), d.optString("lengthSeconds"),
                d.optBoolean("isLiveContent", false))
        } catch (e: Exception) { null }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    fun getComments(videoId: String): List<Comment> {
        // Use /next to get the page, then find the comments section continuation token
        val body = JSONObject().apply { put("context", CONTEXT_WEB); put("videoId", videoId) }
        val json = post("next", body) ?: return emptyList()

        val token = findCommentsToken(json)
        if (token == null) { Log.w(TAG, "No comments token found"); return emptyList() }

        // Fetch the comments using the token
        val body2 = JSONObject().apply { put("context", CONTEXT_WEB); put("continuation", token) }
        val json2 = post("next", body2) ?: return emptyList()
        return parseComments(json2)
    }

    private fun findCommentsToken(json: JSONObject): String? {
        // Walk ALL continuation tokens and pick the one that looks like a comments token
        // Comments tokens typically start with "Eg0" or contain specific patterns
        val tokens = mutableListOf<String>()
        fun walk(obj: Any) {
            when (obj) {
                is JSONObject -> {
                    // reloadContinuationData style
                    obj.optJSONObject("reloadContinuationData")?.optString("continuation","")
                        ?.takeIf { it.isNotBlank() }?.let { tokens.add(it) }
                    // continuationCommand style
                    obj.optJSONObject("continuationCommand")?.optString("token","")
                        ?.takeIf { it.isNotBlank() }?.let { tokens.add(it) }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) { }
        Log.d(TAG, "Found ${tokens.size} continuation tokens")
        // Comments tokens are typically the 2nd or 3rd token found (after related video tokens)
        // They're longer and start differently from related video tokens
        // Filter: comments tokens are usually >100 chars
        val commentTokens = tokens.filter { it.length > 100 }
        Log.d(TAG, "Comment-like tokens: ${commentTokens.size}")
        return commentTokens.lastOrNull() ?: tokens.lastOrNull()
    }

    private fun parseComments(json: JSONObject): List<Comment> {
        val results = mutableListOf<Comment>()
        fun walk(obj: Any) {
            if (results.size >= 20) return
            when (obj) {
                is JSONObject -> {
                    val cr = obj.optJSONObject("commentRenderer")
                    if (cr != null) {
                        val author = cr.optJSONObject("authorText")?.optString("simpleText") ?: ""
                        val text = cr.optJSONObject("contentText")?.optJSONArray("runs")
                            ?.let { runs -> (0 until runs.length()).joinToString("") {
                                runs.getJSONObject(it).optString("text") } }
                            ?: cr.optJSONObject("contentText")?.optString("simpleText") ?: ""
                        val likes = cr.optJSONObject("voteCount")?.optString("simpleText") ?: ""
                        val avatar = cr.optJSONObject("authorThumbnail")
                            ?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url") ?: ""
                        if (author.isNotBlank() && text.isNotBlank())
                            results.add(Comment(author, text, likes, avatar))
                    }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) { Log.e(TAG, "parseComments: ${e.message}") }
        Log.d(TAG, "Parsed ${results.size} comments")
        return results
    }

    // ── Related ───────────────────────────────────────────────────────────────

    fun getRelated(videoId: String): List<VideoResult> {
        val body = JSONObject().apply { put("context", CONTEXT_WEB); put("videoId", videoId) }
        return post("next", body)?.let { parseRelatedResults(it) } ?: emptyList()
    }

    // ── Channel ───────────────────────────────────────────────────────────────

    fun getChannelVideos(channelId: String): List<VideoResult> {
        val body = JSONObject().apply {
            put("context", CONTEXT_WEB); put("browseId", channelId)
            put("params", "EgZ2aWRlb3PyBgQKAjoA")
        }
        return post("browse", body)?.let { parseChannelResults(it) } ?: emptyList()
    }

    fun getStats(videoId: String): Map<String, String> {
        val d = getVideoDetail(videoId) ?: return emptyMap()
        return mapOf("title" to d.title, "channel" to d.channelName, "views" to d.viewCount,
                     "subs" to "", "likes" to "")
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseSearchResults(json: JSONObject): List<VideoResult> {
        val results = mutableListOf<VideoResult>()
        try {
            val contents = json.getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")
            for (i in 0 until contents.length()) {
                val items = contents.getJSONObject(i)
                    .optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val vr = items.getJSONObject(j).optJSONObject("videoRenderer") ?: continue
                    val id = vr.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                    val title = vr.optJSONObject("title")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text") ?: continue
                    val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text") ?: ""
                    val views = vr.optJSONObject("viewCountText")?.optString("simpleText") ?: ""
                    val duration = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    val avatar = vr.optJSONObject("channelThumbnailSupportedRenderers")
                        ?.optJSONObject("channelThumbnailWithLinkRenderer")
                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.optJSONObject(0)?.optString("url") ?: ""
                    results.add(VideoResult(id, title, channel, views, duration, avatar))
                    if (results.size >= 20) return results
                }
            }
        } catch (e: Exception) { Log.e(TAG, "parseSearch: ${e.message}") }
        return results
    }

    private fun parseChannelResults(json: JSONObject): List<VideoResult> {
        val results = mutableListOf<VideoResult>()
        fun walk(obj: Any) {
            if (results.size >= 10) return
            when (obj) {
                is JSONObject -> {
                    val vr = obj.optJSONObject("gridVideoRenderer")
                        ?: obj.optJSONObject("richItemRenderer")?.optJSONObject("content")
                            ?.optJSONObject("videoRenderer")
                    if (vr != null) {
                        val id = vr.optString("videoId").takeIf { it.isNotBlank() }
                        val title = vr.optJSONObject("title")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text")
                            ?: vr.optJSONObject("title")?.optString("simpleText")
                        if (id != null && title != null) results.add(VideoResult(id, title))
                    }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) {}
        return results
    }

    private fun parseRelatedResults(json: JSONObject): List<VideoResult> {
        val results = mutableListOf<VideoResult>()
        val rendererKeys = setOf("compactVideoRenderer","videoRenderer","endScreenVideoRenderer","gridVideoRenderer")
        fun walk(obj: Any) {
            if (results.size >= 20) return
            when (obj) {
                is JSONObject -> {
                    var cr: JSONObject? = null
                    for (key in rendererKeys) { cr = obj.optJSONObject(key); if (cr != null) break }
                    if (cr != null) {
                        val id = cr.optString("videoId").takeIf { it.isNotBlank() }
                        val title = cr.optJSONObject("title")?.optString("simpleText")
                            ?: cr.optJSONObject("title")?.optJSONArray("runs")
                                ?.optJSONObject(0)?.optString("text")
                        val ch = cr.optJSONObject("longBylineText")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text")
                            ?: cr.optJSONObject("ownerText")?.optJSONArray("runs")
                                ?.optJSONObject(0)?.optString("text") ?: ""
                        val views = cr.optJSONObject("viewCountText")?.optString("simpleText")
                            ?: cr.optJSONObject("shortViewCountText")?.optString("simpleText") ?: ""
                        val dur = cr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                        if (id != null && title != null) results.add(VideoResult(id, title, ch, views, dur))
                    }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) {}
        return results
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class VideoResult(val id: String, val title: String, val channel: String = "",
        val views: String = "", val duration: String = "", val channelAvatar: String = "")

    data class VideoDetail(val id: String, val title: String, val channelId: String,
        val channelName: String, val viewCount: String, val description: String,
        val lengthSeconds: String, val isLive: Boolean)

    data class Comment(val author: String, val text: String, val likes: String, val avatarUrl: String)

    data class QualityOption(val label: String, val height: Int, val url: String,
        val audioUrl: String? = null, val hasAudio: Boolean = true)
}
