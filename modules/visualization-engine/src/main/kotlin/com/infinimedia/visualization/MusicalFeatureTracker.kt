package com.infinimedia.visualization

import kotlin.math.sqrt
import kotlin.math.ceil

/** Onset and tempo estimates derived incrementally from log-frequency display bands. */
class MusicalFeatureTracker(private val bandCount: Int = 16) {
    init { require(bandCount >= 3) { "At least three spectrum bands are required" } }
    data class Features(
        val spectralFlux: Float = 0f,
        val transient: Boolean = false,
        val kickHit: Boolean = false,
        val snareHit: Boolean = false,
        val cymbalHit: Boolean = false,
        val beatPulse: Boolean = false,
        val beatStrength: Float = 0f,
        val tempoBpm: Float = 0f,
        val tempoConfidence: Float = 0f,
    )

    private val previousBands = FloatArray(bandCount)
    private var initialized = false
    private var previousTimeMs = Long.MIN_VALUE
    private var lowEnvelope = 0f
    private var midEnvelope = 0f
    private var highEnvelope = 0f
    private var lastKickMs = Long.MIN_VALUE
    private var lastSnareMs = Long.MIN_VALUE
    private var lastCymbalMs = Long.MIN_VALUE
    private var lastBeatMs = Long.MIN_VALUE
    private val beatIntervalsBpm = ArrayDeque<Float>()
    private var tempoBpm = 0f
    private var tempoConfidence = 0f

    fun reset() {
        previousBands.fill(0f)
        initialized = false
        previousTimeMs = Long.MIN_VALUE
        lowEnvelope = 0f; midEnvelope = 0f; highEnvelope = 0f
        lastKickMs = Long.MIN_VALUE; lastSnareMs = Long.MIN_VALUE; lastCymbalMs = Long.MIN_VALUE
        lastBeatMs = Long.MIN_VALUE
        beatIntervalsBpm.clear()
        tempoBpm = 0f; tempoConfidence = 0f
    }

    /** Bands are normalized from -72 to 0 dBFS. Hits are heuristic onset classes, not source separation. */
    fun update(presentationTimeMs: Long, playing: Boolean, bands: FloatArray): Features {
        val now = presentationTimeMs.coerceAtLeast(0L)
        if (previousTimeMs != Long.MIN_VALUE && now + 100L < previousTimeMs) reset()
        // For 20 Hz..20 kHz log bands these boundaries approximate a
        // 160 Hz kick region and a 4 kHz cymbal region.
        val lowEnd = ceil(bandCount * 0.301f).toInt().coerceIn(1, bandCount - 2)
        val midEnd = ceil(bandCount * 0.768f).toInt().coerceIn(lowEnd + 1, bandCount - 1)
        val currentLow = regionMean(bands, 0, lowEnd)
        val currentMid = regionMean(bands, lowEnd, midEnd)
        val currentHigh = regionMean(bands, midEnd, bandCount)
        if (!initialized) {
            copyBands(bands)
            lowEnvelope = currentLow; midEnvelope = currentMid; highEnvelope = currentHigh
            initialized = true
            previousTimeMs = now
            return Features(tempoBpm = tempoBpm, tempoConfidence = tempoConfidence)
        }

        var fluxSquares = 0f
        var lowFluxSquares = 0f; var midFluxSquares = 0f; var highFluxSquares = 0f
        var lowCount = 0; var midCount = 0; var highCount = 0
        for (index in 0 until bandCount) {
            val current = bands.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val rise = (current - previousBands[index]).coerceAtLeast(0f)
            fluxSquares += rise * rise
            when {
                index < lowEnd -> { lowFluxSquares += rise * rise; lowCount++ }
                index < midEnd -> { midFluxSquares += rise * rise; midCount++ }
                else -> { highFluxSquares += rise * rise; highCount++ }
            }
            previousBands[index] = current
        }
        val flux = (sqrt(fluxSquares / bandCount.coerceAtLeast(1)) * 4f).coerceIn(0f, 1f)
        val lowFlux = sqrt(lowFluxSquares / lowCount.coerceAtLeast(1))
        val midFlux = sqrt(midFluxSquares / midCount.coerceAtLeast(1))
        val highFlux = sqrt(highFluxSquares / highCount.coerceAtLeast(1))
        val lowRise = (currentLow - lowEnvelope).coerceAtLeast(0f)
        val midRise = (currentMid - midEnvelope).coerceAtLeast(0f)
        val highRise = (currentHigh - highEnvelope).coerceAtLeast(0f)
        // Positive spectral flux is the attack signal. The slower envelope adds
        // a little energy context without retriggering on a sustained loud note.
        val kickStrength = (lowFlux * 2.15f + lowRise * 0.4f).coerceIn(0f, 1f)
        val snareStrength = (midFlux * 2.05f + midRise * 0.38f).coerceIn(0f, 1f)
        val cymbalStrength = (highFlux * 2.0f + highRise * 0.35f).coerceIn(0f, 1f)
        val kick = playing && kickStrength >= 0.29f && elapsedAtLeast(now, lastKickMs, 95L).also { if (it) lastKickMs = now }
        val snare = playing && snareStrength >= 0.27f && elapsedAtLeast(now, lastSnareMs, 85L).also { if (it) lastSnareMs = now }
        val cymbal = playing && cymbalStrength >= 0.25f && elapsedAtLeast(now, lastCymbalMs, 70L).also { if (it) lastCymbalMs = now }

        // Bass and body onsets estimate pulse. Fine high-hat transients do not
        // double the tempo as readily when excluded from beat interval samples.
        val beatStrength = maxOf(if (kick) kickStrength else 0f, if (snare) snareStrength else 0f)
        var beatPulse = false
        if (playing && beatStrength > 0f && elapsedAtLeast(now, lastBeatMs, 220L)) {
            if (lastBeatMs != Long.MIN_VALUE) addBeatInterval(now - lastBeatMs)
            lastBeatMs = now
            beatPulse = true
        }

        val elapsed = if (previousTimeMs == Long.MIN_VALUE) 33L else (now - previousTimeMs).coerceAtLeast(1L)
        val riseAlpha = (elapsed / 180f).coerceIn(0.06f, 0.22f)
        val fallAlpha = (elapsed / 1100f).coerceIn(0.012f, 0.07f)
        lowEnvelope = approachEnvelope(lowEnvelope, currentLow, riseAlpha, fallAlpha)
        midEnvelope = approachEnvelope(midEnvelope, currentMid, riseAlpha, fallAlpha)
        highEnvelope = approachEnvelope(highEnvelope, currentHigh, riseAlpha, fallAlpha)
        previousTimeMs = now

        val transient = playing && flux >= 0.095f && maxOf(kickStrength, snareStrength, cymbalStrength) >= 0.34f
        return Features(
            spectralFlux = flux,
            transient = transient,
            kickHit = kick,
            snareHit = snare,
            cymbalHit = cymbal,
            beatPulse = beatPulse,
            beatStrength = beatStrength,
            tempoBpm = tempoBpm,
            tempoConfidence = tempoConfidence,
        )
    }

    private fun addBeatInterval(intervalMs: Long) {
        if (intervalMs !in 250L..2000L) return
        var candidate = 60_000f / intervalMs
        while (candidate < 60f) candidate *= 2f
        while (candidate > 180f) candidate *= 0.5f
        if (tempoBpm == 0f) tempoBpm = candidate else tempoBpm += (candidate - tempoBpm) * 0.24f
        if (beatIntervalsBpm.size == 8) beatIntervalsBpm.removeFirst()
        beatIntervalsBpm.addLast(candidate)
        val average = beatIntervalsBpm.average().toFloat()
        val variance = beatIntervalsBpm.map { (it - average) * (it - average) }.average().toFloat()
        val stability = (1f - sqrt(variance) / average.coerceAtLeast(1f)).coerceIn(0f, 1f)
        tempoConfidence = (beatIntervalsBpm.size / 4f).coerceIn(0f, 1f) * stability
    }

    private fun regionMean(bands: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0f
        for (index in start until end) sum += bands.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return sum / (end - start)
    }

    private fun copyBands(bands: FloatArray) {
        for (index in previousBands.indices) {
            previousBands[index] = bands.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        }
    }

    private fun approachEnvelope(current: Float, target: Float, rise: Float, fall: Float): Float =
        current + (target - current) * if (target > current) rise else fall

    private fun elapsedAtLeast(now: Long, before: Long, minimumMs: Long): Boolean =
        before == Long.MIN_VALUE || now - before >= minimumMs
}
