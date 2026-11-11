package com.suryasukma.vcamtest.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer

/**
 * Handles Media3/ExoPlayer lifecycle and state management for video injection.
 *
 * All player calls happen on the main thread. Every play()/stop() call bumps [generation]; a queued request whose
 * generation is no longer current is dropped, so the newest request always wins and none is lost to a "busy" flag.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var isPlayingInternal = false

    // Playback position is derived from a wall-clock anchor instead of polling the player (or blocking callers).
    @Volatile private var anchorWallMs = 0L
    @Volatile private var anchorPosMs = 0L
    @Volatile private var durationMs = 0L

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    /** Approximate current position of the looping clip. Never blocks. */
    val currentPosition: Long
        get() {
            if (!isPlayingInternal) return anchorPosMs
            val elapsed = SystemClock.elapsedRealtime() - anchorWallMs
            val pos = anchorPosMs + elapsed
            val d = durationMs
            return if (d > 0) pos % d else pos
        }

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

    private fun releasePlayer() {
        val p = player
        player = null
        try {
            p?.apply {
                stop()
                clearVideoSurface()
                release()
            }
        } catch (e: Throwable) {
            log("MEDIA-ENGINE", "Release failed: ${e.message}")
        }
        isPlayingInternal = false
        anchorPosMs = 0L
        durationMs = 0L
        videoWidth = 0
        videoHeight = 0
    }

    fun stop() {
        val gen = ++generation
        mainHandler.post {
            if (gen != generation) return@post // a newer play() already replaced this stop
            releasePlayer()
        }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        onPrepared: ((ExoPlayer?) -> Unit)? = null
    ) {
        val gen = ++generation
        mainHandler.post {
            if (gen != generation) return@post // superseded by a newer request
            releasePlayer()
            if (!surface.isValid) return@post

            try {
                log(tag, "Loading media: $path (Video only pipeline)")

                val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                val exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory).build()
                player = exoPlayer
                exoPlayer.volume = 0f // video only: the audio path is handled by AudioHook

                val effects = mutableListOf<Effect>()
                if (isMirrored || rotationAngle != 0) {
                    effects.add(
                        ScaleAndRotateTransformation.Builder()
                            .setScale(if (isMirrored) -1f else 1f, 1f)
                            .setRotationDegrees(rotationAngle.toFloat())
                            .build()
                    )
                }
                if (effects.isNotEmpty()) exoPlayer.setVideoEffects(effects)

                exoPlayer.setMediaItem(MediaItem.fromUri(path.toUri()))
                exoPlayer.setVideoSurface(surface)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE

                var started = false
                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (gen != generation) return
                        isPlayingInternal = isPlaying
                        anchorPosMs = exoPlayer.currentPosition
                        anchorWallMs = SystemClock.elapsedRealtime()
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_READY || started || gen != generation) return
                        started = true
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                        if (videoWidth == 0) {
                            videoWidth = exoPlayer.videoSize.width
                            videoHeight = exoPlayer.videoSize.height
                        }
                        try {
                            exoPlayer.play()
                            log(tag, "Player ACTIVE (${videoWidth}x${videoHeight})")
                            onPrepared?.invoke(exoPlayer)
                        } catch (e: Throwable) {
                            log(tag, "Start failed: ${e.message}")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        log(tag, "Player Error: ${error.errorCodeName} | ${error.message}")
                        if (gen == generation) releasePlayer()
                    }
                })

                exoPlayer.prepare()
            } catch (e: Throwable) {
                log(tag, "Prepare failed: ${e.message}")
                releasePlayer()
            }
        }
    }
}
