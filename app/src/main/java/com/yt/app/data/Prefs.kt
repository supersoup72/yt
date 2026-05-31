package com.yt.app.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("yt_prefs", Context.MODE_PRIVATE)

    // --- History ---
    fun addHistory(videoId: String) {
        val list = getHistory().toMutableList()
        list.remove(videoId)
        list.add(0, videoId)
        prefs.edit { putString("history", list.take(20).joinToString(",")) }
    }
    fun getHistory(): List<String> =
        prefs.getString("history", "")!!.split(",").filter { it.isNotBlank() }

    // --- Likes ---
    fun toggleLike(videoId: String): Boolean {
        val likes = getLikes().toMutableSet()
        return if (likes.contains(videoId)) {
            likes.remove(videoId)
            prefs.edit { putStringSet("likes", likes) }
            false
        } else {
            likes.add(videoId)
            prefs.edit { putStringSet("likes", likes) }
            true
        }
    }
    fun isLiked(videoId: String): Boolean = getLikes().contains(videoId)
    fun getLikes(): Set<String> = prefs.getStringSet("likes", emptySet()) ?: emptySet()

    // --- Subscriptions (channel URLs) ---
    fun toggleSub(channelUrl: String): Boolean {
        val subs = getSubs().toMutableSet()
        return if (subs.contains(channelUrl)) {
            subs.remove(channelUrl)
            prefs.edit { putStringSet("subs", subs) }
            false
        } else {
            subs.add(channelUrl)
            prefs.edit { putStringSet("subs", subs) }
            true
        }
    }
    fun isSub(channelUrl: String): Boolean = getSubs().contains(channelUrl)
    fun getSubs(): Set<String> = prefs.getStringSet("subs", emptySet()) ?: emptySet()

    // --- Downloads (video IDs saved locally) ---
    fun addDownload(videoId: String) {
        val dl = getDownloads().toMutableSet()
        dl.add(videoId)
        prefs.edit { putStringSet("downloads", dl) }
    }
    fun getDownloads(): Set<String> = prefs.getStringSet("downloads", emptySet()) ?: emptySet()
}
