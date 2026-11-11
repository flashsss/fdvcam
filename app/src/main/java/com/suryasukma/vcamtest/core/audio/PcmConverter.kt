package com.suryasukma.vcamtest.core.audio

import kotlin.math.min

/**
 * Converts a stream of decoded 16-bit little-endian PCM chunks (any sample rate / channel count) into the
 * rate / channel layout that an AudioRecord consumer expects: linear resampling plus channel down/up-mix.
 *
 * Pure Kotlin so it can be unit tested. [read] is the only entry point used while recording.
 */
class PcmConverter(
    private val nextChunk: () -> ByteArray?,
    private val sourceRate: () -> Int,
    private val sourceChannels: () -> Int,
) {
    private var srcShorts = ShortArray(0)   // interleaved, [sourceChannels] shorts per frame
    private var srcFrames = 0               // valid frames in srcShorts
    private var srcPos = 0.0                // fractional read position, in source frames
    private var pendingSkip = 0             // source frames to discard from the next chunk

    @Synchronized
    fun reset() {
        srcFrames = 0
        srcPos = 0.0
        pendingSkip = 0
    }

    /** Appends the next chunk to [srcShorts]. Returns false if no chunk is available. */
    private fun pullChunk(inCh: Int): Boolean {
        val chunk = nextChunk() ?: return false
        var frames = (chunk.size / 2) / inCh
        var firstFrame = 0
        if (pendingSkip > 0) {
            val skip = min(pendingSkip, frames)
            pendingSkip -= skip
            firstFrame = skip
            frames -= skip
        }
        if (frames <= 0) return true
        val need = (srcFrames + frames) * inCh
        if (srcShorts.size < need) srcShorts = srcShorts.copyOf(maxOf(need, srcShorts.size * 2))
        var w = srcFrames * inCh
        var r = firstFrame * inCh * 2
        repeat(frames * inCh) {
            srcShorts[w++] = ((chunk[r + 1].toInt() shl 8) or (chunk[r].toInt() and 0xFF)).toShort()
            r += 2
        }
        srcFrames += frames
        return true
    }

    private fun frameSample(frame: Int, c: Int, outCh: Int, inCh: Int): Float {
        val base = frame * inCh
        return when {
            outCh == inCh -> srcShorts[base + c].toFloat()
            outCh == 1 -> {
                var sum = 0f
                for (k in 0 until inCh) sum += srcShorts[base + k]
                sum / inCh
            }
            else -> srcShorts[base + (c % inCh)].toFloat()
        }
    }

    /**
     * Fills [target] with [lengthBytes] of 16-bit little-endian PCM at [targetRate] Hz / [targetChannels] channels.
     * Returns false (target untouched) when no audio is available, so the real microphone keeps working.
     * A partial underrun is padded with silence.
     */
    @Synchronized
    fun read(target: ByteArray, offset: Int, lengthBytes: Int, targetRate: Int, targetChannels: Int): Boolean {
        val outCh = targetChannels.coerceAtLeast(1)
        val outRate = targetRate.coerceAtLeast(4000)
        val outFrames = lengthBytes / (2 * outCh)
        if (outFrames <= 0) return false
        val inCh = sourceChannels().coerceAtLeast(1)
        val step = sourceRate().coerceAtLeast(1).toDouble() / outRate

        var produced = 0
        var o = offset
        while (produced < outFrames) {
            val i0 = srcPos.toInt()
            while (srcFrames < i0 + 2) { if (!pullChunk(inCh)) break }
            if (srcFrames < i0 + 2) break // underrun
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until outCh) {
                val v0 = frameSample(i0, c, outCh, inCh)
                val v1 = frameSample(i0 + 1, c, outCh, inCh)
                val v = (v0 + (v1 - v0) * frac).toInt().coerceIn(-32768, 32767)
                target[o++] = (v and 0xFF).toByte()
                target[o++] = ((v shr 8) and 0xFF).toByte()
            }
            srcPos += step
            produced++
        }

        // Drop frames that were fully consumed.
        val consumed = srcPos.toInt()
        if (consumed > 0) {
            if (consumed >= srcFrames) {
                pendingSkip += consumed - srcFrames
                srcFrames = 0
            } else {
                System.arraycopy(srcShorts, consumed * inCh, srcShorts, 0, (srcFrames - consumed) * inCh)
                srcFrames -= consumed
            }
            srcPos -= consumed
        }

        if (produced == 0) return false
        if (o < offset + lengthBytes) target.fill(0, o, offset + lengthBytes)
        return true
    }
}
