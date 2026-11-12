package com.suryasukma.vcamtest.xposed

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.suryasukma.vcamtest.core.audio.MediaCodecAudioDecoder
import com.suryasukma.vcamtest.core.capture.CaptureManager
import com.suryasukma.vcamtest.core.capture.MediaCodecYuvDecoder
import com.suryasukma.vcamtest.core.capture.YuvFrameProcessor
import com.suryasukma.vcamtest.core.engine.MediaEngine
import com.suryasukma.vcamtest.core.engine.XCamEngine
import com.suryasukma.vcamtest.core.settings.SettingsManager
import com.suryasukma.vcamtest.core.surface.SurfaceManager
import com.suryasukma.vcamtest.core.surface.SurfaceProvider
import com.suryasukma.vcamtest.core.telemetry.PipelineTracker
import com.suryasukma.vcamtest.utils.Logger
import com.suryasukma.vcamtest.utils.SystemUtils
import com.suryasukma.vcamtest.utils.UIUtils
import com.suryasukma.vcamtest.data.Constants
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    @Volatile private var yuvDecoder: MediaCodecYuvDecoder? = null
    @Volatile private var audioDecoder: MediaCodecAudioDecoder? = null
    private val yuvLock = Any()
    private val audioLock = Any()
    
    private val injectors = XCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor()
    
    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
    ) { printLog(it) }
    
    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
    ) { printLog(it) }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

    val context: Context? get() = mContext
    @Volatile private var lastSettingsRefreshMs = 0L
    @Volatile private var refreshInFlight = false

    /** Current media (null = module off or no media). Settings are re-read in the background at most every 2 s. */
    val mediaPath: String?
        get() {
            val now = android.os.SystemClock.elapsedRealtime()
            val ctx = mContext
            if (ctx != null && !refreshInFlight && now - lastSettingsRefreshMs > 2000) {
                lastSettingsRefreshMs = now
                refreshInFlight = true
                Thread({
                    try {
                        val before = settings.mediaPath
                        settings.refreshSettings(ctx)
                        // Master switch turned off (or media removed) while the engine is playing: stop injecting.
                        if (before != null && settings.mediaPath == null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { stopEngine() }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        refreshInFlight = false
                    }
                }, "FDVCam-Settings").apply { isDaemon = true }.start()
            }
            return settings.mediaPath
        }
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        if (tr != null || (msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL"))) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")
    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")

    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }

    fun isCapturingState() = captureManager.isCapturing

    fun isDeliveredJpeg(bytes: ByteArray?) = captureManager.isDelivered(bytes)
    fun hasInjectionToken() = captureManager.hasInjectionToken()
    fun consumeInjectionToken() = captureManager.consumeInjectionToken()

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun getCurrentPosition(): Long = engine.getCurrentPosition()

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) = surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun isNonPreviewSurface(s: Surface?) = surfaceManager.isNonPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun recordPipelineNode(node: String) {
        PipelineTracker.recordNode(mContext, node)
    }

    @JvmOverloads
    fun reportPipelineCapture(width: Int = 0, height: Int = 0, thumbnail: ByteArray? = null) {
        PipelineTracker.reportCapture(mContext, width, height, thumbnail)
    }

    private fun getOrCreateYuvDecoder(ctx: Context, path: String): MediaCodecYuvDecoder {
        return yuvDecoder ?: synchronized(yuvLock) {
            yuvDecoder ?: MediaCodecYuvDecoder(ctx, path) { printLog(it) }.apply { start() }.also { yuvDecoder = it }
        }
    }

    private fun getOrCreateAudioDecoder(ctx: Context, path: String): MediaCodecAudioDecoder {
        return audioDecoder ?: synchronized(audioLock) {
            audioDecoder ?: MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }.also { audioDecoder = it }
        }
    }

    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int) {
        val path = settings.mediaPath
        val ctx = mContext
        if (path != null && ctx != null && path.lowercase().endsWith(".mp4")) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.latestFrame
            if (frame != null) {
                yuvProcessor.injectYuvFrame(image, frame)
                return
            }
        }

        val jpeg = handleStreamFrame(width, height) ?: return
        yuvProcessor.injectToImage(image, jpeg)
    }

    fun handleNv21Frame(width: Int, height: Int): ByteArray? {
        val path = settings.mediaPath ?: return null
        val ctx = mContext ?: return null

        if (path.lowercase().endsWith(".mp4")) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.latestFrame
            if (frame != null) {
                return yuvProcessor.frameToNv21(frame, width, height)
            }
        }

        val jpeg = handleStreamFrame(width, height) ?: return null
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val nv21 = yuvProcessor.rgbToNv21(bmp, width, height)
            bmp.recycle()
            nv21
        } catch (_: Throwable) {
            null
        }
    }

    /** Best-effort cleanup: every step runs even if another one fails, and nothing is rethrown into the host app. */
    fun stopEngine() {
        runCatching { synchronized(yuvLock) { yuvDecoder?.stop(); yuvDecoder = null } }
        runCatching { synchronized(audioLock) { audioDecoder?.stop(); audioDecoder = null } }
        runCatching { engine.stop() }
        runCatching { captureManager.reset() }
        runCatching { injectors.imageReaderHook.reset() }
    }
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)
    fun getDummySurface() = engine.getDummySurface()

    /** Starts the audio decoder ahead of the first AudioRecord#read so the audio thread never waits for it. */
    fun warmUpAudio() {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path)
    }

    fun injectAudioBytes(buffer: ByteArray, offset: Int, length: Int, sampleRate: Int, channels: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readBytes(buffer, offset, length, sampleRate, channels)
    }

    fun injectAudioShorts(buffer: ShortArray, offset: Int, length: Int, sampleRate: Int, channels: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readShorts(buffer, offset, length, sampleRate, channels)
    }

    fun injectAudioByteBuffer(buffer: java.nio.ByteBuffer, length: Int, sampleRate: Int, channels: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readByteBuffer(buffer, length, sampleRate, channels)
    }

    fun getImageReaderCaptureWidth(): Int = injectors.imageReaderHook.activeCaptureWidth
    fun getImageReaderCaptureHeight(): Int = injectors.imageReaderHook.activeCaptureHeight

    @JvmOverloads
    fun handleCapture(w: Int = 0, h: Int = 0, maxSize: Int = Int.MAX_VALUE): ByteArray? {
        val irW = injectors.imageReaderHook.activeCaptureWidth
        val irH = injectors.imageReaderHook.activeCaptureHeight
        val targetW = if (w > 0) w else if (irW > 0) irW else Constants.DEFAULT_CAPTURE_WIDTH
        val targetH = if (h > 0) h else if (irH > 0) irH else Constants.DEFAULT_CAPTURE_HEIGHT
        return captureManager.handleCapture(
            settings.mediaPath, targetW, targetH, settings.rotationAngle, settings.isMirrored, maxSize,
            { isIgnoringHooks() },
            { setIgnoringHooks(it) },
            yuvDecoder?.latestFrame
        )
    }

    fun handleStreamFrame(w: Int, h: Int) = captureManager.handleStreamFrame(
        settings.mediaPath, w, h, settings.rotationAngle, settings.isMirrored,
        { isIgnoringHooks() },
        { setIgnoringHooks(it) },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()
        if (param.packageName == "com.suryasukma.vcamtest") {
            hookManagerApp(param)
            return
        }
        
        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        PipelineTracker.reset()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.suryasukma.vcamtest.ui.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
    }

    /** Settings come from a ContentProvider (binder): never block the host app's startup on it. */
    private fun refreshSettingsAsync(ctx: Context) {
        Thread({ runCatching { settings.refreshSettings(ctx) } }, "FDVCam-Settings").apply { isDaemon = true }.start()
    }

    private fun hookContextInit() {
        val currentApp = SystemUtils.getCurrentApplication()
        if (currentApp != null && !isInitialized) {
            mContext = currentApp
            logInit("Context immediately resolved from ActivityThread: ${mContext?.packageName}")
            mContext?.let { refreshSettingsAsync(it) }
            isInitialized = true
        }

        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    // Prefer the application context so we never keep an Activity alive.
                    mContext = (chain.thisObject as? Context)?.let { it.applicationContext ?: it }
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { refreshSettingsAsync(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }
}
