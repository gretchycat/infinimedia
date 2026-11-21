package com.infinifolder.viewer

import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxGraphValidator
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxPipelineTest {
    @Test fun randomReplacementChangesOneSlotAndEveryGeneratedGraphValidates() {
        val pipeline = RandomFxPipeline(Random(991))
        repeat(32) {
            val before = pipeline.currentEffects()
            val change = pipeline.replaceOneRandomStage()
            val after = pipeline.currentEffects()
            assertEquals(before.size, after.size)
            assertNotEquals(change.previous, change.replacement)
            assertEquals(change.replacement, after[change.slot])
            assertTrue(before.indices.filter { it != change.slot }.all { before[it] == after[it] })
            FxGraphValidator.validate(pipeline.graph(), BuiltinFx.byId)
        }
    }

    @Test fun existingAnalyzerLevelsMapToNormalizedFxMetadata() {
        val metadata = FxAudioMetadataAdapter.snapshot(
            bands = FloatArray(16) { it / 15f },
            vu = floatArrayOf(.3f, .4f, .8f, .6f),
            timestampNanos = 123L,
        )
        assertEquals(123L, metadata.timestampNanos)
        assertEquals(kotlin.math.sqrt(.125f), metadata.rms, .0001f)
        assertEquals(.8f, metadata.peak, .0001f)
        assertEquals((0f + 1f / 15f + 2f / 15f + 3f / 15f) / 4f, metadata.lowEnergy, .0001f)
        assertEquals(((4f + 5f + 6f + 7f + 8f + 9f) / 15f) / 6f, metadata.midEnergy, .0001f)
        assertEquals(12.5f / 15f, metadata.highEnergy, .0001f)
        assertTrue(metadata.onsetEvents.isEmpty())
    }
}
