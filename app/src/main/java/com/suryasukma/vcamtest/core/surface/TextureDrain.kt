package com.suryasukma.vcamtest.core.surface

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Creates SurfaceTextures whose frames are actually consumed.
 *
 * A dummy camera output that nobody consumes fills its BufferQueue after a few frames; the camera HAL then blocks on
 * dequeueBuffer, which shows up as a frozen preview or a camera that never closes properly. Here every texture is
 * attached to a real (tiny) EGL context on one shared thread, and updateTexImage() is called for each new frame so
 * buffers are returned immediately.
 *
 * One daemon thread and one 1x1 pbuffer context are shared by all textures for the life of the process.
 */
object TextureDrain {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initFailed = false

    @Synchronized
    private fun ensureThread(): Handler? {
        if (initFailed) return null
        handler?.let { return it }
        val t = HandlerThread("FDVCam-TextureDrain").apply { isDaemon = true; start() }
        val h = Handler(t.looper)
        var ok = false
        val latch = CountDownLatch(1)
        h.post {
            ok = initEgl()
            latch.countDown()
        }
        val finished = try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!finished || !ok) {
            initFailed = true
            t.quitSafely()
            return null
        }
        thread = t
        handler = h
        return h
    }

    private fun initEgl(): Boolean {
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return false

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false

            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return false
            EGL14.eglMakeCurrent(display, surface, surface, context)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns a SurfaceTexture whose frames are drained, or null if EGL is unavailable
     * (callers then fall back to a plain, undrained texture).
     */
    fun create(): SurfaceTexture? {
        val h = ensureThread() ?: return null
        var result: SurfaceTexture? = null
        val latch = CountDownLatch(1)
        h.post {
            try {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                val st = SurfaceTexture(tex[0])
                st.setOnFrameAvailableListener({ texture ->
                    try { texture.updateTexImage() } catch (_: Throwable) {}
                }, h)
                result = st
            } catch (_: Throwable) {
                result = null
            }
            latch.countDown()
        }
        try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return result
    }

    fun release(texture: SurfaceTexture?) {
        if (texture == null) return
        val h = handler
        if (h == null) {
            try { texture.release() } catch (_: Throwable) {}
            return
        }
        h.post { try { texture.release() } catch (_: Throwable) {} }
    }
}
