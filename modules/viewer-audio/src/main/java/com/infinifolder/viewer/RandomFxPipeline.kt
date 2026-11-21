package com.infinifolder.viewer

import org.infiniforge.fx.api.FxConnection
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxNode
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
        return FxGraph(
            name = "infinimedia-random-${replacementCount}",
            nodes = nodes,
            connections = connections,
            outputNodeId = current,
        )
    }

    internal data class Change(val slot: Int, val previous: String, val replacement: String, val count: Long)
}
