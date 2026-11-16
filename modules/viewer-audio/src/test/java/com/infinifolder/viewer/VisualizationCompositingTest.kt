package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizationCompositingTest {
    @Test fun lightningHalosUseLinearFiveAndEightPercentAlphaCaps() {
        assertEquals(
            LIGHTNING_INNER_GLOW_MAX_ALPHA,
            lightningGlowAlpha(1f, LIGHTNING_INNER_GLOW_GAIN, LIGHTNING_INNER_GLOW_MAX_ALPHA),
            0.00001f,
        )
        assertEquals(
            LIGHTNING_OUTER_GLOW_MAX_ALPHA,
            lightningGlowAlpha(1f, LIGHTNING_OUTER_GLOW_GAIN, LIGHTNING_OUTER_GLOW_MAX_ALPHA),
            0.00001f,
        )
        assertEquals(
            0.025f,
            lightningGlowAlpha(0.05f, LIGHTNING_INNER_GLOW_GAIN, LIGHTNING_INNER_GLOW_MAX_ALPHA),
            0.00001f,
        )
        assertEquals(0f, lightningGlowAlpha(Float.NaN, 0.5f, 0.08f), 0f)
    }

    @Test fun lightningCoreSuppressesIsolatedPixelsAndKeepsConnectedSegments() {
        assertEquals(0f, lightningCoreAlpha(1f, 0f), 0f)
        assertEquals(0f, lightningCoreAlpha(1f / 255f, 0.4f), 0f)
        assertEquals(1f, lightningCoreAlpha(1f, 1f), 0.00001f)
        assertEquals(0f, lightningCoreAlpha(Float.NaN, 1f), 0f)
    }

    @Test fun gpuSimulationFieldsAndArtworkColorArePublishedAsOneFrame() {
        val frame = LightningFieldFrameSource(3, 2)
        val heat = FloatArray(6).also { it[4] = 1f; it[1] = 0.25f }
        val light = FloatArray(6).also { it[1] = 0.5f }
        val flame = FloatArray(6).also { it[1] = 0.125f }
        val density = FloatArray(6).also { it[1] = 0.0001f }
        val flash = FloatArray(6).also { it[4] = 1f; it[1] = 0.25f }
        val artworkColor = 0xff12ab34.toInt()

        frame.publish(heat, light, flame, density, flash, artworkColor)

        assertEquals(1L, frame.version)
        assertEquals(255.toByte(), frame.flashPixels[4])
        assertEquals(63.toByte(), frame.flashPixels[1])
        assertEquals(255.toByte(), frame.firePixels[4 * 4])
        assertEquals(127.toByte(), frame.firePixels[1 * 4])
        assertEquals(180.toByte(), frame.firePixels[1 * 4 + 1])
        assertEquals(90.toByte(), frame.firePixels[1 * 4 + 2])
        assertEquals(2.toByte(), frame.firePixels[1 * 4 + 3])
        assertEquals(artworkColor, frame.paletteColor)
    }

}
