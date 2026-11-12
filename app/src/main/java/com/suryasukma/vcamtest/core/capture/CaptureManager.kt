package com.suryasukma.vcamtest.core.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.suryasukma.vcamtest.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.suryasukma.vcamtest.data.Constants.DEFAULT_CAPTURE_WIDTH
import com.suryasukma.vcamtest.data.Constants.STREAM_FRAME_INTERVAL_MS
import java.util.concurrent.Executors

/**
 * Manages frame extraction for still captures and video streaming.
 */
class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val streamFrameIntervalMs = STREAM_FRAME_INTERVAL_MS

    @Volatile var isCapturing = false
        private set

    private data class CaptureKey(
        val path: String, val width: Int, val height: Int, val rotation: Int, val mirrored: Boolean,
        val contain: Boolean = FitConfig.contain,
    )

    // Keyed by everything that changes the output, so a small stream frame can never be served as a full-size photo.
    private val cacheLock = Any()
    private val captureCache = object : LinkedHashMap<CaptureKey, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CaptureKey, ByteArray>?) = size > 3
    }
    private val captureLock = Any()

    /**
     * Fingerprints of the JPEGs this module already handed to the app (takePicture callback, ImageReader buffer).
     * When the app later decodes those very bytes, the decode hook must NOT replace them with a freshly extracted frame:
     * the second image can have a different size than the one the app was told about (it is what broke sending photos).
     */
    private val delivered = ArrayDeque<Long>()

    private fun fingerprint(b: ByteArray): Long {
        var h = b.size.toLong()
        val n = minOf(b.size, 256)
        for (i in 0 until n) h = h * 31 + b[i]
        for (i in b.size - n until b.size) h = h * 31 + b[i]
        return h
    }

    private fun rememberDelivered(b: ByteArray) {
        val f = fingerprint(b)
        synchronized(delivered) {
            if (!delivered.contains(f)) {
                delivered.addLast(f)
                while (delivered.size > 8) delivered.removeFirst()
            }
        }
    }

    fun isDelivered(b: ByteArray?): Boolean {
        if (b == null || b.size < 512) return false
        val f = fingerprint(b)
        return synchronized(delivered) { delivered.contains(f) }
    }

    /**
     * One-shot token armed by a capture pulse and consumed by the first hook that actually injects the photo.
     * Generic decode/compress hooks (decodeStream, Bitmap#compress) only fire while the token is armed, so avatars,
     * stickers and thumbnails decoded around the same time are left alone.
     */
    private val injectionToken = java.util.concurrent.atomic.AtomicBoolean(false)
    fun hasInjectionToken(): Boolean = injectionToken.get()
    fun consumeInjectionToken(): Boolean = injectionToken.compareAndSet(true, false)

    private fun cacheGet(key: CaptureKey, maxSize: Int): ByteArray? =
        synchronized(cacheLock) { captureCache[key]?.takeIf { it.size <= maxSize } }

    private fun cachePut(key: CaptureKey, value: ByteArray) = synchronized(cacheLock) { captureCache[key] = value }
    private fun cacheClear() = synchronized(cacheLock) { captureCache.clear() }
    @Volatile private var cachedStreamFrame: ByteArray? = null
    
    private var lastCapturePulseTime = 0L
    private var captureTimeMs = 0
    
    private var streamKey: String? = null
    private var streamStartedAtMs = 0L
    private var lastStreamRequestAtMs = 0L
    @Volatile private var streamExtractionRunning = false

    private fun log(msg: String) = logAction("[CAPTURE] $msg")

    fun reset() {
        uiHandler.removeCallbacksAndMessages(null)
        isCapturing = false
        injectionToken.set(false)
        cacheClear()
        cachedStreamFrame = null
        streamKey = null
        streamExtractionRunning = false
        log("[*] Manager state RESET")
    }

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if ((now - lastCapturePulseTime) < 2000) return
        lastCapturePulseTime = now

        captureTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        log("[*] Pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        injectionToken.set(true)
        cacheClear()

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            injectionToken.set(false)
            uiHandler.postDelayed({ cacheClear() }, 5000)
        }, 3000)
    }

    private fun performExtraction(
        context: Context,
        path: String,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int,
        maxSizeBytes: Int,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        return try {
            setIgnoringHooks(true)
            val finalW = if (width > 0) width else liveFrame?.width ?: DEFAULT_CAPTURE_WIDTH
            val finalH = if (height > 0) height else liveFrame?.height ?: DEFAULT_CAPTURE_HEIGHT
            XCamCapture.createJpeg(
                context, path, 
                finalW, 
                finalH, 
                rotation, mirrored, timeMs, maxSizeBytes, logAction,
                liveFrame
            )
        } catch (e: Throwable) {
            log("Extraction failed: ${e.message}")
            null
        } finally {
            setIgnoringHooks(false)
        }
    }

    @JvmOverloads
    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        maxSizeBytes: Int,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        if (isIgnoringHooks() || path == null) return null
        val context = contextProvider() ?: return null

        val key = CaptureKey(path, width, height, rotationAngle, isMirrored)
        cacheGet(key, maxSizeBytes)?.let { rememberDelivered(it); return it }

        // Heavy work is serialized on its own lock (not the stream lock) and re-checks the cache once inside.
        return synchronized(captureLock) {
            cacheGet(key, maxSizeBytes)?.let { rememberDelivered(it); return@synchronized it }
            performExtraction(
                context, path, width, height, rotationAngle, isMirrored, captureTimeMs, maxSizeBytes, setIgnoringHooks, liveFrame
            )?.also {
                cachePut(key, it)
                rememberDelivered(it)
                log("[+] Capture extraction successful (liveFrame: ${liveFrame != null}, size: ${it.size})")
            }
        }
    }

    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit
    ): ByteArray? {
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null
        
        if (!actualPath.lowercase().endsWith(".mp4")) {
            return handleCapture(path, width, height, rotationAngle, isMirrored, Int.MAX_VALUE, isIgnoringHooks, setIgnoringHooks)
        }

        val now = SystemClock.elapsedRealtime()
        val key = "$actualPath|$width|$height|$rotationAngle|$isMirrored"
        
        synchronized(this) {
            if (streamKey != key) {
                streamKey = key
                cachedStreamFrame = null
                streamStartedAtMs = now
                lastStreamRequestAtMs = 0L
                streamExtractionRunning = false
            }

            if (!streamExtractionRunning && now - lastStreamRequestAtMs >= streamFrameIntervalMs) {
                streamExtractionRunning = true
                lastStreamRequestAtMs = now
                val frameTimeMs = (now - streamStartedAtMs).toInt().coerceAtLeast(0)
                
                streamExecutor.execute {
                    performExtraction(context, actualPath, width, height, rotationAngle, isMirrored, frameTimeMs, Int.MAX_VALUE, setIgnoringHooks)?.let {
                        cachedStreamFrame = it
                    }
                    synchronized(this) { streamExtractionRunning = false }
                }
            }
        }
        return cachedStreamFrame
    }
}
