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
 * All player calls happen on the main thread. Every play()/stop()/playChallengeSet() call bumps [generation]; a
 * queued request whose generation is no longer current is dropped, so the newest request always wins and none is
 * lost to a "busy" flag.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    /** One labeled clip in a Challenge Test playlist (see [playChallengeSet]). */
    data class ChallengeItem(val role: String, val path: String)

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

    // Roles loaded into the current playlist, in player-item order; empty when not in Challenge Test mode.
    @Volatile private var challengeRoles: List<String> = emptyList()

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    /** True while a multi-clip Challenge Test playlist (see [playChallengeSet]) is the active player. */
    val isChallengeActive: Boolean
        get() = challengeRoles.isNotEmpty() && player != null

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
        challengeRoles = emptyList()
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

    private fun buildPlayer(context: Context, isMirrored: Boolean, rotationAngle: Int): ExoPlayer {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory).build()
        exoPlayer.volume = 0f // video only: the audio path is handled by AudioHook
        if (isMirrored || rotationAngle != 0) {
            exoPlayer.setVideoEffects(
                listOf(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(if (isMirrored) -1f else 1f, 1f)
                        .setRotationDegrees(rotationAngle.toFloat())
                        .build()
                )
            )
        }
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        return exoPlayer
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        onPrepared: ((ExoPlayer?) -> Unit)? = null
    ) = playInternal(context, path, surface, tag, isMirrored, rotationAngle, onPrepared, attempt = 0)

    /** A failed or stuck player is restarted a couple of times instead of leaving a frozen preview behind. */
    private fun playInternal(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean,
        rotationAngle: Int,
        onPrepared: ((ExoPlayer?) -> Unit)?,
        attempt: Int,
    ) {
        val gen = ++generation
        val retry = {
            if (attempt < MAX_RETRIES) {
                log(tag, "Restarting player (attempt ${attempt + 1}/$MAX_RETRIES)")
                mainHandler.postDelayed({
                    if (gen == generation) playInternal(context, path, surface, tag, isMirrored, rotationAngle, onPrepared, attempt + 1)
                }, RETRY_DELAY_MS)
            }
        }
        mainHandler.post {
            if (gen != generation) return@post // superseded by a newer request
            releasePlayer()
            if (!surface.isValid) return@post

            try {
                log(tag, "Loading media: $path (Video only pipeline)")

                val exoPlayer = buildPlayer(context, isMirrored, rotationAngle)
                player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(path.toUri()))
                exoPlayer.setVideoSurface(surface)

                var started = false
                // Watchdog: a player that never becomes ready (stuck decoder / surface) would show a frozen frame forever.
                mainHandler.postDelayed({
                    if (gen == generation && !started) {
                        log(tag, "Player not ready after ${READY_TIMEOUT_MS} ms")
                        releasePlayer()
                        retry()
                    }
                }, READY_TIMEOUT_MS)
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
                        if (gen == generation) {
                            releasePlayer()
                            retry()
                        }
                    }
                })

                exoPlayer.prepare()
            } catch (e: Throwable) {
                log(tag, "Prepare failed: ${e.message}")
                releasePlayer()
                retry()
            }
        }
    }

    /**
     * Prepares a looping multi-clip playlist for Challenge Test mode: every clip in [items] is loaded once, and
     * [switchChallenge] then jumps between them with a single seek — far faster than tearing the player down and
     * rebuilding it, which is what [play] does for the normal single-clip case and would otherwise make switching
     * feel laggy when reacting to an active-liveness prompt.
     */
    fun playChallengeSet(
        context: Context,
        items: List<ChallengeItem>,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        startRole: String? = null,
    ) {
        if (items.isEmpty()) return
        val gen = ++generation
        mainHandler.post {
            if (gen != generation) return@post
            releasePlayer()
            if (!surface.isValid) return@post

            try {
                log(tag, "Loading challenge set: ${items.map { it.role }}")

                val exoPlayer = buildPlayer(context, isMirrored, rotationAngle)
                player = exoPlayer
                challengeRoles = items.map { it.role }
                exoPlayer.setVideoSurface(surface)
                val startIndex = startRole
                    ?.let { role -> items.indexOfFirst { it.role == role } }
                    ?.takeIf { it >= 0 } ?: 0
                exoPlayer.setMediaItems(items.map { MediaItem.fromUri(it.path.toUri()) }, startIndex, 0L)

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
                        // Re-fires on every switchChallenge() seek too: recomputing duration and (re-)calling play()
                        // each time is cheap and keeps the current item's duration correct for currentPosition().
                        if (playbackState != Player.STATE_READY || gen != generation) return
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                        try {
                            exoPlayer.play()
                        } catch (e: Throwable) {
                            log(tag, "Challenge start failed: ${e.message}")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        log(tag, "Challenge player Error: ${error.errorCodeName} | ${error.message}")
                        if (gen == generation) releasePlayer()
                    }
                })

                exoPlayer.prepare()
            } catch (e: Throwable) {
                log(tag, "Challenge prepare failed: ${e.message}")
                releasePlayer()
            }
        }
    }

    /** Switches the active clip within a running Challenge Test playlist. No-op if [role] isn't loaded. */
    fun switchChallenge(role: String) {
        mainHandler.post {
            val index = challengeRoles.indexOf(role)
            val p = player
            if (index < 0 || p == null) {
                log("CHALLENGE", "Switch to '$role' ignored (not loaded or no active session)")
                return@post
            }
            try {
                p.seekTo(index, 0L)
                p.playWhenReady = true
                anchorPosMs = 0L
                anchorWallMs = SystemClock.elapsedRealtime()
                log("CHALLENGE", "Switched to '$role'")
            } catch (e: Throwable) {
                log("CHALLENGE", "Switch to '$role' failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 400L
        const val READY_TIMEOUT_MS = 4000L
    }
}
