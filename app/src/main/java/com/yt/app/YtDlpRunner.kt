package com.yt.app

import android.content.Context
import android.util.Log
import java.io.File

class YtDlpRunner(private val context: Context) {

    companion object {
        private const val TAG = "YtDlpRunner"
    }

    // codeCacheDir is exempt from noexec on Android — unlike filesDir
    private val binary: File = File(context.codeCacheDir, "ytdlp")

    fun binaryPath(): String = binary.absolutePath
    fun binaryExecutable(): Boolean = binary.canExecute()

    fun ensureBinary() {
        if (!binary.exists() || binary.length() < 1000L) {
            Log.d(TAG, "Extracting yt-dlp to codeCacheDir...")
            context.assets.open("yt-dlp").use { input ->
                binary.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }
        Log.d(TAG, "Binary: ${binary.absolutePath}, size=${binary.length()}, exec=${binary.canExecute()}")
    }

    fun run(vararg args: String): List<String> {
        val raw = runRaw(*args)
        return raw.first
    }

    fun runRaw(vararg args: String): Triple<List<String>, Int, String> {
        ensureBinary()
        val cmd = listOf(binary.absolutePath,
            "--quiet", "--no-warnings", "--no-check-certificate", "--no-config") +
            args.toList()
        Log.d(TAG, "CMD: ${cmd.joinToString(" ")}")
        return try {
            val process = ProcessBuilder(cmd).apply {
                environment()["HOME"] = context.codeCacheDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
                environment()["SSL_CERT_FILE"] = ""
            }.start()

            val stdout = mutableListOf<String>()
            val stderr = StringBuilder()
            val t1 = Thread { process.inputStream.bufferedReader().forEachLine { stdout.add(it) } }
            val t2 = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            t1.start(); t2.start(); t1.join(); t2.join()
            val exit = process.waitFor()

            Log.d(TAG, "Exit=$exit lines=${stdout.size} stderr=${stderr.take(200)}")
            Triple(stdout.filter { it.isNotBlank() }, exit, stderr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Run error: ${e.message}", e)
            Triple(emptyList(), -1, e.message ?: "unknown")
        }
    }

    fun search(query: String, count: Int = 20): List<String> =
        run("ytsearch${count}:$query", "--flat-playlist", "--print", "%(title)s | %(id)s")

    fun searchRaw(query: String, count: Int = 20): Triple<List<String>, Int, String> =
        runRaw("ytsearch${count}:$query", "--flat-playlist", "--print", "%(title)s | %(id)s")

    fun getStreamUrl(videoId: String, quality: String = "best[ext=mp4]/best"): String? =
        run("-f", quality, "-g", "https://www.youtube.com/watch?v=$videoId").firstOrNull()

    fun getChannelUrl(videoId: String): String? =
        run("--print", "%(channel_url)s", "https://www.youtube.com/watch?v=$videoId").firstOrNull()

    fun getChannelFeed(channelUrls: Set<String>, perChannel: Int = 5): List<String> {
        val results = mutableListOf<String>()
        for (url in channelUrls) {
            results.addAll(run("--playlist-end", "$perChannel", "--flat-playlist",
                "--print", "%(title)s | %(id)s", url))
        }
        return results
    }

    fun getStats(videoId: String): Map<String, String> {
        val lines = run(
            "--print", "%(title)s", "--print", "%(uploader)s",
            "--print", "%(channel_follower_count)s",
            "--print", "%(view_count)s", "--print", "%(like_count)s",
            "https://www.youtube.com/watch?v=$videoId"
        )
        return mapOf(
            "title"   to (lines.getOrNull(0) ?: ""),
            "channel" to (lines.getOrNull(1) ?: ""),
            "subs"    to (lines.getOrNull(2) ?: ""),
            "views"   to (lines.getOrNull(3) ?: ""),
            "likes"   to (lines.getOrNull(4) ?: "")
        )
    }

    fun getRelated(videoId: String): List<String> {
        val title = run("--print", "%(title)s",
            "https://www.youtube.com/watch?v=$videoId").firstOrNull() ?: return emptyList()
        val keywords = title.split(" ").take(6).joinToString(" ")
        return search(keywords, 20).filter { !it.endsWith("| $videoId") }
    }

    fun getHomeFeed(historyIds: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()
        for (id in historyIds.take(5)) {
            val title = run("--print", "%(title)s",
                "https://www.youtube.com/watch?v=$id").firstOrNull() ?: continue
            val keywords = title.split(" ").take(5).joinToString(" ")
            for (item in search(keywords, 6)) {
                val itemId = item.substringAfterLast("| ").trim()
                if (itemId !in seen && itemId !in historyIds) {
                    seen.add(itemId); results.add(item)
                }
            }
        }
        return results
    }

    fun download(videoId: String, outputDir: File,
                 quality: String = "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"): Boolean {
        ensureBinary()
        val cmd = listOf(binary.absolutePath,
            "--quiet", "--no-warnings", "--no-check-certificate", "--no-config",
            "-f", quality, "--merge-output-format", "mp4",
            "-o", "${outputDir.absolutePath}/%(title)s.%(ext)s",
            "https://www.youtube.com/watch?v=$videoId")
        return try {
            ProcessBuilder(cmd).apply {
                environment()["HOME"] = context.codeCacheDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }.start().waitFor() == 0
        } catch (e: Exception) { false }
    }
}
