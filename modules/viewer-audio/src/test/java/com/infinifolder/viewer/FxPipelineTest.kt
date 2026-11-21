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
            val graph = pipeline.graph()
            FxGraphValidator.validate(graph, BuiltinFx.byId)
            assertEquals("generator:lightning-spectrum", graph.nodes.single { it.id == "lightning_spectrum" }.effectId)
            assertTrue(graph.connections.any { it.fromNodeId == "lightning_spectrum" && it.inputPort == "overlay" })
        }
    }

    @Test fun lightningSpectrumEditorParametersReachTheGeneratorNode() {
        val pipeline = RandomFxPipeline(Random(4))
        pipeline.updateLightningSpectrumStyle(LightningSpectrumStyle(gain = 3.4f, maxHeight = .72f, barGap = .08f, glow = 1.2f))
        val node = pipeline.graph().nodes.single { it.id == "lightning_spectrum" }
        assertEquals(3.4f, (node.parameters.getValue("gain") as org.infiniforge.fx.api.FxParameterValue.FloatValue).value)
        assertEquals(.72f, (node.parameters.getValue("maxHeight") as org.infiniforge.fx.api.FxParameterValue.FloatValue).value)
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
