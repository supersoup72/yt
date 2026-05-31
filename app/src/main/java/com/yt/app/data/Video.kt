package com.yt.app.data

data class Video(
    val id: String,
    val title: String,
    val channel: String = "",
    val views: String = "",
    val likes: String = "",
    val subs: String = "",
    val duration: String = ""
) {
    val thumbnailUrl: String
        get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$id"
}
