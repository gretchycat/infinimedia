package com.infinimedia.visualization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVisualizationEngineTest {
    private fun total(fields: SharedFields, field: SimulationField): Float =
        (0 until fields.height).sumOf { y ->
            (0 until fields.width).sumOf { x -> fields.value(field, x, y).toDouble() }
        }.toFloat()

    @Test
    fun defaultSimulationCanvasUsesTheHigherResolutionGrid() {
        val engine = MusicVisualizationEngine()
        try {
            assertEquals(320, engine.width)
            assertEquals(180, engine.height)
        } finally {
            engine.close()
        }
    }

    @Test
    fun additiveFieldContributionsDoNotDependOnEffectIterationOrder() {
        val first = FieldContributionBuffer(32, 18)
        val second = FieldContributionBuffer(32, 18)
        val deposits = listOf(
            Triple(SimulationField.HEAT, 0.2f to 0.3f, 0.14f),
            Triple(SimulationField.HEAT, 0.8f to 0.7f, 0.27f),
            Triple(SimulationField.LIGHT, 0.5f to 0.4f, 0.19f),
        )
        deposits.forEach { (field, point, amount) -> first.add(field, point.first, point.second, amount) }
        deposits.asReversed().forEach { (field, point, amount) -> second.add(field, point.first, point.second, amount) }

        for (field in SimulationField.entries) for (index in 0 until 32 * 18) {
            assertEquals(first.at(field, index), second.at(field, index), 0.000001f)
        }
    }

    @Test
    fun sharedVelocityAdvectsHeatAndSmokeInTheContributedDirection() {
        val fields = SharedFields(41, 31)
        val force = FieldContributionBuffer(41, 31)
        force.add(SimulationField.HEAT, 0.5f, 0.7f, 1f)
        force.add(SimulationField.DENSITY, 0.5f, 0.7f, 0.8f)
        force.add(SimulationField.VELOCITY_X, 0.5f, 0.7f, 0.5f)
        force.add(SimulationField.VELOCITY_Y, 0.5f, 0.7f, -0.5f)
        fields.apply(force)

        fields.evolve(0.1f)

        fun centroid(field: SimulationField): Pair<Float, Float> {
            var sum = 0f
            var weightedX = 0f
            var weightedY = 0f
            for (y in 0 until fields.height) for (x in 0 until fields.width) {
                val value = fields.value(field, x, y)
                sum += value
                weightedX += x * value
                weightedY += y * value
            }
            return weightedX / sum to weightedY / sum
        }

        val heatCenter = centroid(SimulationField.HEAT)
        val smokeCenter = centroid(SimulationField.DENSITY)
        assertTrue("positive X momentum should carry heat right", heatCenter.first > 20.5f)
        assertTrue("negative Y momentum should carry heat upward", heatCenter.second < 20.5f)
        assertTrue("the same shared velocity should carry smoke right", smokeCenter.first > 20.5f)
        assertTrue("the same shared velocity should carry smoke upward", smokeCenter.second < 20.5f)
    }

    @Test
    fun temperatureCoolsWhenNoEffectAddsMoreHeat() {
        val fields = SharedFields(24, 16)
        val initialHeat = FieldContributionBuffer(24, 16)
        initialHeat.add(SimulationField.HEAT, 0.5f, 0.5f, 1f)
        fields.apply(initialHeat)
        val initialTotal = (0 until fields.height).sumOf { y ->
            (0 until fields.width).sumOf { x -> fields.value(SimulationField.HEAT, x, y).toDouble() }
        }

        repeat(30) { fields.evolve(1f / 30f) }

        val cooledTotal = (0 until fields.height).sumOf { y ->
            (0 until fields.width).sumOf { x -> fields.value(SimulationField.HEAT, x, y).toDouble() }
        }
        assertTrue("temperature should cool without new heat input", cooledTotal < initialTotal * 0.7)
        assertTrue("cooling should dissipate heat gradually rather than erase it instantly", cooledTotal > 0.01)
    }

    @Test
    fun activeCoolingRemovesAdditionalHeat() {
        val ambient = SharedFields(20, 12)
        val windCooled = SharedFields(20, 12)
        for (fields in listOf(ambient, windCooled)) {
            val heat = FieldContributionBuffer(fields.width, fields.height)
            heat.add(SimulationField.HEAT, 0.5f, 0.5f, 1f)
            fields.apply(heat)
        }
        val wind = FieldContributionBuffer(windCooled.width, windCooled.height)
        wind.add(SimulationField.COOLING, 0.5f, 0.5f, 1f)
        windCooled.apply(wind)
        repeat(20) {
            ambient.evolve(1f / 30f)
            windCooled.evolve(1f / 30f)
        }
        val ambientHeat = total(ambient, SimulationField.HEAT)
        val windHeat = total(windCooled, SimulationField.HEAT)
        assertTrue("wind cooling should remove more heat than ambient cooling alone", windHeat < ambientHeat)
    }

    @Test
    fun wrappedFieldsCrossBothCanvasEdges() {
        val fields = SharedFields(24, 16)
        fields.setWrapBoundary(x = true, y = true)
        val source = FieldContributionBuffer(24, 16)
        source.add(SimulationField.HEAT, 0.95f, 0.95f, 1f)
        source.add(SimulationField.VELOCITY_X, 0.95f, 0.95f, 0.5f)
        source.add(SimulationField.VELOCITY_Y, 0.95f, 0.95f, 0.5f)
        fields.apply(source)
        fields.evolve(0.1f)
        assertTrue("right edge flow should wrap to the left", fields.value(SimulationField.HEAT, 0, 0) > 0f)
    }

    @Test
    fun smolderIsContinuousWhileSlowLightningWaitsForTransients() {
        val engine = MusicVisualizationEngine(24, 16)
        engine.advance(1f / 30f, AudioFeatureFrame.SILENCE)
        assertTrue("smolder remains the continuous source effect", engine.activeEffectIds.contains("smolder"))
        assertTrue("slow lightning should remain dormant without an onset", !engine.activeEffectIds.contains("slow-lightning"))

        engine.advance(1f / 30f, AudioFeatureFrame(21L, 33L, true, 0.4f, 0.5f, FloatArray(16), 0.08f, true))
        assertTrue("moderate transients should trigger slow lightning", engine.activeEffectIds.contains("slow-lightning"))
        assertTrue("fast lightning should ignore moderate transients", !engine.activeEffectIds.contains("fast-lightning"))
        engine.close()
    }

    @Test
    fun windUsesAnAudioActivityTriggerAndDeclaresToroidalCooling() {
        val engine = MusicVisualizationEngine(24, 16)
        listOf("laser", "slow-lightning", "fast-lightning", "smolder", "fire").forEach {
            engine.setEffectEnabled(it, false)
        }
        engine.advance(1f / 30f, AudioFeatureFrame(31L, 0L, false, 0f, 0f, FloatArray(16), 0f, false))
        assertTrue("wind should stay dormant without playback", !engine.activeEffectIds.contains("wind"))
        engine.advance(1f / 30f, AudioFeatureFrame(31L, 33L, true, 0.55f, 0.8f, FloatArray(16), 0f, false))

        val wind = engine.effectDescriptors.single { it.id == "wind" }
        assertTrue("wind should activate during energetic playback", engine.activeEffectIds.contains("wind"))
        assertTrue("wind should wrap the horizontal field boundary", wind.wrapsX)
        assertTrue("wind should wrap the vertical field boundary", wind.wrapsY)
        assertTrue("wind should contribute to the shared cooling field", SimulationField.COOLING in wind.writes)
        engine.close()
    }

    @Test
    fun windTurbulenceDoesNotRepeatAsFixedDiagonalBands() {
        var difference = 0f
        var samples = 0
        for (y in 0..100 step 10) for (x in 0..130 step 13) {
            val first = SmoothFieldNoise.sample(x * 0.055f, y * 0.055f, 1.6f)
            val translated = SmoothFieldNoise.sample((x + 52) * 0.055f, y * 0.055f, 1.6f)
            difference += kotlin.math.abs(first - translated)
            samples++
        }
        assertTrue(
            "wind eddies should not repeat at the former 52-cell sine period",
            difference / samples > 0.12f,
        )
    }

    @Test
    fun spectrumFuelsAFullWidthFireThatRisesAndProducesSmoke() {
        val engine = MusicVisualizationEngine(64, 36, seed = 16L)
        engine.setEffectEnabled("laser", false)
        engine.setEffectEnabled("slow-lightning", false)
        engine.setEffectEnabled("fast-lightning", false)
        engine.setEffectEnabled("fire", false)
        engine.setEffectEnabled("wind", false)
        val bands = FloatArray(16) { 0.82f }
        repeat(72) { frame ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 3L,
                presentationTimeMs = frame * 33L,
                playing = true,
                rms = 0.45f,
                peak = 0.7f,
                bands = bands,
                spectralFlux = 0f,
                transient = false,
            ))
        }
        val heat = FloatArray(64 * 36)
        val density = FloatArray(heat.size)
        val velocityX = FloatArray(heat.size)
        val velocityY = FloatArray(heat.size)
        engine.copyRenderFields(heat, FloatArray(heat.size), FloatArray(heat.size), density = density, velocityX = velocityX, velocityY = velocityY)
        val upperHeat = heat.indices.filter { it / 64 < 28 }.sumOf { heat[it].toDouble() }
        val litColumns = (0 until 64).count { x -> (0 until 36).any { y -> heat[y * 64 + x] > 0.004f } }
        assertTrue("frequency-driven flames should rise from the baseline", upperHeat > 0.02)
        assertTrue("spectrum bands should fuel flames across the width", litColumns >= 58)
        assertTrue("the rising flame should leave drifting smoke", density.sum() > 0.02f)
        assertTrue("fire turbulence should produce varied left and right flow", (velocityX.minOrNull() ?: 0f) < -0.001f && (velocityX.maxOrNull() ?: 0f) > 0.001f)
        assertTrue("fire should contribute upward momentum", (velocityY.minOrNull() ?: 0f) < -0.02f)
        assertTrue("fire momentum should remain a limited quarter-scale contribution", (velocityY.minOrNull() ?: 0f) > -0.35f)
        engine.close()
    }

    @Test
    fun fireMirrorsTheFrequencyProfileAroundTheCenter() {
        val engine = MusicVisualizationEngine(65, 36, seed = 38L)
        listOf("laser", "slow-lightning", "fast-lightning", "smolder", "wind").forEach {
            engine.setEffectEnabled(it, false)
        }
        val bands = FloatArray(16) { it / 15f }
        engine.advance(1f / 30f, AudioFeatureFrame(
            sourceId = 48L,
            presentationTimeMs = 0L,
            playing = true,
            rms = 0.55f,
            peak = 0.8f,
            bands = bands,
            spectralFlux = 0f,
            transient = false,
        ))

        val flame = FloatArray(65 * 36)
        engine.copyRenderFields(FloatArray(flame.size), FloatArray(flame.size), FloatArray(flame.size), flame = flame)
        val baseline = 35 * 65
        for (x in 0 until 65) {
            assertEquals("mirrored fire input at column $x", flame[baseline + x], flame[baseline + 64 - x], 0.00001f)
        }
        assertTrue("the mirrored spectrum should peak in the middle", flame[baseline + 32] > flame[baseline])
        engine.close()
    }

    @Test
    fun fireEruptsQuicklyFromTheLiveFrequencyProfileAtLowOverallVolume() {
        val engine = MusicVisualizationEngine(64, 36, seed = 83L)
        listOf("laser", "slow-lightning", "fast-lightning", "smolder", "wind").forEach {
            engine.setEffectEnabled(it, false)
        }
        val bands = FloatArray(16) { 0.02f + it / 15f * 0.8f }
        engine.advance(1f / 30f, AudioFeatureFrame(
            sourceId = 51L,
            presentationTimeMs = 0L,
            playing = true,
            rms = 0.03f,
            peak = 0.1f,
            bands = bands,
            spectralFlux = 0f,
            transient = false,
        ))

        assertTrue("the live spectrum should start fire without waiting for a loudness threshold", engine.activeEffectIds.contains("fire"))
        val flame = FloatArray(64 * 36)
        engine.copyRenderFields(FloatArray(flame.size), FloatArray(flame.size), FloatArray(flame.size), flame = flame)
        val comparisonRow = 20
        assertTrue(
            "the stronger middle bands should erupt higher than the quiet edge bands",
            flame[comparisonRow * 64 + 32] > flame[comparisonRow * 64],
        )
        engine.close()
    }

    @Test
    fun fireLayerCoolsQuicklyAfterItsAudioSourceStops() {
        val engine = MusicVisualizationEngine(48, 27, seed = 92L)
        listOf("laser", "slow-lightning", "fast-lightning", "smolder", "wind").forEach {
            engine.setEffectEnabled(it, false)
        }
        val bands = FloatArray(16) { 0.72f }
        repeat(30) { frame ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 12L,
                presentationTimeMs = frame * 33L,
                playing = true,
                rms = 0.28f,
                peak = 0.48f,
                bands = bands,
                spectralFlux = 0f,
                transient = false,
            ))
        }
        val flame = FloatArray(48 * 27)
        fun flameTotal(): Float {
            engine.copyRenderFields(FloatArray(flame.size), FloatArray(flame.size), FloatArray(flame.size), flame = flame)
            return flame.sum()
        }
        val activeFire = flameTotal()
        repeat(30) { frame ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 12L,
                presentationTimeMs = 1000L + frame * 33L,
                playing = false,
                rms = 0f,
                peak = 0f,
                bands = FloatArray(16),
                spectralFlux = 0f,
                transient = false,
            ))
        }

        assertTrue("the audio source should produce a visible fire layer", activeFire > 0.1f)
        val cooledFire = flameTotal()
        assertTrue("stored fire heat should at least halve soon after the source stops ($cooledFire vs $activeFire)", cooledFire < activeFire * 0.5f)
        engine.close()
    }

    @Test
    fun laserAndLightningContributeHeatThatFireConsumesAndHeatPersistsAfterEmittersRetire() {
        val engine = MusicVisualizationEngine(48, 27, seed = 1234L)
        val loudSpectrum = FloatArray(16) { 0.82f }
        repeat(18) { index ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 1L,
                presentationTimeMs = index * 33L,
                playing = true,
                rms = 0.72f,
                peak = 0.94f,
                bands = loudSpectrum,
                spectralFlux = if (index == 15) 0.4f else 0.02f,
                transient = index == 15,
            ))
        }
        assertTrue(engine.activeEffectIds.containsAll(setOf("laser", "slow-lightning", "smolder", "fire", "wind")))
        val heat = FloatArray(48 * 27)
        val light = FloatArray(48 * 27)
        val charge = FloatArray(48 * 27)
        engine.copyRenderFields(heat, light, charge)
        assertTrue("both emitters should deposit heat", heat.sum() > 0.35f)
        assertTrue("the heat-driven fire consumer should emit light", light.sum() > 0.02f)
        assertTrue("the transient lightning effect should deposit charge", charge.sum() > 0f)

        engine.setEffectEnabled("laser", false)
        engine.setEffectEnabled("slow-lightning", false)
        engine.setEffectEnabled("fast-lightning", false)
        engine.setEffectEnabled("fire", false)
        engine.setEffectEnabled("wind", false)
        engine.advance(1f / 30f, AudioFeatureFrame(1L, 627L, false, 0f, 0f, FloatArray(16), 0f, false))
        val afterRetirement = FloatArray(48 * 27)
        engine.copyRenderFields(afterRetirement, FloatArray(48 * 27), FloatArray(48 * 27))
        assertTrue("residual heat should decay naturally after the emitters stop", afterRetirement.sum() > 0f)
        engine.close()
    }

    @Test
    fun paletteChangesAreBlendedRatherThanAppliedAsAJump() {
        val engine = MusicVisualizationEngine(16, 9)
        val initial = engine.currentPalette()
        engine.setPalette(VisualizationPalette(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()))
        engine.advance(1f / 30f, AudioFeatureFrame.SILENCE)
        val afterOneFrame = engine.currentPalette()
        assertTrue(initial.primary != afterOneFrame.primary)
        assertTrue(afterOneFrame.primary != 0xffff0000.toInt())
        engine.close()
    }

    @Test
    fun artworkPaletteSelectionDropsNearDuplicatePixelColors() {
        val palette = VisualizationPalette.fromSamples(listOf(
            0xffd42b22.toInt(),
            0xffd82e25.toInt(), // nearly the same red should be discarded
            0xff2438d8.toInt(),
            0xffe5ce28.toInt(),
        ))

        assertEquals(3, palette.colors.size)
        assertEquals(0xffd42b22.toInt(), palette.primary)
        assertEquals(0xff2438d8.toInt(), palette.accent)
        assertEquals(0xffe5ce28.toInt(), palette.highlight)
    }

    @Test
    fun artworkPaletteKeepsNeutralLevelsAndActualSpectrumColors() {
        val expected = intArrayOf(
            0xff000000.toInt(), 0xff777777.toInt(), 0xffffffff.toInt(),
            0xffff0000.toInt(), 0xffff8000.toInt(), 0xffffff00.toInt(),
            0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff8000ff.toInt(),
        )
        val palette = VisualizationPalette.fromPixelSamples(expected)

        assertEquals("black, gray, white and the sampled spectrum should survive", expected.size, palette.colors.size)
        expected.forEach { color -> assertTrue("palette must contain sampled pixel $color", palette.colors.contains(color)) }
        assertEquals("the visual accent should be chromatic rather than gray", expected[3], palette.primary)
    }

    @Test
    fun lightningAfterglowCyclesThroughDistinctArtworkSamples() {
        val artworkColors = listOf(
            0xffd02020.toInt(), 0xffe09020.toInt(), 0xffd0d020.toInt(),
            0xff20b050.toInt(), 0xff2080d0.toInt(), 0xff8030c0.toInt(),
        )
        val palette = VisualizationPalette(artworkColors[0], artworkColors[1], artworkColors[2], artworkColors)
        val engine = MusicVisualizationEngine(24, 16)
        engine.setPalette(palette)
        repeat(360) { engine.advance(0.05f, AudioFeatureFrame.SILENCE) }

        val selected = mutableListOf<Int>()
        val expected = mutableListOf<Int>()
        artworkColors.indices.forEach { index ->
            engine.advance(0.05f, AudioFeatureFrame(
                sourceId = 91L,
                presentationTimeMs = 1_000L + index * 240L,
                playing = true,
                rms = 0.4f,
                peak = 0.7f,
                bands = FloatArray(16),
                spectralFlux = 0.1f,
                transient = true,
            ))
            selected += engine.currentLightningAfterglowColor()
            expected += engine.currentPalette().colorAt(index)
        }

        assertEquals(artworkColors.size, selected.distinct().size)
        artworkColors.indices.forEach { index ->
            assertEquals(expected[index], selected[index])
        }
        engine.close()
    }

    @Test
    fun classicFirePropagatesHeatUpFromAnAudioTriggeredSource() {
        val engine = MusicVisualizationEngine(64, 64, seed = 77L)
        listOf("laser", "slow-lightning", "fast-lightning", "smolder", "wind").forEach {
            engine.setEffectEnabled(it, false)
        }
        val bands = FloatArray(16) { 0.38f }
        repeat(60) { index ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 44L,
                presentationTimeMs = index * 33L,
                playing = true,
                rms = 0.42f,
                peak = 0.7f,
                bands = bands,
                spectralFlux = 0f,
                transient = false,
            ))
        }
        val flame = FloatArray(64 * 64)
        engine.copyRenderFields(FloatArray(flame.size), FloatArray(flame.size), FloatArray(flame.size), flame = flame)
        assertTrue("the classic fire effect should be triggered by sustained audio", engine.activeEffectIds.contains("fire"))
        assertTrue(
            "the rising flame should reach the upper half of the field within two seconds",
            flame.indices.any { it / 64 < 32 && flame[it] > 0.05f },
        )
        engine.close()
    }

    @Test
    fun fastLightningRequiresAStrongerOnsetAndLeavesLessHeatThanSlowLightning() {
        fun strike(effectId: String): Pair<Float, Float> {
            val engine = MusicVisualizationEngine(48, 27, seed = 52L)
            listOf("laser", "smolder", "fire", "wind", "slow-lightning", "fast-lightning")
                .filter { it != effectId }
                .forEach { engine.setEffectEnabled(it, false) }
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 9L,
                presentationTimeMs = 0L,
                playing = true,
                rms = 0.55f,
                peak = 0.9f,
                bands = FloatArray(16),
                spectralFlux = 0.28f,
                transient = true,
            ))
            val heat = FloatArray(48 * 27)
            val flash = FloatArray(heat.size)
            engine.copyRenderFields(heat, FloatArray(heat.size), FloatArray(heat.size), flash = flash)
            val result = heat.sum() to (flash.maxOrNull() ?: 0f)
            engine.close()
            return result
        }

        val slow = strike("slow-lightning")
        val fast = strike("fast-lightning")
        assertTrue("fast lightning should keep a bright clipped flash", fast.second > 1f)
        assertTrue("fast lightning should leave less ambient heat than slow lightning", fast.first < slow.first)

        val engine = MusicVisualizationEngine(48, 27, seed = 53L)
        listOf("laser", "smolder", "fire", "wind").forEach { engine.setEffectEnabled(it, false) }
        engine.advance(1f / 30f, AudioFeatureFrame(10L, 0L, true, 0.5f, 0.9f, FloatArray(16), 0.08f, true))
        assertTrue("moderate transients should retain the slower lightning but not trigger fast lightning", engine.activeEffectIds.contains("slow-lightning"))
        assertTrue("fast lightning should wait for the stronger spectral-flux trigger", !engine.activeEffectIds.contains("fast-lightning"))
        engine.close()
    }

    @Test
    fun lightningFlashStartsClippedAndFallsAwayQuickly() {
        val engine = MusicVisualizationEngine(48, 27, seed = 42L)
        val features = FloatArray(16)
        val flash = FloatArray(48 * 27)

        engine.advance(1f / 30f, AudioFeatureFrame(
            sourceId = 7L,
            presentationTimeMs = 0L,
            playing = true,
            rms = 0.5f,
            peak = 1f,
            bands = features,
            spectralFlux = 1f,
            transient = true,
        ))
        engine.copyRenderFields(FloatArray(flash.size), FloatArray(flash.size), FloatArray(flash.size), flash)
        val peak = flash.maxOrNull() ?: 0f
        assertTrue("initial flash should exceed the normal display range", peak > 1f)

        repeat(3) { frame ->
            engine.advance(1f / 30f, AudioFeatureFrame(
                sourceId = 7L,
                presentationTimeMs = (frame + 1) * 33L,
                playing = false,
                rms = 0f,
                peak = 0f,
                bands = features,
                spectralFlux = 0f,
                transient = false,
            ))
        }
        engine.copyRenderFields(FloatArray(flash.size), FloatArray(flash.size), FloatArray(flash.size), flash)
        assertTrue("flash should fade rapidly after its clipped peak", (flash.maxOrNull() ?: 0f) < peak * 0.4f)
        engine.close()
    }
}
