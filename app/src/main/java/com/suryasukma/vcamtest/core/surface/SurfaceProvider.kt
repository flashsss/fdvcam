package com.suryasukma.vcamtest.core.surface

import android.graphics.SurfaceTexture
import android.view.Surface
import com.suryasukma.vcamtest.data.Constants.DUMMY_SURFACE_TEXTURE_ID

/**
 * Provides and manages dummy surfaces used for diverting original camera output.
 */
class SurfaceProvider(private val logAction: (String) -> Unit) {
    private var dummyST: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private fun log(msg: String) = logAction("[SURFACE-PROVIDER] $msg")

    @Synchronized
    fun getDummySurface(): Surface {
        if ((dummySurface == null) || !dummySurface!!.isValid) {
            log("Creating new dummy surface")
            val st = TextureDrain.create() ?: SurfaceTexture(DUMMY_SURFACE_TEXTURE_ID).apply {
                // EGL unavailable: best effort, frames cannot be drained.
                setOnFrameAvailableListener {
                    try { updateTexImage() } catch (_: Exception) {}
                }
                try { detachFromGLContext() } catch (_: Throwable) {}
            }
            dummySurface?.release()
            TextureDrain.release(dummyST)
            dummyST = st
            dummySurface = Surface(st)
        }
        return dummySurface!!
    }

    @Synchronized
    fun release() {
        dummySurface?.release()
        TextureDrain.release(dummyST)
        dummySurface = null
        dummyST = null
    }
}
