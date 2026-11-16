package com.infinifolder.viewer

internal const val LIGHTNING_CORE_THRESHOLD = 0.02f
internal const val LIGHTNING_CORE_FULL_INTENSITY = 0.22f
internal const val LIGHTNING_CORE_SUPPORT_THRESHOLD = 0.015f
internal const val LIGHTNING_CORE_SUPPORT_FULL_INTENSITY = 0.08f
internal const val LIGHTNING_INNER_GLOW_GAIN = 0.5f
internal const val LIGHTNING_INNER_GLOW_MAX_ALPHA = 0.08f
internal const val LIGHTNING_OUTER_GLOW_GAIN = 0.5f
internal const val LIGHTNING_OUTER_GLOW_MAX_ALPHA = 0.05f

/** Mirrors the OpenGL shader's linear, low-alpha glow response. */
internal fun lightningGlowAlpha(fieldValue: Float, gain: Float, maximumAlpha: Float): Float {
    if (!fieldValue.isFinite() || !gain.isFinite() || !maximumAlpha.isFinite()) return 0f
    return (fieldValue.coerceIn(0f, 1f) * gain.coerceAtLeast(0f)).coerceIn(0f, maximumAlpha.coerceIn(0f, 0.1f))
}
/** Soft core response that drops unconnected single-cell flash remnants. */
internal fun lightningCoreAlpha(fieldValue: Float, neighborFieldValue: Float): Float {
    if (!fieldValue.isFinite() || !neighborFieldValue.isFinite()) return 0f
    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    return smoothstep(LIGHTNING_CORE_THRESHOLD, LIGHTNING_CORE_FULL_INTENSITY, fieldValue.coerceIn(0f, 1f)) *
        smoothstep(
            LIGHTNING_CORE_SUPPORT_THRESHOLD,
            LIGHTNING_CORE_SUPPORT_FULL_INTENSITY,
            neighborFieldValue.coerceIn(0f, 1f),
        )
}

/** Publishes all simulation fields used by the GPU compositor as one indivisible frame. */
internal class LightningFieldFrameSource(width: Int, height: Int) {
    val lock = Any()
    val flashPixels = ByteArray(width * height)
    /** RGBA channels carry perceptually encoded heat, light, flame and smoke density. */
    val firePixels = ByteArray(width * height * 4)
    var paletteColor: Int = 0xffffffff.toInt()
        private set
    var version: Long = 0L
        private set

    init { require(width > 0 && height > 0) }

    fun publish(
        heat: FloatArray,
        light: FloatArray,
        flame: FloatArray,
        density: FloatArray,
        flash: FloatArray,
        afterglowColor: Int,
    ) = synchronized(lock) {
        val size = flashPixels.size
        require(heat.size >= size && light.size >= size && flame.size >= size && density.size >= size && flash.size >= size)
        for (index in flashPixels.indices) {
            flashPixels[index] = (flash[index].coerceIn(0f, 1f) * 255f).toInt().toByte()
            val offset = index * 4
            firePixels[offset] = encodeField(heat[index])
            firePixels[offset + 1] = encodeField(light[index])
            firePixels[offset + 2] = encodeField(flame[index])
            firePixels[offset + 3] = encodeField(density[index])
        }
        paletteColor = afterglowColor or (0xff shl 24)
        version++
    }

    private fun encodeField(value: Float): Byte =
        (kotlin.math.sqrt(value.coerceIn(0f, 1f)) * 255f).toInt().coerceIn(0, 255).toByte()
}
