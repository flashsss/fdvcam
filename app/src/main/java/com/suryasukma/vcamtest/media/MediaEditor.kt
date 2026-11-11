package com.suryasukma.vcamtest.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.suryasukma.vcamtest.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Bakes an [EditSpec] into a new mp4. Both entry points must be called from the main thread. */
@OptIn(UnstableApi::class)
object MediaEditor {
    private const val MAX_IMAGE_SIDE = 2560
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun editVideo(ctx: Context, source: File, spec: EditSpec, output: File, onDone: (Boolean) -> Unit) {
        output.delete()
        val effects = mutableListOf<Effect>()
        if (spec.flipH || spec.flipV) {
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(if (spec.flipH) -1f else 1f, if (spec.flipV) -1f else 1f)
                .build()
        }
        if (spec.rotation % 360 != 0) {
            // Media3 rotates counter-clockwise; the editor UI is clockwise.
            effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(((360 - spec.rotation % 360) % 360).toFloat())
                .build()
        }
        if (spec.hasCrop) {
            // Crop(left, right, bottom, top) in NDC [-1, 1]; y axis points up.
            effects += Crop(
                spec.cropLeft * 2f - 1f,
                spec.cropRight * 2f - 1f,
                1f - spec.cropBottom * 2f,
                1f - spec.cropTop * 2f,
            )
        }
        if (spec.brightness != 0f) effects += Brightness(spec.brightness)
        if (spec.contrast != 0f) effects += Contrast(spec.contrast)
        if (spec.saturation != 0f) effects += HslAdjustment.Builder().adjustSaturation(spec.saturation).build()

        val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(source)).apply {
            if (spec.hasTrim) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.trimStartMs)
                        .apply { if (spec.trimEndMs >= 0) setEndPositionMs(spec.trimEndMs) }
                        .build()
                )
            }
        }.build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        val transformer = Transformer.Builder(ctx).build()
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = onDone(true)
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) = onDone(false)
        })
        try {
            transformer.start(edited, output.absolutePath)
        } catch (_: Exception) {
            onDone(false)
        }
    }

    fun editImage(ctx: Context, source: File, spec: EditSpec, output: File, onDone: (Boolean) -> Unit) {
        worker.execute {
            val tmpImage = File(ctx.cacheDir, "edit_image.jpg")
            val ok = try {
                val bmp = decodeSampled(source.absolutePath, MAX_IMAGE_SIDE)
                val result = BitmapOps.apply(bmp, spec)
                FileOutputStream(tmpImage).use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                true
            } catch (_: Throwable) {
                false
            }
            main.post {
                if (!ok) {
                    onDone(false)
                } else {
                    MediaConverter.convertImageToMp4(ctx, Uri.fromFile(tmpImage), output) { success ->
                        tmpImage.delete()
                        onDone(success)
                    }
                }
            }
        }
    }

    fun decodeSampled(path: String, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: throw IllegalStateException("decode failed")
        return applyExifOrientation(path, bmp)
    }

    private fun applyExifOrientation(path: String, bmp: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
