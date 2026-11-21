package com.infinifolder.viewer

import org.infiniforge.fx.api.FxConnection
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxNode
import org.infiniforge.fx.api.FxParameterValue
import kotlin.random.Random

/** A temporary test policy: keep a two-stage image pipeline and replace one stage per media item. */
internal class RandomFxPipeline(private val random: Random = Random.Default) {
    private val availableEffects = listOf(
        "display:crt",
        "display:lcd",
        "distort:water-refraction",
        "distort:heat-ripple",
        "distort:rotozoom",
        "distort:kaleidoscope",
        "generator:fire",
    )
    private val stages = mutableListOf("display:crt", "distort:water-refraction")
    private var replacementCount = 0L
    private var lightningStyle = LightningSpectrumStyle()

    @Synchronized
    fun lightningSpectrumStyle(): LightningSpectrumStyle = lightningStyle

    @Synchronized
    fun updateLightningSpectrumStyle(value: LightningSpectrumStyle) {
        lightningStyle = value
    }

    @Synchronized
    fun replaceOneRandomStage(): Change {
        val slot = random.nextInt(stages.size)
        val old = stages[slot]
        val choices = availableEffects.filter { it != old && it !in stages }
        check(choices.isNotEmpty()) { "No distinct FX effect is available for replacement" }
        val replacement = choices[random.nextInt(choices.size)]
        stages[slot] = replacement
        replacementCount++
        return Change(slot, old, replacement, replacementCount)
    }

    @Synchronized
    fun currentEffects(): List<String> = stages.toList()

    @Synchronized
    fun graph(): FxGraph {
        val nodes = mutableListOf<FxNode>()
        val connections = mutableListOf<FxConnection>()
        var current = FxGraph.SOURCE_NODE_ID
        stages.forEachIndexed { index, effectId ->
            if (effectId == "generator:fire") {
                val generator = "stage_${index}_generator"
                val blend = "stage_${index}_blend"
                nodes += FxNode(generator, effectId)
                nodes += FxNode(blend, "composite:blend")
                connections += FxConnection(current, blend, "base")
                connections += FxConnection(generator, blend, "overlay")
                current = blend
            } else {
                val node = "stage_$index"
                nodes += FxNode(node, effectId)
                connections += FxConnection(current, node, "image")
                current = node
            }
        }
        val spectrum = "lightning_spectrum"
        val blend = "lightning_blend"
        nodes += FxNode(
            spectrum,
            "generator:lightning-spectrum",
            parameters = mapOf(
                "gain" to FxParameterValue.FloatValue(lightningStyle.gain),
                "maxHeight" to FxParameterValue.FloatValue(lightningStyle.maxHeight),
                "barGap" to FxParameterValue.FloatValue(lightningStyle.barGap),
                "glow" to FxParameterValue.FloatValue(lightningStyle.glow),
                "idleHeight" to FxParameterValue.FloatValue(lightningStyle.idleHeight),
            ),
        )
        nodes += FxNode(blend, "composite:blend")
        connections += FxConnection(current, blend, "base")
        connections += FxConnection(spectrum, blend, "overlay")
        current = blend
        return FxGraph(
            name = "infinimedia-random-${replacementCount}",
            nodes = nodes,
            connections = connections,
            outputNodeId = current,
        )
    }

    internal data class Change(val slot: Int, val previous: String, val replacement: String, val count: Long)
}

internal data class LightningSpectrumStyle(
    val gain: Float = 2.6f,
    val maxHeight: Float = .88f,
    val barGap: Float = .14f,
    val glow: Float = .9f,
    val idleHeight: Float = .04f,
)


/** A narrow recovery graph keeps the spectrum available if a randomized image filter fails. */
internal fun FxGraph.lightningOnlyFallback(): FxGraph? {
    val lightning = nodes.firstOrNull { it.effectId == "generator:lightning-spectrum" } ?: return null
    return FxGraph(
        name = "infinimedia-lightning-fallback",
        nodes = listOf(lightning.copy(id = "lightning_fallback")),
        connections = emptyList(),
        outputNodeId = "lightning_fallback",
    )
}
