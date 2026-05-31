package com.yt.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var repo: VideoRepository

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"

        fun start(context: Context, videoId: String, title: String = "") {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_VIDEO_TITLE, title)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        repo = VideoRepository(this)

        // Build layout programmatically
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)

        playerView = PlayerView(this)
        playerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        playerView.useController = true

        progressBar = ProgressBar(this)
        progressBar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )

        root.addView(playerView)
        root.addView(progressBar)
        setContentView(root)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: run {
            Toast.makeText(this, "No video ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Add to history
        repo.prefs.addHistory(videoId)

        // Setup ExoPlayer with YouTube-compatible headers
        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)")
            .setDefaultRequestProperties(mapOf(
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            ))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSource))
            .build()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progressBar.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) finish()
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = "Playback error: ${error.message}\nCause: ${error.cause?.message}"
                Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            }
        })

        // Resolve stream URL on IO thread
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val streamUrl = repo.getStreamUrl(videoId)

            withContext(Dispatchers.Main) {
                if (streamUrl != null) {
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    android.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Stream Error")
                        .setMessage("Could not get stream URL.\n\nVideo: $videoId\n\nReason: ${com.yt.app.YtWebClient.lastStreamError}")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .show()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
