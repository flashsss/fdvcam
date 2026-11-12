package com.suryasukma.vcamtest.core.capture

import android.graphics.BitmapFactory
import android.media.Image
import androidx.core.graphics.scale

/**
 * Handles conversion of JPEG/Bitmap data into YUV_420_888 format
 * for injection into camera streams.
 */
class YuvFrameProcessor {
    private companion object {
        const val BLACK_Y: Byte = 16
        const val NEUTRAL_UV: Byte = 128.toByte()
    }

    private val nv21Buffer = ThreadLocal<ByteArray?>()
    private var cachedSource: ByteArray? = null
    private var cachedYuv: YuvData? = null

    private class YuvData(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
    )

    fun frameToNv21(frame: MediaCodecYuvDecoder.YuvFrame, targetWidth: Int, targetHeight: Int): ByteArray {
        val ySize = targetWidth * targetHeight
        val totalSize = ySize + ySize / 2
        // The caller copies the result right away on the same (camera callback) thread, so one buffer per thread
        // is enough and avoids allocating ~3 MB per preview frame.
        val nv21 = nv21Buffer.get()?.takeIf { it.size == totalSize } ?: ByteArray(totalSize).also { nv21Buffer.set(it) }

        val srcW = frame.width
        val srcH = frame.height

        if (srcW == targetWidth && srcH == targetHeight) {
            System.arraycopy(frame.yPlane, 0, nv21, 0, minOf(frame.yPlane.size, ySize))
            val chromaCount = minOf(frame.vPlane.size, frame.uPlane.size, ySize / 4)
            var uvIdx = ySize
            for (i in 0 until chromaCount) {
                if (uvIdx + 1 < totalSize) {
                    nv21[uvIdx] = frame.vPlane[i]
                    nv21[uvIdx + 1] = frame.uPlane[i]
                    uvIdx += 2
                }
            }
            return nv21
        }

        // Aspect-preserving resample (cover or contain, see FitConfig); samples outside the source become black.
        val scale = FitConfig.scaleFor(targetWidth, targetHeight, srcW, srcH)
        val xMap = FitConfig.axisMap(targetWidth, srcW, scale)
        val yMap = FitConfig.axisMap(targetHeight, srcH, scale)
        for (y in 0 until targetHeight) {
            val srcY = yMap[y]
            val dstYOffset = y * targetWidth
            if (srcY < 0) {
                java.util.Arrays.fill(nv21, dstYOffset, dstYOffset + targetWidth, BLACK_Y)
                continue
            }
            val srcYOffset = srcY * srcW
            for (x in 0 until targetWidth) {
                val sx = xMap[x]
                val srcIdx = srcYOffset + sx
                nv21[dstYOffset + x] = if (sx >= 0 && srcIdx < frame.yPlane.size) frame.yPlane[srcIdx] else BLACK_Y
            }
        }

        // Chroma (NV21: V first, U second), same mapping at half resolution.
        val targetChromaW = (targetWidth + 1) / 2
        val targetChromaH = (targetHeight + 1) / 2
        val srcChromaW = (srcW + 1) / 2
        val srcChromaH = (srcH + 1) / 2
        val cxMap = FitConfig.axisMap(targetChromaW, srcChromaW, scale)
        val cyMap = FitConfig.axisMap(targetChromaH, srcChromaH, scale)
        var uvDstIdx = ySize

        for (row in 0 until targetChromaH) {
            val srcRow = cyMap[row]
            for (col in 0 until targetChromaW) {
                val srcCol = cxMap[col]
                if (uvDstIdx + 1 >= totalSize) break
                if (srcRow < 0 || srcCol < 0) {
                    nv21[uvDstIdx] = NEUTRAL_UV
                    nv21[uvDstIdx + 1] = NEUTRAL_UV
                } else {
                    val srcIdx = srcRow * srcChromaW + srcCol
                    nv21[uvDstIdx] = if (srcIdx < frame.vPlane.size) frame.vPlane[srcIdx] else NEUTRAL_UV
                    nv21[uvDstIdx + 1] = if (srcIdx < frame.uPlane.size) frame.uPlane[srcIdx] else NEUTRAL_UV
                }
                uvDstIdx += 2
            }
        }

        return nv21
    }

    fun rgbToNv21(bitmap: android.graphics.Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap
            else android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val ySize = width * height
        val totalSize = ySize + ySize / 2
        val nv21 = ByteArray(totalSize)

        var yIdx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val yVal = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
                nv21[yIdx++] = yVal.toByte()
            }
        }

        var uvIdx = ySize
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val x = (col * 2).coerceAtMost(width - 1)
                val yPos = (row * 2).coerceAtMost(height - 1)
                val color = pixels[yPos * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (uvIdx + 1 < totalSize) {
                    val vVal = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255)
                    val uVal = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255)
                    nv21[uvIdx] = vVal.toByte()     // V
                    nv21[uvIdx + 1] = uVal.toByte() // U
                    uvIdx += 2
                }
            }
        }

        if (scaled != bitmap) scaled.recycle()
        return nv21
    }

    fun injectYuvFrame(image: Image, frame: MediaCodecYuvDecoder.YuvFrame) {
        val planes = image.planes
        if (planes.size < 3) return

        val dstW = image.width
        val dstH = image.height
        val srcW = frame.width
        val srcH = frame.height

        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return

        if (dstW == srcW && dstH == srcH) {
            writePlane(planes[0], frame.yPlane, dstW, dstH)
            val cw = (dstW + 1) / 2
            val ch = (dstH + 1) / 2
            writePlane(planes[1], frame.uPlane, cw, ch)
            writePlane(planes[2], frame.vPlane, cw, ch)
            return
        }

        val cw = (dstW + 1) / 2
        val ch = (dstH + 1) / 2
        val scw = (srcW + 1) / 2
        val sch = (srcH + 1) / 2
        val scale = FitConfig.scaleFor(dstW, dstH, srcW, srcH)
        resamplePlane(frame.yPlane, srcW, srcH, planes[0], dstW, dstH, scale, BLACK_Y)
        resamplePlane(frame.uPlane, scw, sch, planes[1], cw, ch, scale, NEUTRAL_UV)
        resamplePlane(frame.vPlane, scw, sch, planes[2], cw, ch, scale, NEUTRAL_UV)
    }

    /**
     * Nearest-neighbour scales [src] (srcW x srcH, tightly packed) into an image plane of dstW x dstH, preserving the
     * aspect ratio ([scale] comes from FitConfig). Positions outside the source get [fill].
     * Mappings are computed once; rows are written with bulk puts when the plane is contiguous.
     */
    private fun resamplePlane(
        src: ByteArray, srcW: Int, srcH: Int, plane: Image.Plane, dstW: Int, dstH: Int, scale: Float, fill: Byte,
    ) {
        val buf = plane.buffer?.duplicate() ?: return
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buf.position()
        val limit = buf.limit()
        val xMap = FitConfig.axisMap(dstW, srcW, scale)
        val yMap = FitConfig.axisMap(dstH, srcH, scale)
        val row = ByteArray(dstW)

        for (y in 0 until dstH) {
            val dstRowOffset = start + y * rowStride
            if (dstRowOffset >= limit) break
            val srcRow = yMap[y]
            val srcRowOffset = if (srcRow < 0) -1 else srcRow * srcW
            if (pixelStride == 1) {
                val n = minOf(dstW, limit - dstRowOffset)
                for (x in 0 until n) {
                    val sx = xMap[x]
                    val si = srcRowOffset + sx
                    row[x] = if (srcRowOffset < 0 || sx < 0 || si >= src.size) fill else src[si]
                }
                buf.position(dstRowOffset)
                buf.put(row, 0, n)
            } else {
                for (x in 0 until dstW) {
                    val di = dstRowOffset + x * pixelStride
                    if (di >= limit) break
                    val sx = xMap[x]
                    val si = srcRowOffset + sx
                    buf.put(di, if (srcRowOffset < 0 || sx < 0 || si >= src.size) fill else src[si])
                }
            }
        }
    }

    fun injectToImage(image: Image, jpeg: ByteArray) {
        val width = image.width
        val height = image.height
        
        val yuv = if ((cachedSource === jpeg) && (cachedYuv?.width == width) && (cachedYuv?.height == height)) {
            cachedYuv!!
        } else {
            process(jpeg, width, height).also {
                cachedSource = jpeg
                cachedYuv = it
            }
        }

        val planes = image.planes
        if (planes.size < 3) return

        writePlane(planes[0], yuv.y, width, height)
        writePlane(planes[1], yuv.u, (width + 1) / 2, (height + 1) / 2)
        writePlane(planes[2], yuv.v, (width + 1) / 2, (height + 1) / 2)
    }

    private fun process(jpeg: ByteArray, width: Int, height: Int): YuvData {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) 
            ?: throw IllegalStateException("Failed to decode frame")
            
        val bitmap = if (decoded.width == width && decoded.height == height) decoded 
            else decoded.scale(width, height)
            
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(((width + 1) / 2) * ((height + 1) / 2))

        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                y[row * width + col] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            }
        }

        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val x = (col * 2).coerceAtMost(width - 1)
                val yPos = (row * 2).coerceAtMost(height - 1)
                val color = pixels[yPos * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val idx = row * chromaW + col
                u[idx] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).toByte()
                v[idx] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).toByte()
            }
        }

        if (bitmap != decoded) bitmap.recycle()
        decoded.recycle()
        
        return YuvData(width, height, y, u, v)
    }

    private fun writePlane(plane: Image.Plane, values: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer?.duplicate() ?: return
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buffer.position()
        val limit = buffer.limit()

        for (row in 0 until height) {
            val dstRow = start + row * rowStride
            val srcRow = row * width
            if (dstRow >= limit || srcRow >= values.size) break
            if (pixelStride == 1) {
                val n = minOf(width, limit - dstRow, values.size - srcRow)
                buffer.position(dstRow)
                buffer.put(values, srcRow, n)
            } else {
                for (col in 0 until width) {
                    val dstIdx = dstRow + col * pixelStride
                    val srcIdx = srcRow + col
                    if (dstIdx < limit && srcIdx < values.size) buffer.put(dstIdx, values[srcIdx])
                }
            }
        }
    }
}
