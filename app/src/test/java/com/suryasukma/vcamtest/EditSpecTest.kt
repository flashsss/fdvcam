package com.suryasukma.vcamtest

import com.suryasukma.vcamtest.media.EditSpec
import com.suryasukma.vcamtest.utils.RootShell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSpecTest {
    @Test fun defaultSpecIsIdentity() {
        assertTrue(EditSpec().isIdentity(includeTrim = true))
    }

    @Test fun rotationFlipCropColorAreEdits() {
        assertFalse(EditSpec(rotation = 90).isIdentity(true))
        assertFalse(EditSpec(flipH = true).isIdentity(true))
        assertFalse(EditSpec(flipV = true).isIdentity(true))
        assertFalse(EditSpec(cropLeft = 0.1f).isIdentity(true))
        assertFalse(EditSpec(brightness = 0.2f).isIdentity(true))
        assertFalse(EditSpec(saturation = -50f).isIdentity(true))
    }

    @Test fun fullRotationIsIdentity() {
        assertTrue(EditSpec(rotation = 360).isIdentity(true))
    }

    @Test fun trimAndSpeedOnlyCountForVideo() {
        val trimmed = EditSpec(trimStartMs = 500)
        assertFalse(trimmed.isIdentity(includeTrim = true))
        assertTrue(trimmed.isIdentity(includeTrim = false))
        val fast = EditSpec(speed = 2f)
        assertFalse(fast.isIdentity(includeTrim = true))
        assertTrue(fast.isIdentity(includeTrim = false))
    }

    @Test fun tinyCropIsIgnored() {
        assertTrue(EditSpec(cropLeft = 0.0005f, cropRight = 0.9995f).isIdentity(true))
    }

    @Test fun packageNameValidationBlocksShellInjection() {
        assertTrue(RootShell.isValidPackage("org.telegram.messenger"))
        assertTrue(RootShell.isValidPackage("com.example.app_2"))
        assertFalse(RootShell.isValidPackage("com.x; rm -rf /"))
        assertFalse(RootShell.isValidPackage("a b"))
        assertFalse(RootShell.isValidPackage(""))
        assertFalse(RootShell.isValidPackage("pkg$(id)"))
    }
}
