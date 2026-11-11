package com.suryasukma.vcamtest.core.capture

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.core.net.toUri

/**
 * MediaCodec-based video decoder that outputs raw YUV_420_888 frames directly, avoiding CPU-heavy Bitmap and JPEG
 * conversion loops.
 *
 * Designed to be light inside a host app process:
 *  - frame buffers are pooled (no per-frame allocation),
 *  - playback is paced by the video's own timestamps,
 *  - decoding pauses while nobody has read a frame for [IDLE_AFTER_MS].
 */
class MediaCodecYuvDecoder(
    private val context: Context,
    private val mediaPath: String,
    private val logAction: (String) -> Unit
) {
    class YuvFrame(
        val width: Int,
        val height: Int,
        val yPlane: ByteArray,
        val uPlane: ByteArray,
        val vPlane: ByteArray,
        val timestampNs: Long
    )

    private class Buffers(val width: Int, val height: Int) {
        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(u.size)
    }

    @Volatile
    private var isRunning = false
    private var decodeThread: Thread? = null

    @Volatile private var latest: YuvFrame? = null
    @Volatile private var lastReadMs = 0L

    /** Latest decoded frame. Reading it tells the decoder that somebody is still consuming frames. */
    val latestFrame: YuvFrame?
        get() {
            lastReadMs = SystemClock.elapsedRealtime()
            return latest
        }

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    // A few rotating buffers: a consumer may still be copying frame N while frame N+1 is being written.
    private val pool = arrayOfNulls<Buffers>(POOL_SIZE)
    private var poolIndex = 0

    fun start() {
        if (isRunning) return
        isRunning = true
        lastReadMs = SystemClock.elapsedRealtime()
        decodeThread = Thread({ decodeLoop() }, "FDVCam-YuvDecoder").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        decodeThread?.interrupt()
        try {
            decodeThread?.join(150)
        } catch (_: InterruptedException) {}
        decodeThread = null
        latest = null
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, mediaPath.toUri(), null)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                logAction("[YUV-DECODER] No video track found in $mediaPath")
                return
            }

            extractor.selectTrack(videoTrackIndex)
            videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L

            // Wall-clock <-> presentation-time mapping used for pacing.
            var baseWallNs = 0L
            var basePtsUs = 0L
            var lastPtsUs = Long.MIN_VALUE
            var needRebase = true

            while (isRunning) {
                // Nobody is reading frames: stop burning CPU/battery until somebody does.
                if (SystemClock.elapsedRealtime() - lastReadMs > IDLE_AFTER_MS) {
                    Thread.sleep(IDLE_POLL_MS)
                    needRebase = true
                    continue
                }

                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        var sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            sampleSize = extractor.readSampleData(inputBuffer, 0)
                        }

                        if (sampleSize > 0) {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val ptsUs = bufferInfo.presentationTimeUs
                    // The clip looped (timestamps went backwards) or decoding resumed: restart the timeline.
                    if (needRebase || ptsUs < lastPtsUs - 100_000L) {
                        baseWallNs = System.nanoTime()
                        basePtsUs = ptsUs
                        needRebase = false
                    }
                    lastPtsUs = ptsUs

                    val dueNs = baseWallNs + (ptsUs - basePtsUs) * 1000L
                    val waitMs = (dueNs - System.nanoTime()) / 1_000_000L
                    if (waitMs > 0) Thread.sleep(minOf(waitMs, MAX_FRAME_WAIT_MS))

                    val image: Image? = codec.getOutputImage(outIndex)
                    if (image != null) {
                        try {
                            latest = extractYuvFrame(image)
                        } finally {
                            image.close()
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            logAction("[YUV-DECODER] Decoder error: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun nextBuffers(width: Int, height: Int): Buffers {
        poolIndex = (poolIndex + 1) % POOL_SIZE
        val existing = pool[poolIndex]
        if (existing != null && existing.width == width && existing.height == height) return existing
        return Buffers(width, height).also { pool[poolIndex] = it }
    }

    private fun extractYuvFrame(image: Image): YuvFrame {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2

        val buffers = nextBuffers(width, height)
        copyPlane(planes[0], width, height, buffers.y)
        copyPlane(planes[1], cw, ch, buffers.u)
        copyPlane(planes[2], cw, ch, buffers.v)
        return YuvFrame(width, height, buffers.y, buffers.u, buffers.v, System.nanoTime())
    }

    /** Copies one image plane into a tightly packed array, using bulk row copies whenever the layout allows. */
    private fun copyPlane(plane: Image.Plane, w: Int, h: Int, dst: ByteArray) {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buf.position()

        if (pixelStride == 1) {
            if (rowStride == w) {
                buf.get(dst, 0, minOf(dst.size, buf.remaining()))
            } else {
                for (row in 0 until h) {
                    buf.position(start + row * rowStride)
                    val n = minOf(w, buf.remaining())
                    if (n <= 0) break
                    buf.get(dst, row * w, n)
                }
            }
        } else {
            for (row in 0 until h) {
                val rowStart = start + row * rowStride
                val dstBase = row * w
                for (col in 0 until w) {
                    val idx = rowStart + col * pixelStride
                    if (idx >= buf.limit()) return
                    dst[dstBase + col] = buf.get(idx)
                }
            }
        }
    }

    private companion object {
        const val POOL_SIZE = 3
        const val IDLE_AFTER_MS = 1500L
        const val IDLE_POLL_MS = 40L
        const val MAX_FRAME_WAIT_MS = 200L
    }
}
