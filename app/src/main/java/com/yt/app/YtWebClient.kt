package com.yt.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YtWebClient {

    private const val TAG = "YtWebClient"
    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private val CONTEXT = JSONObject("""{"client":{"clientName":"WEB","clientVersion":"2.20240101","hl":"en","gl":"US"}}""")

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
            conn.readTimeout = 15000
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
            put("context", CONTEXT); put("query", query); put("params", "EgIQAQ==")
        }
        return post("search", body)?.let { parseSearchResults(it) } ?: emptyList()
    }

    // ── Stream URL (tries multiple clients) ───────────────────────────────────

    fun getStreamUrl(videoId: String, preferredHeight: Int = 0): String? {
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
            val url = extractUrl(json, preferredHeight)
            if (url != null) return url
            lastStreamError = "${cfg.name}: streamingData present but no direct mp4 url"
        }
        return null
    }

    // Returns list of available quality options: Pair(label, url)
    fun getQualities(videoId: String): List<QualityOption> {
        if (cachedVisitorData == null) cachedVisitorData = fetchVisitorData()
        val vd = cachedVisitorData ?: ""
        val clientObj = JSONObject().apply {
            put("clientName", "ANDROID_VR"); put("clientVersion", "1.56.21")
            put("hl", "en"); put("gl", "US"); put("androidSdkVersion", 34)
            if (vd.isNotBlank()) put("visitorData", vd)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientObj) })
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val ua = "Mozilla/5.0 (Linux; Android 14; Oculus Quest) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        val json = post("player", body, ua) ?: return emptyList()
        if (!json.has("streamingData")) return emptyList()
        val options = mutableListOf<QualityOption>()
        val seen = mutableSetOf<Int>()
        fun scanArr(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("mimeType").startsWith("video/mp4")) continue
                val url = f.optString("url", ""); if (url.isBlank()) continue
                val h = f.optInt("height", 0); if (h == 0 || seen.contains(h)) continue
                seen.add(h); options.add(QualityOption("${h}p", h, url))
            }
        }
        val sd = json.getJSONObject("streamingData")
        // Only scan `formats` (combined audio+video) for quality options
        scanArr(sd.optJSONArray("formats"))
        // If no combined formats found, fall back to adaptive
        if (options.isEmpty()) scanArr(sd.optJSONArray("adaptiveFormats"))
        return options.sortedByDescending { it.height }
    }

    private fun extractUrl(json: JSONObject, preferredHeight: Int = 0): String? {
        return try {
            val sd = json.getJSONObject("streamingData")

            // `formats` = combined audio+video streams (always prefer these)
            // `adaptiveFormats` = video-only or audio-only (no sound if picked alone)
            val combined = mutableListOf<Pair<Int,String>>()
            val adaptive = mutableListOf<Pair<Int,String>>()

            fun scan(arr: JSONArray?, bucket: MutableList<Pair<Int,String>>) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    // Only combined streams have both video+audio; skip audio-only adaptive
                    if (!mime.startsWith("video/mp4")) continue
                    val u = f.optString("url", ""); if (u.isBlank()) continue
                    bucket.add(Pair(f.optInt("height", 0), u))
                }
            }

            scan(sd.optJSONArray("formats"), combined)
            scan(sd.optJSONArray("adaptiveFormats"), adaptive)

            // Always pick from combined first (has audio), fall back to adaptive only if none
            val pool = if (combined.isNotEmpty()) combined else adaptive
            if (pool.isEmpty()) return null

            if (preferredHeight > 0) {
                pool.minByOrNull { Math.abs(it.first - preferredHeight) }?.second
            } else {
                pool.maxByOrNull { it.first }?.second
            }
        } catch (e: Exception) { null }
    }

    // ── Video detail (title, channel, views, description, likes) ─────────────

    fun getVideoDetail(videoId: String): VideoDetail? {
        val body = JSONObject().apply { put("context", CONTEXT); put("videoId", videoId) }
        val json = post("player", body) ?: return null
        return try {
            val d = json.getJSONObject("videoDetails")
            VideoDetail(
                id = videoId,
                title = d.optString("title"),
                channelId = d.optString("channelId"),
                channelName = d.optString("author"),
                viewCount = d.optString("viewCount"),
                description = d.optString("shortDescription"),
                lengthSeconds = d.optString("lengthSeconds"),
                isLive = d.optBoolean("isLiveContent", false)
            )
        } catch (e: Exception) { null }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    fun getComments(videoId: String): List<Comment> {
        // Step 1: get the /next response to find the comments continuation token
        val body = JSONObject().apply { put("context", CONTEXT); put("videoId", videoId) }
        val json = post("next", body) ?: return emptyList()

        // Step 2: find continuation token inside engagementPanels
        val token = findContinuationToken(json, "comments") ?: return emptyList()

        // Step 3: fetch comments with the token
        val body2 = JSONObject().apply { put("context", CONTEXT); put("continuation", token) }
        val json2 = post("next", body2) ?: return emptyList()
        return parseComments(json2)
    }

    private fun findContinuationToken(json: JSONObject, hint: String): String? {
        var token: String? = null
        fun walk(obj: Any) {
            if (token != null) return
            when (obj) {
                is JSONObject -> {
                    // continuationCommand is how Innertube passes section tokens
                    val cc = obj.optJSONObject("continuationCommand")
                    if (cc != null) {
                        val t = cc.optString("token", "")
                        if (t.isNotBlank()) { token = t; return }
                    }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) { }
        return token
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
                                runs.getJSONObject(it).optString("text") } } ?: ""
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
        return results
    }

    // ── Related ───────────────────────────────────────────────────────────────

    fun getRelated(videoId: String): List<VideoResult> {
        val body = JSONObject().apply { put("context", CONTEXT); put("videoId", videoId) }
        return post("next", body)?.let { parseRelatedResults(it) } ?: emptyList()
    }

    // ── Channel ───────────────────────────────────────────────────────────────

    fun getChannelVideos(channelId: String): List<VideoResult> {
        val body = JSONObject().apply {
            put("context", CONTEXT); put("browseId", channelId)
            put("params", "EgZ2aWRlb3PyBgQKAjoA")
        }
        return post("browse", body)?.let { parseChannelResults(it) } ?: emptyList()
    }

    fun getStats(videoId: String): Map<String, String> {
        val detail = getVideoDetail(videoId) ?: return emptyMap()
        return mapOf("title" to detail.title, "channel" to detail.channelName,
                     "views" to detail.viewCount, "subs" to "", "likes" to "")
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
                        ?: obj.optJSONObject("richItemRenderer")?.optJSONObject("content")?.optJSONObject("videoRenderer")
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
        fun walk(obj: Any) {
            if (results.size >= 20) return
            when (obj) {
                is JSONObject -> {
                    val cr = obj.optJSONObject("compactVideoRenderer")
                    if (cr != null) {
                        val id = cr.optString("videoId").takeIf { it.isNotBlank() }
                        val title = cr.optJSONObject("title")?.optString("simpleText")
                        val ch = cr.optJSONObject("longBylineText")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text") ?: ""
                        val views = cr.optJSONObject("viewCountText")?.optString("simpleText") ?: ""
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

    data class VideoResult(
        val id: String, val title: String, val channel: String = "",
        val views: String = "", val duration: String = "", val channelAvatar: String = ""
    )

    data class VideoDetail(
        val id: String, val title: String, val channelId: String,
        val channelName: String, val viewCount: String,
        val description: String, val lengthSeconds: String, val isLive: Boolean
    )

    data class Comment(val author: String, val text: String, val likes: String, val avatarUrl: String)
    data class QualityOption(val label: String, val height: Int, val url: String)
}
