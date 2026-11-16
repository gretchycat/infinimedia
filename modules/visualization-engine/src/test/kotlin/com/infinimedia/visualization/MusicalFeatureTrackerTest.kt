package com.infinimedia.visualization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalFeatureTrackerTest {
    @Test fun isolatedLowMidAndHighOnsetsAreClassifiedSeparately() {
        val lowTracker = MusicalFeatureTracker()
        lowTracker.update(0L, true, FloatArray(16) { 0.42f })
        val kickBands = FloatArray(16) { 0.42f }.also { for (i in 0..4) it[i] = 0.9f }
        val kick = lowTracker.update(33L, true, kickBands)
        assertTrue(kick.kickHit)
        assertFalse(kick.snareHit)
        assertFalse(kick.cymbalHit)
        assertTrue(kick.transient)
        assertTrue(kick.spectralFlux > 0.1f)

        val middleTracker = MusicalFeatureTracker()
        middleTracker.update(0L, true, FloatArray(16) { 0.42f })
        val snareBands = FloatArray(16) { 0.42f }.also { for (i in 5..12) it[i] = 0.9f }
        val snare = middleTracker.update(33L, true, snareBands)
        assertTrue(snare.snareHit)
        assertFalse(snare.kickHit)
        assertFalse(snare.cymbalHit)

        val highTracker = MusicalFeatureTracker()
        highTracker.update(0L, true, FloatArray(16) { 0.42f })
        val cymbalBands = FloatArray(16) { 0.42f }.also { for (i in 13..15) it[i] = 0.95f }
        val cymbal = highTracker.update(33L, true, cymbalBands)
        assertTrue(cymbal.cymbalHit)
        assertFalse(cymbal.kickHit)
        assertFalse(cymbal.snareHit)
        assertFalse(cymbal.beatPulse)
    }

    @Test fun aSustainedLoudNoteDoesNotKeepTriggeringHits() {
        val tracker = MusicalFeatureTracker()
        tracker.update(0L, true, FloatArray(16) { 0.35f })
        val sustained = FloatArray(16) { 0.35f }.also { for (i in 0..4) it[i] = 0.9f }
        assertTrue(tracker.update(33L, true, sustained).kickHit)
        repeat(12) { step ->
            val features = tracker.update(66L + step * 33L, true, sustained)
            assertFalse("sustained band level retriggered at step $step", features.kickHit)
        }
    }

    @Test fun repeatedBassOnsetsProduceStableTempoEstimate() {
        val tracker = MusicalFeatureTracker()
        val quiet = FloatArray(16) { 0.35f }
        tracker.update(0L, true, quiet)
        fun hit(timeMs: Long): MusicalFeatureTracker.Features {
            tracker.update(timeMs - 20L, true, quiet)
            val kick = quiet.copyOf().also { for (i in 0..4) it[i] = 0.9f }
            return tracker.update(timeMs, true, kick)
        }
        assertTrue(hit(100L).beatPulse)
        assertTrue(hit(600L).beatPulse)
        val third = hit(1100L)
        assertTrue(third.beatPulse)
        assertEquals(120f, third.tempoBpm, 0.5f)
        assertTrue(third.tempoConfidence >= 0.45f)
    }

    @Test fun seekBackClearsPreviousOnsetAndTempoHistory() {
        val tracker = MusicalFeatureTracker()
        tracker.update(0L, true, FloatArray(16) { 0.4f })
        val kick = FloatArray(16) { 0.4f }.also { for (i in 0..4) it[i] = 0.95f }
        tracker.update(200L, true, kick)
        tracker.update(700L, true, FloatArray(16) { 0.4f })
        val afterSeek = tracker.update(100L, true, kick)
        assertFalse(afterSeek.kickHit)
        assertEquals(0f, afterSeek.tempoConfidence, 0f)
    }
}
