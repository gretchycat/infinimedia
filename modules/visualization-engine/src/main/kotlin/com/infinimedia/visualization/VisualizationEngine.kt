package com.infinimedia.visualization

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Maps one spectrum across the left half and mirrors it across the right half. */
internal fun mirroredSpectrumBandPosition(x: Int, width: Int, lastBandIndex: Int): Float {
    if (width <= 1 || lastBandIndex <= 0) return 0f
    val column = x.coerceIn(0, width - 1)
    val distanceFromEdge = min(column, width - 1 - column)
    val maximumDistance = (width - 1) / 2f
    return (distanceFromEdge / maximumDistance).coerceIn(0f, 1f) * lastBandIndex
}

/** Keep lightning segment samples at most one Manhattan grid step apart. */
internal fun lightningSegmentSteps(x1: Float, y1: Float, x2: Float, y2: Float, width: Int, height: Int): Int {
    val gridLength = abs(x2 - x1) * width + abs(y2 - y1) * height
    return max(2, ceil(gridLength).toInt())
}

/** Spectrum amplitude controls the height of each fast, erupting flame tongue. */
internal fun fireEruptionHeight(band: Float, height: Int): Int {
    if (height <= 1) return height.coerceAtLeast(0)
    val shapedBand = band.coerceIn(0f, 1f).pow(0.55f)
    return (height * (0.05f + shapedBand * 0.78f)).toInt().coerceIn(1, height - 1)
}

/** Audio measurements timestamped against the playback position, not analyzer wall-clock time. */
data class AudioFeatureFrame(
    val sourceId: Long,
    val presentationTimeMs: Long,
    val playing: Boolean,
    val rms: Float,
    val peak: Float,
    val bands: FloatArray,
    val spectralFlux: Float,
    val transient: Boolean,
    val kickHit: Boolean = false,
    val snareHit: Boolean = false,
    val cymbalHit: Boolean = false,
    val beatPulse: Boolean = false,
    val beatStrength: Float = 0f,
    val tempoBpm: Float = 0f,
    val tempoConfidence: Float = 0f,
    val spectralCentroidHz: Float = 0f,
    val stereoWidth: Float = 0f,
    val stereoBalance: Float = 0f,
    val zeroCrossingRate: Float = 0f,
) {
    fun band(index: Int): Float = bands.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f

    companion object {
        val SILENCE = AudioFeatureFrame(0L, 0L, false, 0f, 0f, FloatArray(16), 0f, false)
    }
}

/** Distinct colors sampled from album artwork and blended gradually at track boundaries. */
data class VisualizationPalette(
    val primary: Int,
    val accent: Int,
    val highlight: Int,
    val colors: List<Int> = listOf(primary, accent, highlight).distinct(),
) {
    /** Return an actual sampled artwork color in stable cycling order. */
    fun colorAt(index: Int): Int {
        val samples = colors.ifEmpty { listOf(primary, accent, highlight).distinct() }
        val position = ((index % samples.size) + samples.size) % samples.size
        return samples[position]
    }

    companion object {
        val DEFAULT = VisualizationPalette(0xff12d49b.toInt(), 0xff25a9f2.toInt(), 0xffffc857.toInt())

        /** Keep caller-prioritized sampled colors while discarding near-duplicates. */
        fun fromSamples(candidates: Iterable<Int>, minimumRgbDistance: Int = 58): VisualizationPalette {
            val selected = mutableListOf<Int>()
            val minimumDistanceSquared = minimumRgbDistance * minimumRgbDistance
            for (candidate in candidates) {
                if (selected.all { colorDistanceSquared(it, candidate) >= minimumDistanceSquared }) {
                    selected += candidate or (0xff shl 24)
                    if (selected.size == 16) break
                }
            }
            if (selected.isEmpty()) return DEFAULT
            val primary = selected[0]
            val accent = selected.getOrElse(1) { primary }
            val highlight = selected.getOrElse(2) { accent }
            return VisualizationPalette(primary, accent, highlight, selected)
        }

        /** Selects actual pixels from separate gray levels and hue ranges. */
        fun fromPixelSamples(pixels: IntArray, minimumRgbDistance: Int = 34): VisualizationPalette {
            data class Bin(var count: Int = 0, var color: Int = 0, var score: Float = Float.NEGATIVE_INFINITY)
            val neutral = Array(3) { Bin() }
            val hues = Array(12) { Bin() }

            for (pixel in pixels) {
                if ((pixel ushr 24 and 0xff) < 96) continue
                val red = pixel ushr 16 and 0xff
                val green = pixel ushr 8 and 0xff
                val blue = pixel and 0xff
                val high = max(red, max(green, blue)) / 255f
                val low = min(red, min(green, blue)) / 255f
                val saturation = if (high <= 0f) 0f else (high - low) / high

                if (saturation < 0.17f) {
                    val index = when {
                        high < 0.16f -> 0
                        high < 0.78f -> 1
                        else -> 2
                    }
                    val target = when (index) { 0 -> 0.04f; 1 -> 0.48f; else -> 0.96f }
                    val bin = neutral[index]
                    bin.count++
                    val score = -abs(high - target)
                    if (score > bin.score) {
                        bin.score = score
                        bin.color = pixel or (0xff shl 24)
                    }
                    continue
                }

                val delta = high - low
                val hue = when {
                    high == red / 255f -> 60f * (((green - blue) / 255f) / delta).let { if (it < 0f) it + 6f else it }
                    high == green / 255f -> 60f * (((blue - red) / 255f) / delta + 2f)
                    else -> 60f * (((red - green) / 255f) / delta + 4f)
                }.let { if (it < 0f) it + 360f else it }
                val bin = hues[(hue / 30f).toInt().coerceIn(0, 11)]
                bin.count++
                val score = saturation * 0.72f + (1f - abs(high - 0.62f)) * 0.28f
                if (score > bin.score) {
                    bin.score = score
                    bin.color = pixel or (0xff shl 24)
                }
            }

            val candidates = buildList {
                neutral.filter { it.count > 0 }.forEach { add(it.color) }
                hues.filter { it.count > 0 }.forEach { add(it.color) }
            }
            if (candidates.isEmpty()) return DEFAULT
            val distinct = fromSamples(candidates, minimumRgbDistance)
            val chromatic = distinct.colors.filter { saturation(it) >= 0.17f }
            val neutralColors = distinct.colors.filter { saturation(it) < 0.17f }
            val primary = chromatic.getOrElse(0) { neutralColors.lastOrNull() ?: DEFAULT.primary }
            val accent = chromatic.getOrElse(1) { neutralColors.getOrElse(1) { DEFAULT.accent } }
            val highlight = chromatic.getOrElse(2) { neutralColors.lastOrNull() ?: DEFAULT.highlight }
            return VisualizationPalette(primary, accent, highlight, distinct.colors)
        }

        private fun saturation(color: Int): Float {
            val high = max(color ushr 16 and 0xff, max(color ushr 8 and 0xff, color and 0xff))
            val low = min(color ushr 16 and 0xff, min(color ushr 8 and 0xff, color and 0xff))
            return if (high == 0) 0f else (high - low).toFloat() / high
        }

        private fun colorDistanceSquared(first: Int, second: Int): Int {
            val red = ((first ushr 16) and 0xff) - ((second ushr 16) and 0xff)
            val green = ((first ushr 8) and 0xff) - ((second ushr 8) and 0xff)
            val blue = (first and 0xff) - (second and 0xff)
            return red * red + green * green + blue * blue
        }
    }
}

/** Velocity values are normalized canvas units per second; negative Y points upward. */
enum class SimulationField { HEAT, FLAME, COOLING, VELOCITY_X, VELOCITY_Y, DENSITY, CHARGE, LIGHT, PRESSURE, FLASH }

/** Smooth, deterministic noise for local turbulence without fixed repeating bands. */
internal object SmoothFieldNoise {
    fun sample(x: Float, y: Float, z: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val fx = fade(x - x0)
        val fy = fade(y - y0)
        val fz = fade(z - z0)

        val lower = lerp(
            lerp(lattice(x0, y0, z0), lattice(x0 + 1, y0, z0), fx),
            lerp(lattice(x0, y0 + 1, z0), lattice(x0 + 1, y0 + 1, z0), fx),
            fy,
        )
        val upper = lerp(
            lerp(lattice(x0, y0, z0 + 1), lattice(x0 + 1, y0, z0 + 1), fx),
            lerp(lattice(x0, y0 + 1, z0 + 1), lattice(x0 + 1, y0 + 1, z0 + 1), fx),
            fy,
        )
        return lerp(lower, upper, fz)
    }

    private fun lattice(x: Int, y: Int, z: Int): Float {
        var hash = x * 374761393 + y * 668265263 + z * 1442695041
        hash = (hash xor (hash ushr 13)) * 1274126177
        hash = hash xor (hash ushr 16)
        return (hash ushr 1) / Int.MAX_VALUE.toFloat() * 2f - 1f
    }

    private fun fade(value: Float): Float = value * value * (3f - 2f * value)
    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount
}

interface FieldReader {
    val width: Int
    val height: Int
    val wrapsX: Boolean get() = false
    val wrapsY: Boolean get() = false
    fun value(field: SimulationField, x: Int, y: Int): Float
}

/** Additive field writes are accumulated and applied only after every effect has run. */
class FieldContributionBuffer(val width: Int, val height: Int) {
    private val values = Array(SimulationField.entries.size) { FloatArray(width * height) }
    private val touched = BooleanArray(SimulationField.entries.size)
    private val touchedOrdinals = IntArray(SimulationField.entries.size)
    private var touchedCount = 0

    fun clear() {
        for (position in 0 until touchedCount) {
            val ordinal = touchedOrdinals[position]
            values[ordinal].fill(0f)
            touched[ordinal] = false
        }
        touchedCount = 0
    }

    fun add(field: SimulationField, x: Float, y: Float, amount: Float) {
        if (!amount.isFinite() || amount == 0f) return
        val gx = x.coerceIn(0f, 1f) * (width - 1)
        val gy = y.coerceIn(0f, 1f) * (height - 1)
        val x0 = gx.toInt()
        val y0 = gy.toInt()
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val fx = gx - x0
        val fy = gy - y0
        val fieldValues = values[field.ordinal]
        if (!touched[field.ordinal]) {
            touched[field.ordinal] = true
            touchedOrdinals[touchedCount++] = field.ordinal
        }
        fieldValues[y0 * width + x0] += amount * (1f - fx) * (1f - fy)
        fieldValues[y0 * width + x1] += amount * fx * (1f - fy)
        fieldValues[y1 * width + x0] += amount * (1f - fx) * fy
        fieldValues[y1 * width + x1] += amount * fx * fy
    }

    internal fun at(field: SimulationField, index: Int): Float = values[field.ordinal][index]
    internal fun touchedField(position: Int): SimulationField = SimulationField.entries[touchedOrdinals[position]]
    internal val touchedFieldCount: Int get() = touchedCount
}

data class EffectDescriptor(
    val id: String,
    val reads: Set<SimulationField>,
    val writes: Set<SimulationField>,
    val resourceCost: Float,
    val tags: Set<String> = emptySet(),
    val wrapsX: Boolean = false,
    val wrapsY: Boolean = false,
)

data class DirectorState(
    val energy: Float = 0f,
    val bass: Float = 0f,
    val treble: Float = 0f,
    val laserIntensity: Float = 0f,
    val fireIntensity: Float = 0.4f,
    val section: Section = Section.QUIET,
) {
    enum class Section { QUIET, FLOWING, DRIVING }
}

data class VisualizationContext(
    val audio: AudioFeatureFrame,
    val fields: FieldReader,
    val palette: VisualizationPalette,
    val director: DirectorState,
    val timeSeconds: Double,
)

/** Effects only exchange state through the declared fields and additive contribution buffer. */
interface VisualizationEffect {
    val descriptor: EffectDescriptor
    fun shouldRun(context: VisualizationContext): Boolean = true
    fun initialize(width: Int, height: Int, seed: Long) = Unit
    fun configure(parameters: Map<String, Float>) = Unit
    fun contribute(context: VisualizationContext, output: FieldContributionBuffer) = Unit
    fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) = Unit
    fun reset() = Unit
    fun close() = Unit
}

/** Slow rule-based director with smoothed energy and separated entry/exit thresholds. */
class MusicDirector {
    var state: DirectorState = DirectorState()
        private set

    private var energy = 0f
    private var bass = 0f
    private var treble = 0f
    private var sectionHoldSeconds = 0f

    fun update(frame: AudioFeatureFrame, dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.25f)
        val targetEnergy = if (frame.playing) frame.rms.coerceIn(0f, 1f) else 0f
        var lowSum = 0f
        val lowCount = min(5, frame.bands.size)
        for (i in 0 until lowCount) lowSum += frame.bands[i]
        val low = if (lowCount == 0) 0f else lowSum / lowCount
        var highSum = 0f
        val highStart = min(10, frame.bands.size)
        val highCount = frame.bands.size - highStart
        for (i in highStart until frame.bands.size) highSum += frame.bands[i]
        val high = if (highCount == 0) 0f else highSum / highCount
        val smoothing = 1f - kotlin.math.exp(-dt * if (targetEnergy > energy) 3.4f else 1.25f)
        energy += (targetEnergy - energy) * smoothing
        bass += (low - bass) * (1f - kotlin.math.exp(-dt * 2.4f))
        treble += (high - treble) * (1f - kotlin.math.exp(-dt * 2.8f))

        val previousSection = state.section
        val nextSection = when (previousSection) {
            DirectorState.Section.QUIET -> if (energy > 0.13f) DirectorState.Section.FLOWING else previousSection
            DirectorState.Section.FLOWING -> when {
                energy > 0.39f -> DirectorState.Section.DRIVING
                energy < 0.055f -> DirectorState.Section.QUIET
                else -> previousSection
            }
            DirectorState.Section.DRIVING -> if (energy < 0.28f) DirectorState.Section.FLOWING else previousSection
        }
        if (nextSection != previousSection) sectionHoldSeconds = 0f else sectionHoldSeconds += dt
        val stableSection = if (sectionHoldSeconds < 0.35f) previousSection else nextSection
        val laser = ((treble * 1.1f) + (energy * 0.32f)).coerceIn(0f, 1f)
        state = DirectorState(
            energy = energy,
            bass = bass,
            treble = treble,
            laserIntensity = laser,
            fireIntensity = (0.34f + energy * 0.62f + bass * 0.18f).coerceIn(0.25f, 1f),
            section = stableSection,
        )
    }

    fun reset() {
        energy = 0f
        bass = 0f
        treble = 0f
        sectionHoldSeconds = 0f
        state = DirectorState()
    }
}

/** Fixed, low-resolution fields shared by all effects in a composition. */
internal class SharedFields(override val width: Int, override val height: Int) : FieldReader {
    private val values = Array(SimulationField.entries.size) { FloatArray(width * height) }
    private val advectedHeat = FloatArray(width * height)
    private val nextHeat = FloatArray(width * height)
    private val advectedDensity = FloatArray(width * height)
    private val nextDensity = FloatArray(width * height)
    private val nextVelocityX = FloatArray(width * height)
    private val nextVelocityY = FloatArray(width * height)
    override var wrapsX: Boolean = false
        private set
    override var wrapsY: Boolean = false
        private set

    fun setWrapBoundary(x: Boolean, y: Boolean) {
        wrapsX = x
        wrapsY = y
    }

    override fun value(field: SimulationField, x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        return values[field.ordinal][y * width + x]
    }

    fun apply(contributions: FieldContributionBuffer) {
        for (position in 0 until contributions.touchedFieldCount) {
            val field = contributions.touchedField(position)
            val target = values[field.ordinal]
            for (i in target.indices) {
                val maximum = when (field) {
                    SimulationField.CHARGE, SimulationField.VELOCITY_X, SimulationField.VELOCITY_Y -> 1f
                    SimulationField.FLASH -> 8f
                    else -> 1.5f
                }
                target[i] = (target[i] + contributions.at(field, i)).coerceIn(
                    if (field == SimulationField.CHARGE || field == SimulationField.VELOCITY_X || field == SimulationField.VELOCITY_Y) -maximum else 0f,
                    maximum,
                )
            }
        }
    }

    fun evolve(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        advectDiffuse(
            field = SimulationField.HEAT,
            advected = advectedHeat,
            destination = nextHeat,
            dt = dt,
            diffusion = 1.5f,
            // Ambient cooling continuously removes stored temperature, including
            // after an emitter stops contributing heat.
            decay = 1.8f,
        )
        val cooling = values[SimulationField.COOLING.ordinal]
        for (i in nextHeat.indices) nextHeat[i] = (nextHeat[i] - cooling[i] * dt * 2f).coerceAtLeast(0f)
        nextHeat.copyInto(values[SimulationField.HEAT.ordinal])
        advectDiffuse(
            field = SimulationField.DENSITY,
            advected = advectedDensity,
            destination = nextDensity,
            dt = dt,
            diffusion = 0.55f,
            decay = 0.26f,
        )
        nextDensity.copyInto(values[SimulationField.DENSITY.ordinal])
        diffuseVelocity(SimulationField.VELOCITY_X, nextVelocityX, dt)
        nextVelocityX.copyInto(values[SimulationField.VELOCITY_X.ordinal])
        diffuseVelocity(SimulationField.VELOCITY_Y, nextVelocityY, dt)
        nextVelocityY.copyInto(values[SimulationField.VELOCITY_Y.ordinal])
        decay(SimulationField.LIGHT, 1.65f, dt, 0f, 1.5f)
        decay(SimulationField.CHARGE, 14f, dt, -1f, 1f)
        decay(SimulationField.FLASH, 52f, dt, 0f, 8f)
        decay(SimulationField.PRESSURE, 2.2f, dt, 0f, 1.5f)
        decay(SimulationField.COOLING, 2.8f, dt, 0f, 1.5f)
        decay(SimulationField.FLAME, 3.5f, dt, 0f, 1.5f)
    }

    /** Viscosity smooths force fields before their momentum transports heat and smoke. */
    private fun diffuseVelocity(field: SimulationField, destination: FloatArray, dt: Float) {
        val source = values[field.ordinal]
        val damping = kotlin.math.exp(-0.55f * dt)
        val viscosity = 2.8f * dt
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var sum = 0f
            var count = 0
            if (x > 0 || wrapsX) { sum += source[y * width + if (x > 0) x - 1 else width - 1]; count++ }
            if (x + 1 < width || wrapsX) { sum += source[y * width + if (x + 1 < width) x + 1 else 0]; count++ }
            if (y > 0 || wrapsY) { sum += source[(if (y > 0) y - 1 else height - 1) * width + x]; count++ }
            if (y + 1 < height || wrapsY) { sum += source[(if (y + 1 < height) y + 1 else 0) * width + x]; count++ }
            val average = if (count == 0) source[i] else sum / count
            destination[i] = (source[i] + (average - source[i]) * viscosity).coerceIn(-1f, 1f) * damping
        }
    }

    /** Forward advection lets a local velocity contribution carry the values at that point. */
    private fun advectDiffuse(
        field: SimulationField,
        advected: FloatArray,
        destination: FloatArray,
        dt: Float,
        diffusion: Float,
        decay: Float,
    ) {
        val source = values[field.ordinal]
        val velocityX = values[SimulationField.VELOCITY_X.ordinal]
        val velocityY = values[SimulationField.VELOCITY_Y.ordinal]
        val decayMultiplier = kotlin.math.exp(-decay * dt)
        advected.fill(0f)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val amount = source[i]
            if (amount <= 0f) continue
            val movedX = x + velocityX[i] * dt * (if (wrapsX) width else width - 1)
            val movedY = y + velocityY[i] * dt * (if (wrapsY) height else height - 1)
            val targetX = if (wrapsX) positiveModulo(movedX, width.toFloat()) else movedX.coerceIn(0f, (width - 1).toFloat())
            val targetY = if (wrapsY) positiveModulo(movedY, height.toFloat()) else movedY.coerceIn(0f, (height - 1).toFloat())
            val x0 = targetX.toInt()
            val y0 = targetY.toInt()
            val x1 = if (wrapsX) (x0 + 1) % width else min(width - 1, x0 + 1)
            val y1 = if (wrapsY) (y0 + 1) % height else min(height - 1, y0 + 1)
            val fx = targetX - x0
            val fy = targetY - y0
            advected[y0 * width + x0] += amount * (1f - fx) * (1f - fy)
            advected[y0 * width + x1] += amount * fx * (1f - fy)
            advected[y1 * width + x0] += amount * (1f - fx) * fy
            advected[y1 * width + x1] += amount * fx * fy
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x

            var neighborSum = 0f
            var neighbors = 0
            if (x > 0 || wrapsX) { neighborSum += advected[y * width + if (x > 0) x - 1 else width - 1]; neighbors++ }
            if (x + 1 < width || wrapsX) { neighborSum += advected[y * width + if (x + 1 < width) x + 1 else 0]; neighbors++ }
            if (y > 0 || wrapsY) { neighborSum += advected[(if (y > 0) y - 1 else height - 1) * width + x]; neighbors++ }
            if (y + 1 < height || wrapsY) { neighborSum += advected[(if (y + 1 < height) y + 1 else 0) * width + x]; neighbors++ }
            val transported = advected[i]
            val average = if (neighbors == 0) transported else neighborSum / neighbors
            destination[i] = (transported + (average - transported) * diffusion * dt).coerceIn(0f, 1.5f) * decayMultiplier
        }
    }

    private fun decay(field: SimulationField, rate: Float, dt: Float, minimum: Float, maximum: Float) {
        val values = values[field.ordinal]
        val multiplier = kotlin.math.exp(-rate * dt)
        for (i in values.indices) values[i] = (values[i] * multiplier).coerceIn(minimum, maximum)
    }

    private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus

    fun copyRenderFields(
        heat: FloatArray,
        light: FloatArray,
        charge: FloatArray,
        flash: FloatArray? = null,
        density: FloatArray? = null,
        velocityX: FloatArray? = null,
        velocityY: FloatArray? = null,
        flame: FloatArray? = null,
    ) {
        values[SimulationField.HEAT.ordinal].copyInto(heat, endIndex = min(heat.size, width * height))
        values[SimulationField.LIGHT.ordinal].copyInto(light, endIndex = min(light.size, width * height))
        values[SimulationField.CHARGE.ordinal].copyInto(charge, endIndex = min(charge.size, width * height))
        flash?.let { values[SimulationField.FLASH.ordinal].copyInto(it, endIndex = min(it.size, width * height)) }
        density?.let { values[SimulationField.DENSITY.ordinal].copyInto(it, endIndex = min(it.size, width * height)) }
        velocityX?.let { values[SimulationField.VELOCITY_X.ordinal].copyInto(it, endIndex = min(it.size, width * height)) }
        velocityY?.let { values[SimulationField.VELOCITY_Y.ordinal].copyInto(it, endIndex = min(it.size, width * height)) }
        flame?.let { values[SimulationField.FLAME.ordinal].copyInto(it, endIndex = min(it.size, width * height)) }
    }

    fun clear() {
        values.forEach { it.fill(0f) }
        advectedHeat.fill(0f)
        nextHeat.fill(0f)
        advectedDensity.fill(0f)
        nextDensity.fill(0f)
        nextVelocityX.fill(0f)
        nextVelocityY.fill(0f)
        wrapsX = false
        wrapsY = false
    }
}

private class LaserEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "laser",
        reads = setOf(SimulationField.VELOCITY_X, SimulationField.VELOCITY_Y),
        writes = setOf(SimulationField.HEAT, SimulationField.LIGHT),
        resourceCost = 0.2f,
        tags = setOf("emitter", "beam"),
    )

    override fun shouldRun(context: VisualizationContext): Boolean = context.director.laserIntensity >= 0.035f

    override fun contribute(context: VisualizationContext, output: FieldContributionBuffer) {
        val intensity = context.director.laserIntensity
        if (intensity < 0.035f) return
        val phase = context.timeSeconds * (0.38 + context.director.bass * 0.6)
        val startY = (0.5 + 0.24 * sin(phase)).toFloat()
        val endY = (0.5 + 0.24 * sin(phase + 1.3)).toFloat()
        val n = max(context.fields.width, 48)
        val energy = intensity * (0.45f + context.director.energy * 0.55f)
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val y = startY + (endY - startY) * x + 0.025f * sin((phase * 1.8 + x * 14.0).toFloat())
            output.add(SimulationField.HEAT, x, y, energy * 0.023f)
            output.add(SimulationField.LIGHT, x, y, energy * 0.042f)
        }
    }
}

private class SlowLightningEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "slow-lightning",
        reads = setOf(SimulationField.CHARGE),
        writes = setOf(SimulationField.CHARGE, SimulationField.HEAT, SimulationField.FLASH),
        resourceCost = 0.24f,
        tags = setOf("emitter", "transient"),
    )

    private val pointsX = FloatArray(11)
    private val pointsY = FloatArray(11)
    private var randomState = 0x6d2b79f5
    private var lastSource = Long.MIN_VALUE
    private var lastTransientMs = Long.MIN_VALUE
    private var remainingSeconds = 0f

    override fun shouldRun(context: VisualizationContext): Boolean =
        remainingSeconds > 0f || (context.audio.playing && context.audio.transient)

    override fun initialize(width: Int, height: Int, seed: Long) {
        randomState = (seed xor (seed ushr 32)).toInt().takeIf { it != 0 } ?: 0x6d2b79f5
    }

    override fun contribute(context: VisualizationContext, output: FieldContributionBuffer) {
        val frame = context.audio
        if (frame.sourceId != lastSource) {
            lastSource = frame.sourceId
            lastTransientMs = Long.MIN_VALUE
        }
        if (frame.transient && frame.playing && frame.presentationTimeMs > lastTransientMs) {
            lastTransientMs = frame.presentationTimeMs
            remainingSeconds = 0.11f
            createBolt()
        }
        if (remainingSeconds <= 0f) return
        val elapsedSeconds = (0.11f - remainingSeconds).coerceAtLeast(0f)
        // Let the first flash exceed the field's display range, then drop it sharply.
        // Field accumulation and output clamping create a brief clipped white peak.
        val strength = (0.55f + context.director.energy * 0.45f) *
            5f * kotlin.math.exp(-elapsedSeconds / 0.011f)
        for (i in 0 until pointsX.lastIndex) {
            drawSegment(output, pointsX[i], pointsY[i], pointsX[i + 1], pointsY[i + 1], strength)
            if (i in 3..6 && i % 2 == 0) {
                val bx = pointsX[i] + randomSigned() * 0.12f
                val by = pointsY[i] + 0.09f
                drawSegment(output, pointsX[i], pointsY[i], bx, by, strength * 0.52f)
            }
        }
    }

    override fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) {
        remainingSeconds = (remainingSeconds - dtSeconds.coerceAtMost(0.05f)).coerceAtLeast(0f)
    }

    override fun reset() {
        remainingSeconds = 0f
        lastSource = Long.MIN_VALUE
        lastTransientMs = Long.MIN_VALUE
    }

    private fun createBolt() {
        pointsX[0] = 0.25f + randomUnit() * 0.5f
        pointsY[0] = 0f
        for (i in 1 until pointsX.size) {
            pointsX[i] = (pointsX[i - 1] + randomSigned() * 0.105f).coerceIn(0.04f, 0.96f)
            pointsY[i] = i / pointsX.lastIndex.toFloat()
        }
    }

    private fun drawSegment(output: FieldContributionBuffer, x1: Float, y1: Float, x2: Float, y2: Float, strength: Float) {
        val steps = lightningSegmentSteps(x1, y1, x2, y2, output.width, output.height)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            output.add(SimulationField.CHARGE, x, y, strength * 0.11f)
            output.add(SimulationField.HEAT, x, y, strength * 0.075f)
            output.add(SimulationField.FLASH, x, y, strength * 1.8f)
        }
    }

    private fun randomUnit(): Float {
        randomState = randomState xor (randomState shl 13)
        randomState = randomState xor (randomState ushr 17)
        randomState = randomState xor (randomState shl 5)
        return (randomState ushr 1) / Int.MAX_VALUE.toFloat()
    }

    private fun randomSigned(): Float = randomUnit() * 2f - 1f
}

/** Very brief high-contrast strike for strong, sharp transients. */
private class FastLightningEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "fast-lightning",
        reads = setOf(SimulationField.CHARGE),
        writes = setOf(SimulationField.CHARGE, SimulationField.HEAT, SimulationField.FLASH),
        resourceCost = 0.12f,
        tags = setOf("emitter", "transient", "sharp-onset"),
    )

    private val pointsX = FloatArray(7)
    private val pointsY = FloatArray(7)
    private var randomState = 0x2c9277b5
    private var lastSource = Long.MIN_VALUE
    private var lastStrikeMs = Long.MIN_VALUE
    private var remainingSeconds = 0f

    override fun shouldRun(context: VisualizationContext): Boolean = remainingSeconds > 0f || isTrigger(context.audio)

    override fun initialize(width: Int, height: Int, seed: Long) {
        randomState = (seed xor (seed ushr 32)).toInt().takeIf { it != 0 } ?: 0x2c9277b5
    }

    override fun contribute(context: VisualizationContext, output: FieldContributionBuffer) {
        val frame = context.audio
        if (frame.sourceId != lastSource) {
            lastSource = frame.sourceId
            lastStrikeMs = Long.MIN_VALUE
        }
        if (isTrigger(frame) && (lastStrikeMs == Long.MIN_VALUE || frame.presentationTimeMs - lastStrikeMs >= 120L)) {
            lastStrikeMs = frame.presentationTimeMs
            remainingSeconds = 0.045f
            createBolt()
        }
        if (remainingSeconds <= 0f) return
        val elapsed = (0.045f - remainingSeconds).coerceAtLeast(0f)
        val strength = 8f * kotlin.math.exp(-elapsed / 0.0055f)
        for (i in 0 until pointsX.lastIndex) {
            val steps = lightningSegmentSteps(
                pointsX[i], pointsY[i], pointsX[i + 1], pointsY[i + 1], output.width, output.height,
            )
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                val x = pointsX[i] + (pointsX[i + 1] - pointsX[i]) * t
                val y = pointsY[i] + (pointsY[i + 1] - pointsY[i]) * t
                output.add(SimulationField.FLASH, x, y, strength * 1.2f)
                output.add(SimulationField.CHARGE, x, y, strength * 0.008f)
                // Bright but low thermal mass: the bolt flashes without leaving a broad hot wake.
                output.add(SimulationField.HEAT, x, y, strength * 0.0015f)
            }
        }
    }

    override fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) {
        remainingSeconds = (remainingSeconds - dtSeconds.coerceAtMost(0.05f)).coerceAtLeast(0f)
    }

    override fun reset() {
        remainingSeconds = 0f
        lastSource = Long.MIN_VALUE
        lastStrikeMs = Long.MIN_VALUE
    }

    private fun isTrigger(frame: AudioFeatureFrame): Boolean =
        frame.playing && frame.transient &&
            (frame.kickHit || frame.snareHit || frame.cymbalHit || frame.spectralFlux >= 0.20f) &&
            (frame.peak >= 0.08f || frame.rms >= 0.035f)

    private fun createBolt() {
        pointsX[0] = 0.25f + randomUnit() * 0.5f
        pointsY[0] = 0f
        for (i in 1 until pointsX.size) {
            pointsX[i] = (pointsX[i - 1] + randomSigned() * 0.16f).coerceIn(0.04f, 0.96f)
            pointsY[i] = i / pointsX.lastIndex.toFloat()
        }
    }

    private fun randomUnit(): Float {
        randomState = randomState xor (randomState shl 13)
        randomState = randomState xor (randomState ushr 17)
        randomState = randomState xor (randomState shl 5)
        return (randomState ushr 1) / Int.MAX_VALUE.toFloat()
    }

    private fun randomSigned(): Float = randomUnit() * 2f - 1f
}

private class SmolderEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "smolder",
        reads = setOf(SimulationField.HEAT),
        writes = setOf(SimulationField.HEAT, SimulationField.LIGHT, SimulationField.VELOCITY_X, SimulationField.VELOCITY_Y, SimulationField.DENSITY),
        resourceCost = 0.35f,
        tags = setOf("consumer", "field-driven"),
    )

    private var spectrumEnvelope = FloatArray(16)

    override fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) {
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        val fields = context.fields
        val burn = context.director.fireIntensity
        val bands = context.audio.bands
        if (spectrumEnvelope.size != bands.size) spectrumEnvelope = FloatArray(bands.size)

        // Treat the log-spaced spectrum as a row of independently fueled emitters.
        // Smoothed band levels keep the fire moving between PCM analysis windows.
        for (band in spectrumEnvelope.indices) {
            val target = if (context.audio.playing) context.audio.band(band) else 0f
            val rate = if (target > spectrumEnvelope[band]) 10f else 2.4f
            val blend = 1f - kotlin.math.exp(-rate * dt)
            spectrumEnvelope[band] += (target - spectrumEnvelope[band]) * blend
        }
        if (spectrumEnvelope.isNotEmpty()) {
            val sourceRowY = 1f
            val frameScale = dt * 30f
            for (x in 0 until fields.width) {
                val bandPosition = mirroredSpectrumBandPosition(x, fields.width, spectrumEnvelope.lastIndex)
                val low = bandPosition.toInt().coerceIn(0, spectrumEnvelope.lastIndex)
                val high = (low + 1).coerceAtMost(spectrumEnvelope.lastIndex)
                val level = spectrumEnvelope[low] + (spectrumEnvelope[high] - spectrumEnvelope[low]) * (bandPosition - low)
                val shaped = level.coerceIn(0f, 1f).pow(1.25f)
                val sourceHeat = shaped * (0.13f + burn * 0.11f) * frameScale
                output.add(SimulationField.HEAT, x / (fields.width - 1f), sourceRowY, sourceHeat)
            }
        }

        for (y in 0 until fields.height) for (x in 0 until fields.width) {
            val heat = fields.value(SimulationField.HEAT, x, y)
            val nx = x / (fields.width - 1f)
            val ny = y / (fields.height - 1f)
            if (heat >= 0.006f) {
                val brightness = heat * burn * (0.84f + context.director.bass * 0.16f)
                output.add(SimulationField.LIGHT, nx, ny, brightness * dt * 17f)
                // Fire contributes upward momentum. The shared field solver moves heat
                // and smoke, so other forces can steer these fields without fire-specific rules.
                val hotFraction = (heat / (heat + 0.06f)).coerceIn(0f, 1f)
                val acceleration = 0.25f * hotFraction * 0.55f
                output.add(SimulationField.VELOCITY_Y, nx, ny, -acceleration * dt)
                // A single travelling sine made the whole flame lock into repeating
                // diagonal bands. Smooth space-time noise keeps eddies local and varied.
                val turbulence = SmoothFieldNoise.sample(x * 0.11f, y * 0.11f, (context.timeSeconds * 0.42).toFloat())
                output.add(SimulationField.VELOCITY_X, nx, ny, turbulence * heat.coerceIn(0f, 1f) * 0.12f * dt)

                // Hot gases leave the flame as smoke, which continues rising after
                // the local heat has faded.
                output.add(SimulationField.DENSITY, nx, ny, heat * dt * 2.6f)
            }
        }
    }

    override fun reset() {
        spectrumEnvelope.fill(0f)
    }

    override fun initialize(width: Int, height: Int, seed: Long) {
        spectrumEnvelope.fill(0f)
    }
}

/** Classic low-resolution demo fire: heat advects upward, spreads sideways and cools. */
private class FireEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "fire",
        reads = setOf(SimulationField.HEAT, SimulationField.VELOCITY_X),
        writes = setOf(SimulationField.HEAT, SimulationField.FLAME, SimulationField.LIGHT, SimulationField.VELOCITY_Y),
        resourceCost = 0.42f,
        tags = setOf("emitter", "cellular-fire", "bass-triggered"),
    )

    private var temperature = FloatArray(0)
    private var nextTemperature = FloatArray(0)
    private var width = 0
    private var height = 0
    private var maximumTemperature = 0f
    private var randomState = 0x5f356495
    private var spectrumEnvelope = FloatArray(0)

    override fun initialize(width: Int, height: Int, seed: Long) {
        this.width = width
        this.height = height
        temperature = FloatArray(width * height)
        nextTemperature = FloatArray(width * height)
        spectrumEnvelope = FloatArray(16)
        randomState = (seed xor (seed ushr 32)).toInt().takeIf { it != 0 } ?: 0x5f356495
    }

    override fun shouldRun(context: VisualizationContext): Boolean =
        maximumTemperature > 0.003f || (context.audio.playing && (
            context.audio.rms >= 0.025f || context.audio.bands.any { it >= 0.025f }
        ))

    override fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) {
        val frameScale = (dtSeconds.coerceIn(0f, 0.05f) * 30f).coerceIn(0f, 1.5f)
        val frame = context.audio
        if (spectrumEnvelope.size != frame.bands.size) spectrumEnvelope = FloatArray(frame.bands.size)
        for (band in spectrumEnvelope.indices) {
            val target = if (frame.playing) frame.band(band) else 0f
            val rate = if (target > spectrumEnvelope[band]) 60f else 12f
            val blend = 1f - kotlin.math.exp(-rate * dtSeconds.coerceIn(0f, 0.05f))
            spectrumEnvelope[band] += (target - spectrumEnvelope[band]) * blend
        }
        val peakSpectrum = spectrumEnvelope.maxOrNull() ?: 0f
        val burning = frame.playing && (frame.rms >= 0.025f || peakSpectrum >= 0.025f)
        val instantaneousEnergy = max(frame.rms * 1.3f, peakSpectrum * 0.9f)
        val sourceEnergy = if (burning) {
            (0.32f + instantaneousEnergy * 1.3f + frame.spectralFlux.coerceIn(0f, 1f) * 0.35f).coerceIn(0.25f, 1.25f)
        } else 0f

        for (y in height - 2 downTo 0) for (x in 0 until width) {
            val velocity = context.fields.value(SimulationField.VELOCITY_X, x, y)
            val shift = velocity * 5f
            // Backtrace farther into the hot gas below to give flames a stronger
            // buoyant rise instead of advancing only one grid row per frame.
            val belowY = min(y + 1.7f, (height - 1).toFloat())
            val left = sample(temperature, x + shift - 1f, belowY, context.fields.wrapsX)
            val center = sample(temperature, x + shift, belowY, context.fields.wrapsX)
            val right = sample(temperature, x + shift + 1f, belowY, context.fields.wrapsX)
            val risingHeat = (left + center * 2f + right) * 0.25f
            val externalHeat = context.fields.value(SimulationField.HEAT, x, y)
            // Cool stored flame substantially faster so the layer does not keep
            // accumulating heat across successive audio peaks.
            val randomCooling = (0.012f + randomUnit() * 0.024f) * frameScale
            nextTemperature[y * width + x] = (risingHeat - randomCooling + externalHeat * 0.48f)
                .coerceIn(0f, 1.5f)
        }

        val bottom = height - 1
        for (x in 0 until width) {
            val bandPosition = mirroredSpectrumBandPosition(x, width, frame.bands.lastIndex)
            val lowBand = bandPosition.toInt().coerceIn(0, frame.bands.lastIndex.coerceAtLeast(0))
            val highBand = (lowBand + 1).coerceAtMost(frame.bands.lastIndex)
            val bandBlend = bandPosition - lowBand
            val band = if (frame.playing && spectrumEnvelope.isNotEmpty()) {
                spectrumEnvelope[lowBand] + (spectrumEnvelope[highBand] - spectrumEnvelope[lowBand]) * bandBlend
            } else 0f
            val drivenBand = (band + frame.spectralFlux.coerceIn(0f, 1f) * 0.85f).coerceIn(0f, 1f)
            val localFlame = sourceEnergy * (0.42f + drivenBand.pow(0.5f) * 0.68f)
            val sourceHeat = context.fields.value(SimulationField.HEAT, x, bottom) * 0.48f
            nextTemperature[bottom * width + x] = max(localFlame, sourceHeat).coerceIn(0f, 1.5f)

            // Inject a quick, spectrum-shaped flame tongue each frame. Its tip
            // flickers with moving noise, while its height follows the live band
            // envelope rather than waiting for slow heat advection to reach it.
            if (burning) {
                val bandHeight = fireEruptionHeight(drivenBand, height)
                val mirroredX = min(x, width - 1 - x)
                val tipNoise = SmoothFieldNoise.sample(mirroredX * 0.17f, 0.0f, (context.timeSeconds * 3.2).toFloat())
                val tongueHeight = (bandHeight * (0.84f + (tipNoise + 1f) * 0.08f))
                    .toInt().coerceIn(1, height - 1)
                for (distance in 0 until tongueHeight) {
                    val y = bottom - distance
                    val rise = 1f - distance / tongueHeight.toFloat()
                    val turbulence = SmoothFieldNoise.sample(
                        mirroredX * 0.19f,
                        distance * 0.095f,
                        (context.timeSeconds * 4.4).toFloat(),
                    )
                    val raggedness = (0.82f + turbulence * 0.22f).coerceIn(0.48f, 1f)
                    val flame = sourceEnergy * (0.48f + drivenBand.pow(0.45f) * 0.72f) *
                        rise.pow(0.72f) * raggedness
                    val index = y * width + x
                    nextTemperature[index] = max(nextTemperature[index], flame.coerceIn(0f, 1.5f))
                }
            }
        }

        val previous = temperature
        temperature = nextTemperature
        nextTemperature = previous
        maximumTemperature = 0f
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val heat = temperature[i]
            maximumTemperature = max(maximumTemperature, heat)
            if (heat <= 0.002f) continue
            val nx = x / (width - 1f)
            val ny = y / (height - 1f)
            output.add(SimulationField.FLAME, nx, ny, heat * dtSeconds.coerceAtMost(0.05f) * 3.5f)
            output.add(SimulationField.HEAT, nx, ny, heat * dtSeconds.coerceAtMost(0.05f) * 0.42f)
            output.add(SimulationField.LIGHT, nx, ny, heat * dtSeconds.coerceAtMost(0.05f) * 0.32f)
            output.add(SimulationField.VELOCITY_Y, nx, ny, -heat * dtSeconds.coerceAtMost(0.05f) * 0.08f)
        }
    }

    override fun reset() {
        temperature.fill(0f)
        nextTemperature.fill(0f)
        spectrumEnvelope.fill(0f)
        maximumTemperature = 0f
    }

    private fun sample(values: FloatArray, x: Float, y: Float, wrapsX: Boolean): Float {
        val coordinate = if (wrapsX) ((x % width) + width) % width else x.coerceIn(0f, (width - 1).toFloat())
        val x0 = coordinate.toInt()
        val x1 = if (wrapsX) (x0 + 1) % width else min(width - 1, x0 + 1)
        val fractionX = coordinate - x0
        val sampleY = y.coerceIn(0f, (height - 1).toFloat())
        val y0 = sampleY.toInt()
        val y1 = min(height - 1, y0 + 1)
        val fractionY = sampleY - y0
        val top = values[y0 * width + x0] * (1f - fractionX) + values[y0 * width + x1] * fractionX
        val bottom = values[y1 * width + x0] * (1f - fractionX) + values[y1 * width + x1] * fractionX
        return top * (1f - fractionY) + bottom * fractionY
    }

    private fun randomUnit(): Float {
        randomState = randomState xor (randomState shl 13)
        randomState = randomState xor (randomState ushr 17)
        randomState = randomState xor (randomState shl 5)
        return (randomState ushr 1) / Int.MAX_VALUE.toFloat()
    }
}

/** Slow rotating wind that carries the shared fields across both canvas edges and cools them. */
private class WindEffect : VisualizationEffect {
    override val descriptor = EffectDescriptor(
        id = "wind",
        reads = setOf(SimulationField.VELOCITY_X, SimulationField.VELOCITY_Y),
        writes = setOf(SimulationField.VELOCITY_X, SimulationField.VELOCITY_Y, SimulationField.COOLING),
        resourceCost = 0.18f,
        tags = setOf("force", "cooling", "audio-activity-triggered"),
        wrapsX = true,
        wrapsY = true,
    )

    override fun shouldRun(context: VisualizationContext): Boolean = context.audio.playing && (
        context.audio.rms >= 0.15f || context.director.energy >= 0.1f || context.director.bass >= 0.12f
    )

    override fun update(context: VisualizationContext, dtSeconds: Float, output: FieldContributionBuffer) {
        if (!shouldRun(context)) return
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        val time = context.timeSeconds
        val angle = time * 0.16 + sin(time * 0.29) * 0.62 + context.director.bass * 0.8
        val speed = (0.08f + context.director.energy * 0.16f) * (0.82f + 0.18f * sin(time * 0.43).toFloat())
        val baseX = cos(angle).toFloat() * speed
        val baseY = sin(angle).toFloat() * speed
        val fields = context.fields
        for (y in 0 until fields.height) for (x in 0 until fields.width) {
            val nx = x / (fields.width - 1f)
            val ny = y / (fields.height - 1f)
            val noiseX = SmoothFieldNoise.sample(x * 0.055f, y * 0.055f, (time * 0.16).toFloat())
            val noiseY = SmoothFieldNoise.sample((x + 37) * 0.055f, (y - 19) * 0.055f, (time * 0.16 + 11.3).toFloat())
            output.add(SimulationField.VELOCITY_X, nx, ny, (baseX + noiseX * 0.035f) * dt * 0.75f)
            output.add(SimulationField.VELOCITY_Y, nx, ny, (baseY + noiseY * 0.035f) * dt * 0.75f)
            output.add(SimulationField.COOLING, nx, ny, dt * 0.8f)
        }
    }
}

/**
 * Platform-independent simulation/composition core. Effects read a stable field snapshot and
 * accumulate contributions in batches, preventing effect iteration order from changing results.
 */
class MusicVisualizationEngine(
    // A finer simulation grid keeps narrow transients crisp after the field is scaled
    // into the video pane, while remaining substantially smaller than display pixels.
    val width: Int = 320,
    val height: Int = 180,
    seed: Long = 0x1f1f1fL,
) : AutoCloseable {
    private val fields = SharedFields(width.coerceAtLeast(8), height.coerceAtLeast(8))
    private val contributions = FieldContributionBuffer(fields.width, fields.height)
    private val effects: List<VisualizationEffect> = listOf(
        LaserEffect(),
        SlowLightningEffect(),
        FastLightningEffect(),
        SmolderEffect(),
        FireEffect(),
        WindEffect(),
    )
    private val enabled = effects.mapTo(mutableSetOf()) { it.descriptor.id }
    private val activeNow = linkedSetOf<String>()
    private var sourceId = Long.MIN_VALUE
    private var previousPositionMs = Long.MIN_VALUE
    private var directorElapsed = 0f
    private var timeSeconds = 0.0
    private var palette = VisualizationPalette.DEFAULT
    private var targetPalette = palette
    private var lightningPaletteIndex = 0
    private var lastLightningSourceId = Long.MIN_VALUE
    private var lastLightningTransientMs = Long.MIN_VALUE
    private var lightningAfterglowColor = palette.colorAt(0)
    private var closed = false

    val director = MusicDirector()
    val effectDescriptors: List<EffectDescriptor> get() = effects.map { it.descriptor }
    val activeEffectIds: Set<String> get() = synchronized(this) { activeNow.toSet() }

    init {
        effects.forEachIndexed { index, effect -> effect.initialize(fields.width, fields.height, seed + index * 0x9e3779b9L) }
    }

    @Synchronized
    fun setPalette(value: VisualizationPalette) {
        targetPalette = value
    }

    @Synchronized
    fun setEffectEnabled(id: String, enabled: Boolean) {
        require(effects.any { it.descriptor.id == id }) { "Unknown visualization effect: $id" }
        if (enabled) this.enabled.add(id) else this.enabled.remove(id)
    }

    @Synchronized
    fun advance(dtSeconds: Float, audio: AudioFeatureFrame) {
        if (closed) return
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        if (audio.sourceId != sourceId) {
            sourceId = audio.sourceId
            previousPositionMs = audio.presentationTimeMs
        } else if (previousPositionMs != Long.MIN_VALUE && audio.playing &&
            audio.presentationTimeMs < previousPositionMs - 250L) {
            // A backwards seek is a discontinuity; don't turn the stale window into a new beat.
            fields.clear()
            director.reset()
            effects.forEach { it.reset() }
        }
        previousPositionMs = audio.presentationTimeMs
        timeSeconds += dt
        directorElapsed += dt
        if (directorElapsed >= 0.25f) {
            director.update(audio, directorElapsed.coerceAtMost(0.75f))
            directorElapsed = 0f
        }
        palette = blendPalette(palette, targetPalette, (dt * 0.32f).coerceIn(0f, 1f))
        if (audio.sourceId != lastLightningSourceId) {
            lastLightningSourceId = audio.sourceId
            lastLightningTransientMs = Long.MIN_VALUE
        }
        val newLightningTransient = lastLightningTransientMs == Long.MIN_VALUE ||
            audio.presentationTimeMs - lastLightningTransientMs >= 120L
        if (audio.playing && audio.transient && newLightningTransient) {
            lastLightningTransientMs = audio.presentationTimeMs
            lightningAfterglowColor = palette.colorAt(lightningPaletteIndex++)
        }

        val emitContext = VisualizationContext(audio, fields, palette, director.state, timeSeconds)
        contributions.clear()
        activeNow.clear()
        for (effect in effects) if (effect.descriptor.id in enabled && effect.shouldRun(emitContext)) {
            activeNow.add(effect.descriptor.id)
            effect.contribute(emitContext, contributions)
        }
        fields.setWrapBoundary(
            x = effects.any { it.descriptor.id in activeNow && it.descriptor.wrapsX },
            y = effects.any { it.descriptor.id in activeNow && it.descriptor.wrapsY },
        )
        fields.apply(contributions)

        // The update pass sees the same post-emission snapshot. Its writes become visible
        // together after all consumers have run.
        val updateContext = VisualizationContext(audio, fields, palette, director.state, timeSeconds)
        contributions.clear()
        for (effect in effects) if (effect.descriptor.id in enabled && effect.shouldRun(updateContext)) {
            activeNow.add(effect.descriptor.id)
            effect.update(updateContext, dt, contributions)
        }
        fields.apply(contributions)
        fields.evolve(dt)
    }

    @Synchronized
    fun copyRenderFields(
        heat: FloatArray,
        light: FloatArray,
        charge: FloatArray,
        flash: FloatArray? = null,
        density: FloatArray? = null,
        velocityX: FloatArray? = null,
        velocityY: FloatArray? = null,
        flame: FloatArray? = null,
    ) {
        fields.copyRenderFields(heat, light, charge, flash, density, velocityX, velocityY, flame)
    }

    @Synchronized
    fun currentPalette(): VisualizationPalette = palette

    /** Artwork color used for the current strike's outer, fading afterglow. */
    @Synchronized
    fun currentLightningAfterglowColor(): Int = lightningAfterglowColor

    @Synchronized
    fun hardReset() {
        fields.clear()
        director.reset()
        effects.forEach { it.reset() }
        sourceId = Long.MIN_VALUE
        previousPositionMs = Long.MIN_VALUE
        directorElapsed = 0f
        lastLightningSourceId = Long.MIN_VALUE
        lastLightningTransientMs = Long.MIN_VALUE
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        effects.forEach { runCatching { it.close() } }
        fields.clear()
    }

    private fun blendPalette(from: VisualizationPalette, to: VisualizationPalette, amount: Float): VisualizationPalette {
        val colorCount = max(from.colors.size, to.colors.size).coerceAtLeast(1)
        val colors = (0 until colorCount).map { index ->
            blendColor(
                from.colors.getOrElse(index) { from.colors.lastOrNull() ?: from.primary },
                to.colors.getOrElse(index) { to.colors.lastOrNull() ?: to.primary },
                amount,
            )
        }.distinct()
        return VisualizationPalette(
            blendColor(from.primary, to.primary, amount),
            blendColor(from.accent, to.accent, amount),
            blendColor(from.highlight, to.highlight, amount),
            colors,
        )
    }

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int) = (((from ushr shift) and 0xff) * (1f - amount) + ((to ushr shift) and 0xff) * amount).toInt()
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
