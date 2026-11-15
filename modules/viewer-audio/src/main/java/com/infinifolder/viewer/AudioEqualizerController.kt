package com.infinifolder.viewer

import android.content.Context
import android.media.audiofx.Equalizer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val PERSISTED_EQ_FREQUENCIES_HZ = intArrayOf(60, 170, 310, 600, 1_000, 3_000, 6_000, 12_000, 14_000, 16_000)

/** Interpolate a persisted equalizer curve in log-frequency space. */
internal fun interpolateEqualizerCurve(frequencyHz: Int, frequenciesHz: IntArray, levels: ShortArray): Short {
    if (levels.isEmpty() || frequenciesHz.size != levels.size) return 0
    val target = frequencyHz.coerceAtLeast(1).toFloat()
    if (target <= frequenciesHz.first()) return levels.first()
    if (target >= frequenciesHz.last()) return levels.last()
    val upper = frequenciesHz.indices.first { frequenciesHz[it] >= target }
    val lower = upper - 1
    val lowFrequency = ln(frequenciesHz[lower].toFloat())
    val highFrequency = ln(frequenciesHz[upper].toFloat())
    val fraction = (ln(target) - lowFrequency) / (highFrequency - lowFrequency)
    return (levels[lower] + (levels[upper] - levels[lower]) * fraction).roundToInt().toShort()
}

/** Convert any legacy/native-sized preference into the stable ten-slot Winamp curve. */
internal fun normalizePersistedEqualizerLevels(levels: ShortArray): ShortArray {
    if (levels.size == PERSISTED_EQ_FREQUENCIES_HZ.size) return levels.copyOf()
    if (levels.isEmpty()) return ShortArray(PERSISTED_EQ_FREQUENCIES_HZ.size)
    val legacyFrequencies = IntArray(levels.size) { index ->
        if (levels.size == 1) PERSISTED_EQ_FREQUENCIES_HZ.first()
        else (PERSISTED_EQ_FREQUENCIES_HZ.first() *
            (PERSISTED_EQ_FREQUENCIES_HZ.last().toDouble() / PERSISTED_EQ_FREQUENCIES_HZ.first())
                .pow(index.toDouble() / (levels.size - 1))).roundToInt()
    }
    return ShortArray(PERSISTED_EQ_FREQUENCIES_HZ.size) { index ->
        interpolateEqualizerCurve(PERSISTED_EQ_FREQUENCIES_HZ[index], legacyFrequencies, levels)
    }
}

internal data class AudioEqualizerBand(
    val centerFrequencyHz: Int,
    val levelMillibels: Short,
    val supported: Boolean,
)

/** Android reports band frequencies in milliHertz. Invalid ranges mean the band cannot be controlled. */
internal fun isEqualizerBandSupported(centerFrequencyMilliHz: Int, frequencyRangeMilliHz: IntArray): Boolean =
    frequencyRangeMilliHz.size == 2 &&
        centerFrequencyMilliHz > 0 &&
        frequencyRangeMilliHz[0] >= 0 &&
        frequencyRangeMilliHz[1] > frequencyRangeMilliHz[0] &&
        centerFrequencyMilliHz in frequencyRangeMilliHz[0]..frequencyRangeMilliHz[1]

internal fun equalizerCenterFrequencyHz(centerFrequencyMilliHz: Int): Int =
    centerFrequencyMilliHz.coerceAtLeast(0) / 1_000

internal data class AudioEqualizerSnapshot(
    val enabled: Boolean,
    val minLevelMillibels: Short,
    val maxLevelMillibels: Short,
    val bands: List<AudioEqualizerBand>,
    val presets: List<String>,
    val activePreset: String,
)

/** Owns the phone's platform equalizer and persists its settings independently of a player session. */
internal class AudioEqualizerController(context: Context) {
    private val preferences = context.getSharedPreferences("infinimedia_equalizer", Context.MODE_PRIVATE)
    private var effect: Equalizer? = null
    private var sessionId: Int = 0
    private var supportedBands = BooleanArray(0)
    private var enabled = preferences.getBoolean(KEY_ENABLED, false)
    private var activePreset = preferences.getString(KEY_PRESET, CUSTOM_PRESET) ?: CUSTOM_PRESET
    private var storedLevels = preferences.getString(KEY_LEVELS, null)
        ?.split(',')
        ?.mapNotNull { it.toShortOrNull() }
        ?.toShortArray()
        ?.let(::normalizePersistedEqualizerLevels)
        ?: ShortArray(PERSISTED_EQ_FREQUENCIES_HZ.size)

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || sessionId == audioSessionId && effect != null) return
        releaseEffect()
        try {
            val created = Equalizer(0, audioSessionId)
            effect = created
            sessionId = audioSessionId
            supportedBands = discoverSupportedBands(created)
            restore(created)
        } catch (_: Exception) {
            effect = null
            sessionId = 0
            supportedBands = BooleanArray(0)
        }
    }

    @Synchronized
    fun snapshot(): AudioEqualizerSnapshot {
        val current = effect ?: return AudioEqualizerSnapshot(
            enabled = enabled,
            minLevelMillibels = (-1_500).toShort(),
            maxLevelMillibels = 1_500,
            bands = PERSISTED_EQ_FREQUENCIES_HZ.map { frequency ->
                AudioEqualizerBand(frequency, 0, supported = false)
            },
            presets = emptyList(),
            activePreset = activePreset,
        )
        return runCatching {
            val range = current.bandLevelRange
            val count = current.numberOfBands.toInt()
            AudioEqualizerSnapshot(
                enabled = current.enabled,
                minLevelMillibels = range[0],
                maxLevelMillibels = range[1],
                bands = (0 until count).map { index ->
                    val supported = supportedBands.getOrNull(index) == true
                    AudioEqualizerBand(
                        centerFrequencyHz = equalizerCenterFrequencyHz(current.getCenterFreq(index.toShort())),
                        levelMillibels = if (supported) current.getBandLevel(index.toShort()) else 0,
                        supported = supported,
                    )
                },
                presets = (0 until current.numberOfPresets.toInt()).map { current.getPresetName(it.toShort()) },
                activePreset = activePreset,
            )
        }.getOrElse {
            AudioEqualizerSnapshot(
                enabled = enabled,
                minLevelMillibels = (-1_500).toShort(),
                maxLevelMillibels = 1_500,
                bands = PERSISTED_EQ_FREQUENCIES_HZ.map { AudioEqualizerBand(it, 0, supported = false) },
                presets = emptyList(),
                activePreset = activePreset,
            )
        }
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        runCatching { effect?.enabled = value }
    }

    @Synchronized
    fun setBandLevel(index: Int, levelMillibels: Short) {
        val current = effect ?: return
        if (index !in 0 until current.numberOfBands.toInt()) return
        if (supportedBands.getOrNull(index) != true) return
        val applied = runCatching { current.setBandLevel(index.toShort(), levelMillibels) }.isSuccess
        if (!applied) return
        activePreset = CUSTOM_PRESET
        persistCurrentLevels(current)
    }

    @Synchronized
    fun usePreset(index: Int): Boolean {
        val current = effect ?: return false
        if (index !in 0 until current.numberOfPresets.toInt()) return false
        return runCatching {
            current.usePreset(index.toShort())
            centerUnsupportedBands(current)
            activePreset = current.getPresetName(index.toShort())
            persistCurrentLevels(current)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun reset() {
        val current = effect
        if (current != null) {
            for (index in 0 until current.numberOfBands.toInt()) {
                if (supportedBands.getOrNull(index) == true) {
                    runCatching { current.setBandLevel(index.toShort(), 0) }
                }
            }
        }
        storedLevels = ShortArray(PERSISTED_EQ_FREQUENCIES_HZ.size)
        activePreset = CUSTOM_PRESET
        persistSettings()
    }

    @Synchronized
    fun release() {
        releaseEffect()
    }

    private fun restore(current: Equalizer) {
        val names = (0 until current.numberOfPresets.toInt()).map { current.getPresetName(it.toShort()) }
        val presetIndex = names.indexOfFirst { it.equals(activePreset, ignoreCase = true) }
        if (presetIndex >= 0 && activePreset != CUSTOM_PRESET) {
            runCatching { current.usePreset(presetIndex.toShort()) }
        }
        centerUnsupportedBands(current)
        for (index in 0 until current.numberOfBands.toInt()) {
            if (supportedBands.getOrNull(index) == true) {
                val frequencyHz = equalizerCenterFrequencyHz(current.getCenterFreq(index.toShort()))
                val level = interpolateEqualizerCurve(frequencyHz, PERSISTED_EQ_FREQUENCIES_HZ, storedLevels)
                    .coerceIn(current.bandLevelRange[0], current.bandLevelRange[1])
                runCatching { current.setBandLevel(index.toShort(), level) }
            }
        }
        runCatching { current.enabled = enabled }
    }

    private fun persistCurrentLevels(current: Equalizer) {
        val available = (0 until current.numberOfBands.toInt()).mapNotNull { index ->
            if (supportedBands.getOrNull(index) != true) return@mapNotNull null
            val frequency = equalizerCenterFrequencyHz(current.getCenterFreq(index.toShort()))
            frequency.takeIf { it > 0 }?.let { it to current.getBandLevel(index.toShort()) }
        }.sortedBy { it.first }
        if (available.isNotEmpty()) {
            val frequencies = available.map { it.first }.toIntArray()
            val levels = available.map { it.second }.toShortArray()
            storedLevels = ShortArray(PERSISTED_EQ_FREQUENCIES_HZ.size) { index ->
                interpolateEqualizerCurve(PERSISTED_EQ_FREQUENCIES_HZ[index], frequencies, levels)
            }
        }
        persistSettings()
    }

    private fun persistSettings() {
        preferences.edit()
            .putString(KEY_LEVELS, storedLevels.joinToString(","))
            .putString(KEY_PRESET, activePreset)
            .apply()
    }

    private fun discoverSupportedBands(current: Equalizer): BooleanArray =
        (0 until current.numberOfBands.toInt()).map { index ->
            runCatching {
                val band = index.toShort()
                isEqualizerBandSupported(current.getCenterFreq(band), current.getBandFreqRange(band))
            }.getOrDefault(false)
        }.toBooleanArray()

    private fun centerUnsupportedBands(current: Equalizer) {
        supportedBands.forEachIndexed { index, supported ->
            if (!supported) runCatching { current.setBandLevel(index.toShort(), 0) }
        }
    }

    private fun releaseEffect() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
        supportedBands = BooleanArray(0)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_LEVELS = "levels"
        const val CUSTOM_PRESET = "Custom"
    }
}
