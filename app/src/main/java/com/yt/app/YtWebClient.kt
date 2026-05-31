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

    // Fetch visitorData from YouTube homepage — required for player API
    private fun fetchVisitorData(): String? {
        return try {
            val conn = URL("https://www.youtube.com/").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Regex(""""visitorData":"([^"]+)"""").find(html)?.groupValues?.get(1)
                .also { Log.d(TAG, "visitorData fetched: ${it?.take(20)}") }
        } catch (e: Exception) {
            Log.e(TAG, "fetchVisitorData: ${e.message}"); null
        }
    }

    fun search(query: String): List<VideoResult> {
        val body = JSONObject().apply {
            put("context", CONTEXT)
            put("query", query)
            put("params", "EgIQAQ==")
        }
        return post("search", body)?.let { parseSearchResults(it) } ?: emptyList()
    }

    fun getStreamUrl(videoId: String): String? {
        if (cachedVisitorData == null) cachedVisitorData = fetchVisitorData()
        val vd = cachedVisitorData ?: ""

        data class ClientConfig(val name: String, val version: String, val ua: String, val extra: Map<String,Any> = emptyMap(), val params: String = "")

        val configs = listOf(
            // ANDROID_VR (Oculus Quest) - works without PO token as of 2025
            ClientConfig(
                "ANDROID_VR", "1.56.21",
                "Mozilla/5.0 (Linux; Android 14; Oculus Quest) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                mapOf("androidSdkVersion" to 34, "deviceModel" to "Quest 3")
            ),
            // ANDROID with NewPipe bypass params
            ClientConfig(
                "ANDROID", "19.02.39",
                "com.google.android.youtube/19.02.39 (Linux; U; Android 14) gzip",
                mapOf("androidSdkVersion" to 34),
                params = "CgIQBg=="
            ),
            // IOS
            ClientConfig(
                "IOS", "19.09.3",
                "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
                mapOf("deviceModel" to "iPhone16,2")
            )
        )

        for (cfg in configs) {
            val clientObj = JSONObject().apply {
                put("clientName", cfg.name)
                put("clientVersion", cfg.version)
                put("hl", "en"); put("gl", "US")
                if (vd.isNotBlank()) put("visitorData", vd)
                cfg.extra.forEach { (k, v) -> put(k, v) }
            }
            val body = JSONObject().apply {
                put("context", JSONObject().apply { put("client", clientObj) })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (cfg.params.isNotBlank()) put("params", cfg.params)
            }

            val json = post("player", body, cfg.ua) ?: continue
            val status = json.optJSONObject("playabilityStatus")
            val statusStr = status?.optString("status") ?: ""
            val reason = status?.optString("reason") ?: ""
            Log.d(TAG, "${cfg.name} status=$statusStr reason=$reason")

            if (!json.has("streamingData")) {
                lastStreamError = "${cfg.name}: no streamingData status=$statusStr reason=$reason"
                continue
            }

            val url = extractBestUrl(json)
            if (url != null) { Log.d(TAG, "Stream OK from ${cfg.name}"); return url }
            lastStreamError = "${cfg.name}: streamingData present but all urls are cipher-signed"
        }
        return null
    }

    private fun extractBestUrl(json: JSONObject): String? {
        return try {
            val streaming = json.getJSONObject("streamingData")
            var bestUrl: String? = null; var bestHeight = 0
            fun check(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    if (!f.optString("mimeType").startsWith("video/mp4")) continue
                    val u = f.optString("url", "")
                    if (u.isBlank()) continue
                    val h = f.optInt("height", 0)
                    if (h > bestHeight) { bestUrl = u; bestHeight = h }
                }
            }
            check(streaming.optJSONArray("formats"))
            if (bestUrl == null) check(streaming.optJSONArray("adaptiveFormats"))
            Log.d(TAG, "extractBestUrl: ${bestHeight}p found=${bestUrl != null}")
            bestUrl
        } catch (e: Exception) { null }
    }

    fun getStats(videoId: String): Map<String, String> {
        val body = JSONObject().apply { put("context", CONTEXT); put("videoId", videoId) }
        val json = post("player", body) ?: return emptyMap()
        return try {
            val d = json.getJSONObject("videoDetails")
            mapOf("title" to d.optString("title"), "channel" to d.optString("author"),
                  "views" to d.optString("viewCount"), "subs" to "", "likes" to "")
        } catch (e: Exception) { emptyMap() }
    }

    fun getRelated(videoId: String): List<VideoResult> {
        val body = JSONObject().apply { put("context", CONTEXT); put("videoId", videoId) }
        return post("next", body)?.let { parseRelatedResults(it) } ?: emptyList()
    }

    fun getChannelVideos(channelId: String): List<VideoResult> {
        val body = JSONObject().apply {
            put("context", CONTEXT); put("browseId", channelId)
            put("params", "EgZ2aWRlb3PyBgQKAjoA")
        }
        return post("browse", body)?.let { parseChannelResults(it) } ?: emptyList()
    }

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
                    .optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val vr = items.getJSONObject(j).optJSONObject("videoRenderer") ?: continue
                    val id = vr.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                    val title = vr.optJSONObject("title")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text") ?: continue
                    val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text") ?: ""
                    val views = vr.optJSONObject("viewCountText")?.optString("simpleText") ?: ""
                    results.add(VideoResult(id, title, channel, views))
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
        try { walk(json) } catch (e: Exception) { Log.e(TAG, "parseChannel: ${e.message}") }
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
                        if (id != null && title != null) results.add(VideoResult(id, title, ch))
                    }
                    obj.keys().forEach { k -> walk(obj.get(k)) }
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.get(i))
            }
        }
        try { walk(json) } catch (e: Exception) { Log.e(TAG, "parseRelated: ${e.message}") }
        return results
    }

    data class VideoResult(val id: String, val title: String, val channel: String = "", val views: String = "")
}
