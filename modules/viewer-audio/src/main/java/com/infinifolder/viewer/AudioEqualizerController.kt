package com.infinifolder.viewer

import android.content.Context
import android.media.audiofx.Equalizer

internal data class AudioEqualizerBand(
    val centerFrequencyHz: Int,
    val levelMillibels: Short,
)

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
    private var enabled = preferences.getBoolean(KEY_ENABLED, false)
    private var activePreset = preferences.getString(KEY_PRESET, CUSTOM_PRESET) ?: CUSTOM_PRESET
    private var storedLevels = preferences.getString(KEY_LEVELS, null)
        ?.split(',')
        ?.mapNotNull { it.toShortOrNull() }
        ?.toShortArray()

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || sessionId == audioSessionId && effect != null) return
        releaseEffect()
        try {
            val created = Equalizer(0, audioSessionId)
            effect = created
            sessionId = audioSessionId
            restore(created)
        } catch (_: Exception) {
            effect = null
            sessionId = 0
        }
    }

    @Synchronized
    fun snapshot(): AudioEqualizerSnapshot? {
        val current = effect ?: return null
        return runCatching {
            val range = current.bandLevelRange
            val count = current.numberOfBands.toInt()
            AudioEqualizerSnapshot(
                enabled = current.enabled,
                minLevelMillibels = range[0],
                maxLevelMillibels = range[1],
                bands = (0 until count).map { index ->
                    AudioEqualizerBand(
                        centerFrequencyHz = current.getCenterFreq(index.toShort()).coerceAtLeast(0),
                        levelMillibels = current.getBandLevel(index.toShort()),
                    )
                },
                presets = (0 until current.numberOfPresets.toInt()).map { current.getPresetName(it.toShort()) },
                activePreset = activePreset,
            )
        }.getOrNull()
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
        runCatching { current.setBandLevel(index.toShort(), levelMillibels) }
        activePreset = CUSTOM_PRESET
        persistCurrentLevels(current)
    }

    @Synchronized
    fun usePreset(index: Int): Boolean {
        val current = effect ?: return false
        if (index !in 0 until current.numberOfPresets.toInt()) return false
        return runCatching {
            current.usePreset(index.toShort())
            activePreset = current.getPresetName(index.toShort())
            persistCurrentLevels(current)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun reset() {
        val current = effect ?: return
        for (index in 0 until current.numberOfBands.toInt()) {
            runCatching { current.setBandLevel(index.toShort(), 0) }
        }
        activePreset = CUSTOM_PRESET
        persistCurrentLevels(current)
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
        storedLevels?.takeIf { it.size == current.numberOfBands.toInt() }?.forEachIndexed { index, level ->
            runCatching { current.setBandLevel(index.toShort(), level) }
        }
        runCatching { current.enabled = enabled }
    }

    private fun persistCurrentLevels(current: Equalizer) {
        storedLevels = (0 until current.numberOfBands.toInt()).map { current.getBandLevel(it.toShort()) }.toShortArray()
        preferences.edit()
            .putString(KEY_LEVELS, storedLevels!!.joinToString(","))
            .putString(KEY_PRESET, activePreset)
            .apply()
    }

    private fun releaseEffect() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_LEVELS = "levels"
        const val CUSTOM_PRESET = "Custom"
    }
}
