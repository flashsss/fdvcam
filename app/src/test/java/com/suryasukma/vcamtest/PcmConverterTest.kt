package com.suryasukma.vcamtest

import com.suryasukma.vcamtest.core.audio.PcmConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class PcmConverterTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun shorts(b: ByteArray) = IntArray(b.size / 2) { ((b[it * 2 + 1].toInt() shl 8) or (b[it * 2].toInt() and 0xFF)).toShort().toInt() }

    private fun converter(rate: Int, ch: Int, vararg chunks: ByteArray): PcmConverter {
        val q = ArrayDeque(chunks.toList())
        return PcmConverter({ q.poll() }, { rate }, { ch })
    }

    @Test fun sameFormatPassesSamplesThrough() {
        val c = converter(16000, 1, pcm(100, 200, 300, 400, 500, 600))
        val out = ByteArray(8) // 4 mono frames
        assertTrue(c.read(out, 0, 8, 16000, 1))
        assertArrayEquals(intArrayOf(100, 200, 300, 400), shorts(out))
    }

    @Test fun stereoToMonoAveragesChannels() {
        // frames: (100,300) (200,400) (0,0)
        val c = converter(16000, 2, pcm(100, 300, 200, 400, 0, 0))
        val out = ByteArray(4)
        assertTrue(c.read(out, 0, 4, 16000, 1))
        assertArrayEquals(intArrayOf(200, 300), shorts(out))
    }

    @Test fun monoToStereoDuplicatesChannel() {
        val c = converter(16000, 1, pcm(10, 20, 30, 40))
        val out = ByteArray(8) // 2 stereo frames
        assertTrue(c.read(out, 0, 8, 16000, 2))
        assertArrayEquals(intArrayOf(10, 10, 20, 20), shorts(out))
    }

    @Test fun downsamplingByTwoSkipsEverySecondFrame() {
        val c = converter(32000, 1, pcm(0, 1000, 2000, 3000, 4000, 5000, 6000))
        val out = ByteArray(6) // 3 output frames at 16 kHz
        assertTrue(c.read(out, 0, 6, 16000, 1))
        assertArrayEquals(intArrayOf(0, 2000, 4000), shorts(out))
    }

    @Test fun upsamplingInterpolatesLinearly() {
        val c = converter(8000, 1, pcm(0, 1000, 2000))
        val out = ByteArray(6) // 3 output frames at 16 kHz
        assertTrue(c.read(out, 0, 6, 16000, 1))
        assertArrayEquals(intArrayOf(0, 500, 1000), shorts(out))
    }

    @Test fun emptySourceLeavesTargetUntouched() {
        val c = converter(16000, 1)
        val out = ByteArray(8) { 7 }
        assertFalse(c.read(out, 0, 8, 16000, 1))
        assertTrue(out.all { it == 7.toByte() })
    }

    @Test fun partialUnderrunIsPaddedWithSilence() {
        val c = converter(16000, 1, pcm(1000, 2000, 3000))
        val out = ByteArray(10) { 9 } // asks for 5 frames, only 2 can be produced (needs i0+2 frames)
        assertTrue(c.read(out, 0, 10, 16000, 1))
        val s = shorts(out)
        assertEquals(1000, s[0])
        assertEquals(2000, s[1])
        assertEquals(0, s[4])
    }

    @Test fun continuesAcrossChunksWithoutGlitches() {
        val c = converter(16000, 1, pcm(1, 2, 3), pcm(4, 5, 6), pcm(7, 8, 9))
        val first = ByteArray(8)
        val second = ByteArray(8)
        assertTrue(c.read(first, 0, 8, 16000, 1))
        assertTrue(c.read(second, 0, 8, 16000, 1))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), shorts(first))
        assertArrayEquals(intArrayOf(5, 6, 7, 8), shorts(second))
    }
}
