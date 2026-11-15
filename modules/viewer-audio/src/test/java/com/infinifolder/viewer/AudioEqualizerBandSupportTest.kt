package com.infinifolder.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEqualizerBandSupportTest {
    @Test
    fun acceptsValidBandWithinReportedFrequencyRange() {
        assertTrue(isEqualizerBandSupported(1_000_000, intArrayOf(500_000, 2_000_000)))
    }

    @Test
    fun rejectsMissingOrInvalidFrequencyRanges() {
        assertFalse(isEqualizerBandSupported(1_000_000, intArrayOf()))
        assertFalse(isEqualizerBandSupported(1_000_000, intArrayOf(2_000_000, 500_000)))
        assertFalse(isEqualizerBandSupported(1_000_000, intArrayOf(1_100_000, 2_000_000)))
        assertFalse(isEqualizerBandSupported(0, intArrayOf(0, 2_000_000)))
    }

    @Test
    fun convertsAndroidMilliHertzBandCentersToHertz() {
        assertEquals(60, equalizerCenterFrequencyHz(60_000))
        assertEquals(16_000, equalizerCenterFrequencyHz(16_000_000))
        assertEquals(0, equalizerCenterFrequencyHz(-1))
    }

    @Test
    fun mapsAvailableBandsToFixedWinampSlotsAndInterpolatesLockedOnes() {
        val slots = mapToWinampEqualizerSlots(
            listOf(
                AudioEqualizerBand(centerFrequencyHz = 60, levelMillibels = (-600).toShort(), supported = true),
                AudioEqualizerBand(centerFrequencyHz = 600, levelMillibels = 600.toShort(), supported = true),
            ),
        )

        assertEquals(10, slots.size)
        assertEquals(0, slots[0].effectBandIndex)
        assertEquals(1, slots[3].effectBandIndex)
        assertTrue(slots[0].supported)
        assertTrue(slots[3].supported)
        assertFalse(slots[1].supported)
        assertEquals(0, slots[1].levelMillibels.toInt())
        assertEquals(0, slots[2].levelMillibels.toInt())
        assertEquals(600, slots[9].levelMillibels.toInt())
    }

    @Test
    fun keepsUnsupportedSlidersCenteredWhenNoBandsAreAvailable() {
        val slots = mapToWinampEqualizerSlots(emptyList())

        assertEquals(10, slots.size)
        assertTrue(slots.all { !it.supported && it.levelMillibels == 0.toShort() })
    }

    @Test
    fun persistsAndInterpolatesTheCurveOnALogarithmicFrequencyAxis() {
        val curve = shortArrayOf((-1_200).toShort(), 1_200)
        assertEquals((-1_200).toShort(), interpolateEqualizerCurve(100, intArrayOf(100, 10_000), curve))
        assertEquals(0.toShort(), interpolateEqualizerCurve(1_000, intArrayOf(100, 10_000), curve))
        assertEquals(1_200.toShort(), interpolateEqualizerCurve(10_000, intArrayOf(100, 10_000), curve))
    }

    @Test
    fun upgradesOldNativeSizedPreferenceCurvesToTenPersistentSlots() {
        val normalized = normalizePersistedEqualizerLevels(shortArrayOf((-900).toShort(), 0, 900))

        assertEquals(10, normalized.size)
        assertEquals((-900).toShort(), normalized.first())
        assertEquals(900.toShort(), normalized.last())
        assertEquals(normalized.toList(), normalizePersistedEqualizerLevels(normalized).toList())
    }

    @Test
    fun removesOnlyBakedThumbPixelsAndLeavesSkinRailPixelsIntact() {
        val width = 25
        val height = 100
        val source = IntArray(width * height) { 0xff777777.toInt() }
        for (y in 60..67) for (x in 6..18) source[y * width + x] = 0xffeeeeee.toInt()

        val result = eraseBakedEqualizerThumbs(width, height, source, listOf(12))

        assertEquals(0xffeeeeee.toInt(), source[63 * width + 12])
        assertEquals(0xff777777.toInt(), result[63 * width + 12])
        assertEquals(source[59 * width + 5], result[59 * width + 5])
        assertEquals(source[63 * width + 3], result[63 * width + 3])
    }
}
