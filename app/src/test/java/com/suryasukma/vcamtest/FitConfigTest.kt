package com.suryasukma.vcamtest

import com.suryasukma.vcamtest.core.capture.FitConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitConfigTest {
    @After fun reset() { FitConfig.contain = false }

    @Test fun fillCropsTheOverflowAndNeverLeavesBars() {
        FitConfig.contain = false
        // 200x100 source into a 100x100 target: scale 1, the middle 100 columns are kept.
        val scale = FitConfig.scaleFor(100, 100, 200, 100)
        assertEquals(1f, scale, 0.001f)
        val xMap = FitConfig.axisMap(100, 200, scale)
        val yMap = FitConfig.axisMap(100, 100, scale)
        assertEquals(50, xMap.first())
        assertEquals(149, xMap.last())
        assertTrue(yMap.all { it >= 0 })
        assertEquals(0, yMap.first())
        assertEquals(99, yMap.last())
    }

    @Test fun containLetterboxesInsteadOfCropping() {
        FitConfig.contain = true
        val scale = FitConfig.scaleFor(100, 100, 200, 100)
        assertEquals(0.5f, scale, 0.001f)
        val xMap = FitConfig.axisMap(100, 200, scale)
        val yMap = FitConfig.axisMap(100, 100, scale)
        assertTrue(xMap.all { it in 0..199 })           // the full width is visible
        assertEquals(-1, yMap.first())                  // top bar
        assertEquals(-1, yMap.last())                   // bottom bar
        assertTrue(yMap[50] in 0..99)                   // content in the middle
    }

    @Test fun sameAspectIsAnIdentityMapping() {
        for (contain in listOf(false, true)) {
            FitConfig.contain = contain
            val scale = FitConfig.scaleFor(80, 60, 80, 60)
            val xMap = FitConfig.axisMap(80, 80, scale)
            assertEquals((0 until 80).toList(), xMap.toList())
        }
    }
}
