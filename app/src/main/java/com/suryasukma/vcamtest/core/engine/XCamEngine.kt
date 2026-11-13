package com.suryasukma.vcamtest.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.suryasukma.vcamtest.core.settings.SettingsManager
import com.suryasukma.vcamtest.core.surface.SurfaceManager
import com.suryasukma.vcamtest.core.surface.SurfaceProvider

@UnstableApi
class XCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val mediaEngine: MediaEngine,
    private val surfaceProvider: SurfaceProvider,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
    private var lastInjectedGen = -1
    private var lastInjectedSurfaceId = -1L

    // The most recently seen live preview surface + the Context that owns it, kept independently of whether the
    // normal single-media slot has anything configured: Challenge Test only needs a valid camera preview to target.
    @Volatile private var activeSurface: Surface? = null
    @Volatile private var activeContext: Context? = null

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")
    private fun logPipe(msg: String) = log("PIPELINE", msg)

    fun isPlaying() = mediaEngine.isPlaying
    fun getCurrentPosition() = mediaEngine.currentPosition
    fun isChallengeActive() = mediaEngine.isChallengeActive

    fun stop() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaEngine.stop()
        lastST = null
        lastModernSurface = null
        lastInjectedSurfaceId = -1L
        lastInjectedGen = -1
        activeSurface = null
        activeContext = null
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val surface = Surface(st)
        if (!surfaceManager.isPreviewSurface(surface)) {
            surface.release()
            return
        }

        contextProvider()?.let {
            activeSurface = surface
            activeContext = it
        }

        if ((st == lastST) && mediaEngine.isPlaying) {
            lastInjectedGen = surfaceManager.sessionGeneration
            return
        }
        lastST = st
        lastInjectedGen = surfaceManager.sessionGeneration

        val path = settingsProvider().mediaPath ?: return
        val context = contextProvider() ?: return
        val settings = settingsProvider()

        logPipe("Legacy Hook: Injecting to SurfaceTexture")
        mediaEngine.play(context, path, surface, "Legacy", settings.isMirrored, settings.rotationAngle)
    }

    fun handleModernPreview(surface: Surface) {
        val currentGen = surfaceManager.sessionGeneration
        contextProvider()?.let {
            activeSurface = surface
            activeContext = it
        }
        if (surface == lastModernSurface && mediaEngine.isPlaying) {
            lastInjectedGen = currentGen
            return
        }
        lastModernSurface = surface
        uiHandler.post { processInjection(surface) }
    }

    /**
     * Starts Challenge Test mode on whatever preview surface is currently active, independent of the normal
     * single-media slot. No-op if no camera preview has been seen yet (nothing to target).
     */
    fun startChallengeMode(items: List<MediaEngine.ChallengeItem>, startRole: String?) {
        val surface = activeSurface ?: run { logPipe("Challenge Test: no active preview surface yet"); return }
        val context = activeContext ?: return
        if (items.isEmpty()) return
        val settings = settingsProvider()
        logPipe("Challenge Test: starting with ${items.map { it.role }}")
        mediaEngine.playChallengeSet(context, items, surface, "Challenge", settings.isMirrored, settings.rotationAngle, startRole)
    }

    fun switchChallenge(role: String) = mediaEngine.switchChallenge(role)

    /** Leaves Challenge Test mode and, if a normal media is configured, resumes injecting it on the same surface. */
    fun stopChallengeMode() {
        val surface = activeSurface
        val context = activeContext
        val path = if (surface != null && context != null) settingsProvider().mediaPath else null
        if (surface != null && context != null && path != null) {
            logPipe("Challenge Test: stopping, resuming normal playback")
            // Force a fresh injection: the dedup check below only guards against redundant calls for the *normal*
            // path, and the player is currently a Challenge Test playlist even though isPlaying() is already true.
            lastInjectedSurfaceId = -1L
            injectToSurface(surface, context, path)
        } else {
            logPipe("Challenge Test: stopping, no media configured")
            stop()
        }
    }

    private fun processInjection(surface: Surface) {
        val context = contextProvider() ?: return
        val path = settingsProvider().mediaPath ?: return
        injectToSurface(surface, context, path)
    }

    fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            if (!surface.isValid) return
            
            val id = com.suryasukma.vcamtest.utils.SystemUtils.getSurfaceId(surface)
            val currentGen = surfaceManager.sessionGeneration
            val settings = settingsProvider()

            if (id == lastInjectedSurfaceId && mediaEngine.isPlaying) {
                lastInjectedGen = currentGen
                return
            }

            logPipe("Injection: ID=$id Gen=$currentGen")
            mediaEngine.stop()
            
            lastInjectedGen = currentGen
            lastInjectedSurfaceId = id
            mediaEngine.play(context, path, surface, "Engine", settings.isMirrored, settings.rotationAngle)
        }
    }

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()
}
