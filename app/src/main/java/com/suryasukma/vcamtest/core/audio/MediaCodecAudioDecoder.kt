package com.suryasukma.vcamtest.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Hardware/framework-based audio decoder that extracts PCM 16-bit audio from the video source,
 * providing streaming audio samples for AudioRecord replacement (aligned with CamSwap).
 */
class MediaCodecAudioDecoder(
    private val context: Context,
    private val mediaPath: String,
    private val logAction: (String) -> Unit
) {
    @Volatile
    private var isRunning = false
    private var decodeThread: Thread? = null

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    var sampleRate = 44100
        private set

    @Volatile
    var channelCount = 2
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        decodeThread = Thread({ decodeLoop() }, "FDVCam-AudioDecoder").apply {
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
        pcmQueue.clear()
        converter.reset()
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, mediaPath.toUri(), null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                logAction("[AUDIO-DECODER] No audio track found in $mediaPath")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            sampleRate = audioFormat.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            channelCount = audioFormat.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L

            while (isRunning) {
                if (pcmQueue.size > 50) {
                    Thread.sleep(20)
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
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmQueue.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            logAction("[AUDIO-DECODER] Error: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private val converter = PcmConverter({ pcmQueue.poll() }, { sampleRate }, { channelCount })

    private fun readPcm16(target: ByteArray, offset: Int, lengthBytes: Int, targetRate: Int, targetChannels: Int): Boolean =
        converter.read(target, offset, lengthBytes, targetRate, targetChannels)

    /** Reads decoded audio as 16-bit little-endian bytes (see [readPcm16]). */
    fun readBytes(target: ByteArray, offset: Int, length: Int, targetRate: Int, targetChannels: Int): Boolean =
        readPcm16(target, offset, length, targetRate, targetChannels)

    /** Reads decoded audio as 16-bit shorts. */
    fun readShorts(target: ShortArray, offset: Int, length: Int, targetRate: Int, targetChannels: Int): Boolean {
        val tempBytes = ByteArray(length * 2)
        if (!readPcm16(tempBytes, 0, tempBytes.size, targetRate, targetChannels)) return false
        for (i in 0 until length) {
            val low = tempBytes[i * 2].toInt() and 0xFF
            val high = tempBytes[i * 2 + 1].toInt()
            target[offset + i] = ((high shl 8) or low).toShort()
        }
        return true
    }

    /** Reads decoded audio directly into a ByteBuffer (position is left unchanged, like AudioRecord does). */
    fun readByteBuffer(target: ByteBuffer, length: Int, targetRate: Int, targetChannels: Int): Boolean {
        val n = length.coerceAtMost(target.remaining())
        if (n <= 0) return false
        val tempBytes = ByteArray(n)
        if (!readPcm16(tempBytes, 0, n, targetRate, targetChannels)) return false
        val currentPos = target.position()
        target.put(tempBytes, 0, n)
        target.position(currentPos)
        return true
    }
}
