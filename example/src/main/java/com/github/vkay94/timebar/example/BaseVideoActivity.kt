package com.github.vkay94.timebar.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlin.math.max


@SuppressLint("Registered")
open class BaseVideoActivity : AppCompatActivity() {

    var videoPlayer: PlayerView? = null
    var player: SimpleExoPlayer? = null

    var progressListener: ProgressUpdateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
    }

    fun buildMediaSource(mUri: Uri) {
        val dataSourceFactory = DefaultDataSourceFactory(
            this@BaseVideoActivity,
            Util.getUserAgent(this@BaseVideoActivity, resources.getString(R.string.app_name)),
            DefaultBandwidthMeter.Builder(this@BaseVideoActivity).build()
        )
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory, Mp4ExtractorFactory())
            .createMediaSource(MediaItem.fromUri(mUri))

        player?.apply {
            setMediaSource(videoSource)
            prepare()
        }
    }

    fun initializePlayer() {
        if (player == null) {
            val loadControl: LoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_DURATION,
                    MAX_BUFFER_DURATION,
                    MIN_PLAYBACK_START_BUFFER,
                    MIN_PLAYBACK_RESUME_BUFFER
                )
                .build()

            player = SimpleExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build()

            videoPlayer?.player = player

            updateHandler.removeCallbacks(updateRunnable)
            updateHandler.postDelayed(updateRunnable, max(max(0, updateDelay), DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS))
        }
    }

    var updateDelay: Long = DEFAULT_MAX_UPDATE_INTERVAL_MS
        set(value) {
            field = value
                .coerceAtLeast(DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS)
                .coerceAtMost(DEFAULT_MAX_UPDATE_INTERVAL_MS)
        }

    // Player Lifecycle
    fun releasePlayer() {
        if (player != null) {
            player?.release()
            player = null
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onRestart() {
        super.onRestart()
        if (player?.playbackState == Player.STATE_READY && player?.playWhenReady!!)
            player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    // Update logic for TimeBar

    private val updateHandler = Handler(Looper.myLooper()!!)
    private val updateRunnable = object : Runnable {
        override fun run() {
            player?.let {
                if (it.duration > 0) {
                    progressListener?.onProgressUpdate(it.currentPosition, it.bufferedPosition, it.duration)
                }
                Log.d("CHECK", "updateDelay = $updateDelay, res = ${max(max(0, updateDelay), DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS)}")
                updateHandler.postDelayed(this, max(max(0, updateDelay), DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS))
            }
        }
    }

    interface ProgressUpdateListener {
        fun onProgressUpdate(currentPosition: Long, bufferedPosition: Long, duration: Long)
    }

    companion object {
        const val MIN_BUFFER_DURATION = 15000
        const val MAX_BUFFER_DURATION = 60000
        const val MIN_PLAYBACK_START_BUFFER = 2500
        const val MIN_PLAYBACK_RESUME_BUFFER = 5000

        const val DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS = 200L
        const val DEFAULT_MAX_UPDATE_INTERVAL_MS = 1000L

        fun <T: BaseVideoActivity> newIntent(context: Context, activity: Class<T>): Intent =
            Intent(context, activity)
    }
}
