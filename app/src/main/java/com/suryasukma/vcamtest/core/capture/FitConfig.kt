package com.suryasukma.vcamtest.core.capture

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How media is scaled into the size an app asks for. Applied identically to preview (YUV/NV21) and to photos (JPEG),
 * so a preview and the photo taken from it always look the same.
 *  - fill    (default): cover the target and crop the overflow, aspect ratio preserved
 *  - contain: fit the whole media inside the target, black bars, aspect ratio preserved
 */
object FitConfig {
    @Volatile var contain = false

    fun scaleFor(dstW: Int, dstH: Int, srcW: Int, srcH: Int): Float {
        val sx = dstW.toFloat() / srcW
        val sy = dstH.toFloat() / srcH
        return if (contain) min(sx, sy) else max(sx, sy)
    }

    /** For every destination index along one axis: the source index to sample, or -1 when it falls outside the source. */
    fun axisMap(dstLen: Int, srcLen: Int, scale: Float): IntArray = IntArray(dstLen) {
        val i = floor((it + 0.5f - dstLen / 2f) / scale + srcLen / 2f).toInt()
        if (i < 0 || i >= srcLen) -1 else i
    }
}
