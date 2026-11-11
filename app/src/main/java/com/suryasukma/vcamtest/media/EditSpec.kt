package com.suryasukma.vcamtest.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Describes an edit applied to a source image/video.
 * Order of operations: flip -> rotate (clockwise) -> crop -> color adjustments.
 * Crop values are normalized (0..1) relative to the flipped+rotated frame.
 */
data class EditSpec(
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -100..100
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = -1L,    // -1 = until end
) {
    val hasGeometry get() = rotation % 360 != 0 || flipH || flipV
    val hasCrop get() = cropLeft > 0.001f || cropTop > 0.001f || cropRight < 0.999f || cropBottom < 0.999f
    val hasColor get() = brightness != 0f || contrast != 0f || saturation != 0f
    val hasTrim get() = trimStartMs > 0L || trimEndMs >= 0L

    fun isIdentity(includeTrim: Boolean): Boolean =
        !hasGeometry && !hasCrop && !hasColor && !(includeTrim && hasTrim)

    /** Brightness/contrast/saturation as a ColorMatrix (shared by preview and image export). */
    fun colorMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation((1f + saturation / 100f).coerceAtLeast(0f))
        val scale = 1f + contrast
        val translate = (0.5f * (1f - scale) + brightness) * 255f
        val adjust = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        cm.postConcat(adjust)
        return cm
    }

    fun colorFilter(): ColorMatrixColorFilter? =
        if (hasColor) ColorMatrixColorFilter(colorMatrix()) else null
}

object BitmapOps {
    fun transform(src: Bitmap, flipH: Boolean, flipV: Boolean, rotation: Int): Bitmap {
        if (!flipH && !flipV && rotation % 360 == 0) return src
        val m = Matrix().apply {
            if (flipH || flipV) postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
            if (rotation % 360 != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
        val x = (l * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (t * src.height).toInt().coerceIn(0, src.height - 1)
        val w = ((r - l) * src.width).toInt().coerceIn(1, src.width - x)
        val h = ((b - t) * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    fun adjustColors(src: Bitmap, spec: EditSpec): Bitmap {
        val filter = spec.colorFilter() ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
        return out
    }

    fun apply(src: Bitmap, spec: EditSpec): Bitmap {
        var bmp = transform(src, spec.flipH, spec.flipV, spec.rotation)
        if (spec.hasCrop) bmp = crop(bmp, spec.cropLeft, spec.cropTop, spec.cropRight, spec.cropBottom)
        return adjustColors(bmp, spec)
    }
}
