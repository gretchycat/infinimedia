package com.infinifolder.viewer

import org.infiniforge.fx.api.AudioFrameMetadata
import kotlin.math.max
import kotlin.math.sqrt

/** Adapts the already-normalized visualizer bridge output; it performs no audio analysis. */
internal object FxAudioMetadataAdapter {
    fun snapshot(bands: FloatArray, vu: FloatArray, timestampNanos: Long): AudioFrameMetadata {
        val normalizedBands = bands.map { finiteUnit(it) }
        val low = average(normalizedBands, 0, 4)
        val mid = average(normalizedBands, 4, 10)
        val high = average(normalizedBands, 10, 16)
        val left = vu.getOrElse(0) { 0f }.let(::finiteUnit)
        val right = vu.getOrElse(1) { 0f }.let(::finiteUnit)
        val leftPeak = vu.getOrElse(2) { 0f }.let(::finiteUnit)
        val rightPeak = vu.getOrElse(3) { 0f }.let(::finiteUnit)
        return AudioFrameMetadata(
            timestampNanos = timestampNanos,
            rms = sqrt((left * left + right * right) * 0.5f),
            peak = max(leftPeak, rightPeak),
            bands = normalizedBands,
            lowEnergy = low,
            midEnergy = mid,
            highEnergy = high,
        )
    }

    private fun average(values: List<Float>, start: Int, end: Int): Float {
        val slice = values.subList(start.coerceAtMost(values.size), end.coerceAtMost(values.size))
        return if (slice.isEmpty()) 0f else slice.sum() / slice.size
    }

    private fun finiteUnit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
