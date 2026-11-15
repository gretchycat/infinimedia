package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.infinifolder.theme.ThemeManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ln

private val WINAMP_EQ_FREQUENCIES_HZ = intArrayOf(60, 170, 310, 600, 1_000, 3_000, 6_000, 12_000, 14_000, 16_000)

internal data class WinampEqualizerSlot(
    val effectBandIndex: Int?,
    val levelMillibels: Short,
    val supported: Boolean,
)

internal fun mapToWinampEqualizerSlots(bands: List<AudioEqualizerBand>): List<WinampEqualizerSlot> {
    val mapped = MutableList(WINAMP_EQ_FREQUENCIES_HZ.size) { WinampEqualizerSlot(null, 0, supported = false) }
    val availableSlots = WINAMP_EQ_FREQUENCIES_HZ.indices.toMutableSet()
    bands.indices.sortedByDescending { bands[it].supported }.forEach { effectIndex ->
        if (availableSlots.isEmpty()) return@forEach
        val frequency = bands[effectIndex].centerFrequencyHz.coerceAtLeast(1).toFloat()
        val slot = availableSlots.minByOrNull { index ->
            abs(ln(WINAMP_EQ_FREQUENCIES_HZ[index].toFloat()) - ln(frequency))
        } ?: return@forEach
        availableSlots.remove(slot)
        val band = bands[effectIndex]
        mapped[slot] = WinampEqualizerSlot(
            effectBandIndex = effectIndex,
            levelMillibels = if (band.supported) band.levelMillibels else 0,
            supported = band.supported,
        )
    }

    val supportedSlots = mapped.indices.filter { mapped[it].supported }
    return mapped.mapIndexed { index, slot ->
        if (slot.supported || supportedSlots.isEmpty()) return@mapIndexed slot
        val previous = supportedSlots.lastOrNull { it < index }?.let { mapped[it] }
        val next = supportedSlots.firstOrNull { it > index }?.let { mapped[it] }
        val interpolated = when {
            previous != null && next != null -> (previous.levelMillibels + next.levelMillibels) / 2
            previous != null -> previous.levelMillibels
            next != null -> next.levelMillibels
            else -> 0
        }
        slot.copy(levelMillibels = interpolated.toShort())
    }
}

/** Remove the stock thumb pixels while preserving the skin's own rail and graduation pixels. */
internal fun eraseBakedEqualizerThumbs(
    width: Int,
    height: Int,
    source: IntArray,
    sliderCenters: List<Int>,
): IntArray {
    require(width > 0 && height > 0 && source.size == width * height)
    val result = source.copyOf()
    data class BakedThumb(val centerX: Int, val start: Int, val end: Int)
    val thumbs = sliderCenters.mapNotNull { centerX ->
        if (centerX !in 6 until width - 6) return@mapNotNull null
        var bestStart = -1
        var bestEnd = -1
        var runStart = -1
        for (y in 44 until minOf(94, height)) {
            val pixel = source[y * width + centerX]
            val brightness = ((pixel ushr 16) and 0xff) + ((pixel ushr 8) and 0xff) + (pixel and 0xff)
            if (brightness >= 360) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                if (y - runStart > bestEnd - bestStart) {
                    bestStart = runStart
                    bestEnd = y
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && minOf(94, height) - runStart > bestEnd - bestStart) {
            bestStart = runStart
            bestEnd = minOf(94, height)
        }
        if (bestStart < 0 || bestEnd - bestStart < 5) return@mapNotNull null
        BakedThumb(centerX, bestStart, bestEnd)
    }

    for (thumb in thumbs) {
        val centerX = thumb.centerX
        val top = (thumb.start - 5).coerceAtLeast(0)
        val bottom = (thumb.end + 4).coerceAtMost(height - 1)
        val cleanRail = thumbs.asSequence()
            .filter { other -> other.centerX != centerX }
            .filter { other -> other.end + 4 < top || other.start - 5 > bottom }
            .minByOrNull { other -> kotlin.math.abs(other.centerX - centerX) }
            ?.centerX

        if (cleanRail != null) {
            for (offset in -6..6) for (y in top..bottom) {
                result[y * width + centerX + offset] = source[y * width + cleanRail + offset]
            }
        } else {
            val span = (bottom - top).coerceAtLeast(1)
            for (x in centerX - 6..centerX + 6) {
                val topPixel = source[top * width + x]
                val bottomPixel = source[bottom * width + x]
                for (y in top + 1 until bottom) {
                    val fraction = (y - top).toFloat() / span
                    val topRed = (topPixel ushr 16) and 0xff
                    val topGreen = (topPixel ushr 8) and 0xff
                    val topBlue = topPixel and 0xff
                    val red = (topRed + (((bottomPixel ushr 16) and 0xff) - topRed) * fraction).toInt()
                    val green = (topGreen + (((bottomPixel ushr 8) and 0xff) - topGreen) * fraction).toInt()
                    val blue = (topBlue + ((bottomPixel and 0xff) - topBlue) * fraction).toInt()
                    result[y * width + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
    }
    return result
}

/** Winamp 2.x equalizer faceplate with live Android band controls. */
internal class WinampEqualizerView(context: Context) : View(context) {
    var onEnabledChanged: ((Boolean) -> Unit)? = null
    var onBandChanged: ((Int, Short) -> Unit)? = null
    var onPresetsRequested: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null

    private var snapshot: AudioEqualizerSnapshot? = null
    private var displayBands = emptyList<WinampEqualizerSlot>()
    private var draggingBand = -1
    private var enabled = false
    private var shaded = false
    private var cleanedFaceplateSource: android.graphics.Bitmap? = null
    private var cleanedFaceplate: android.graphics.Bitmap? = null
    private val backgroundPaint = Paint().apply { isFilterBitmap = false }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun render(value: AudioEqualizerSnapshot) {
        snapshot = value
        displayBands = mapToWinampEqualizerSlots(value.bands)
        enabled = value.enabled
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val nativeHeight = if (shaded) SHADED_HEIGHT else NATIVE_HEIGHT
        val desiredHeight = (width * nativeHeight / NATIVE_WIDTH.toFloat()).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        val nativeHeight = if (shaded) SHADED_HEIGHT else NATIVE_HEIGHT
        canvas.scale(width / NATIVE_WIDTH.toFloat(), height / nativeHeight.toFloat())
        // ThemeManager loads the selected skin after the activity's view hierarchy is built.
        // Resolve lazily here so the EQ doesn't permanently capture the pre-theme null fallback.
        val skinBitmap = ThemeManager.skinBitmap("eqmain.bmp")
        if (shaded) {
            if (skinBitmap != null && skinBitmap.width >= NATIVE_WIDTH && skinBitmap.height >= SHADED_HEIGHT) {
                canvas.drawBitmap(skinBitmap, Rect(0, 0, NATIVE_WIDTH, SHADED_HEIGHT),
                    Rect(0, 0, NATIVE_WIDTH, SHADED_HEIGHT), backgroundPaint)
            } else {
                drawFallbackTitleBar(canvas)
            }
            canvas.restoreToCount(save)
            return
        }
        if (skinBitmap != null && skinBitmap.width >= NATIVE_WIDTH && skinBitmap.height >= NATIVE_HEIGHT) {
            val faceplate = cleanedFaceplate(skinBitmap)
            canvas.drawBitmap(faceplate, Rect(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT),
                Rect(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT), backgroundPaint)
        } else {
            drawFallbackFaceplate(canvas)
        }
        val value = snapshot
        if (value != null) {
            // Keep the skin's rail backgrounds and graduated marks untouched. Only the
            // baked thumbs are cleared from the faceplate before live thumbs are drawn.
            displayBands.forEachIndexed { index, band ->
                val low = value.minLevelMillibels.toInt()
                val high = value.maxLevelMillibels.toInt()
                val level = band.levelMillibels.toInt().coerceIn(low, high)
                val x = slotX(index)
                drawBand(canvas, x, level, low, high)
            }
            drawKnob(canvas, 27.5f, 67f)
            drawPowerButton(canvas, skinBitmap)
        }
        canvas.restoreToCount(save)
    }

    private fun drawFallbackFaceplate(canvas: Canvas) {
        canvas.drawColor(Color.rgb(40, 47, 58))
        fillPaint.shader = android.graphics.LinearGradient(
            0f, 1f, 0f, 115f,
            intArrayOf(Color.rgb(247, 249, 252), Color.rgb(155, 168, 187), Color.rgb(214, 220, 229)),
            null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(2f, 2f, 273f, 114f), 8f, 8f, fillPaint)
        fillPaint.shader = null
        fillPaint.color = Color.rgb(44, 53, 68)
        canvas.drawRoundRect(RectF(14f, 17f, 261f, 31f), 4f, 4f, fillPaint)
        fillPaint.color = Color.WHITE
        fillPaint.textAlign = Paint.Align.LEFT
        fillPaint.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        fillPaint.textSize = 9f
        canvas.drawText("EQUALIZER", 31f, 12f, fillPaint)
        fillPaint.textAlign = Paint.Align.CENTER
        fillPaint.textSize = 6f
        canvas.drawText("ON", 29f, 27f, fillPaint)
        canvas.drawText("AUTO", 57f, 27f, fillPaint)
        canvas.drawText("PRESETS", 241f, 27f, fillPaint)
        fillPaint.color = Color.rgb(230, 234, 240)
        canvas.drawRect(43f, 36f, 77f, 102f, fillPaint)
        fillPaint.color = Color.rgb(82, 91, 105)
        canvas.drawRect(46f, 67f, 76f, 68f, fillPaint)
    }

    private fun drawFallbackTitleBar(canvas: Canvas) {
        canvas.drawColor(Color.rgb(40, 47, 58))
        fillPaint.color = Color.rgb(44, 53, 68)
        canvas.drawRect(0f, 0f, NATIVE_WIDTH.toFloat(), SHADED_HEIGHT.toFloat(), fillPaint)
        fillPaint.color = Color.WHITE
        fillPaint.textAlign = Paint.Align.LEFT
        fillPaint.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        fillPaint.textSize = 8f
        canvas.drawText("EQUALIZER", 8f, 11f, fillPaint)
    }

    private fun drawPowerButton(canvas: Canvas) {
        fillPaint.shader = android.graphics.LinearGradient(
            0f, 18f, 0f, 30f,
            if (enabled) intArrayOf(Color.rgb(228, 243, 255), Color.rgb(55, 119, 194))
            else intArrayOf(Color.rgb(233, 235, 237), Color.rgb(116, 122, 130)),
            null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(16f, 18f, 43f, 29f), 5f, 5f, fillPaint)
        fillPaint.shader = null
        fillPaint.color = Color.rgb(22, 31, 44)
        fillPaint.textAlign = Paint.Align.CENTER
        fillPaint.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        fillPaint.textSize = 7f
        canvas.drawText("ON", 29.5f, 26f, fillPaint)
    }

    private fun drawPowerButton(canvas: Canvas, skinBitmap: android.graphics.Bitmap?) {
        if (skinBitmap == null || skinBitmap.width < 128 || skinBitmap.height < 132) {
            drawPowerButton(canvas)
            return
        }
        // eqmain.bmp uses exact sprite cells: ON off x=10..35, on x=69..94;
        // AUTO off x=36..67. Keep their native 26x12 / 32x12 dimensions.
        val onSource = if (enabled) Rect(69, 119, 95, 131) else Rect(10, 119, 36, 131)
        // The top faceplate already contains the default ON/AUTO buttons at these
        // positions. Draw state sprites over those exact pixels so no baked blue
        // edges peek out when the selected state differs.
        canvas.drawBitmap(skinBitmap, onSource, RectF(14f, 18f, 40f, 30f), backgroundPaint)
        // AUTO processing is not implemented; keep the skin's native unselected button state.
        canvas.drawBitmap(skinBitmap, Rect(36, 119, 68, 131), RectF(40f, 18f, 72f, 30f), backgroundPaint)
    }

    private fun cleanedFaceplate(source: android.graphics.Bitmap): android.graphics.Bitmap {
        if (cleanedFaceplateSource === source) return requireNotNull(cleanedFaceplate)
        cleanedFaceplateSource = source
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val centers = listOf(27) + WINAMP_EQ_FREQUENCIES_HZ.indices.map { slotX(it).toInt() }
        val cleanPixels = eraseBakedEqualizerThumbs(source.width, source.height, pixels, centers)
        cleanedFaceplate = android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888).also {
            it.setPixels(cleanPixels, 0, source.width, 0, 0, source.width, source.height)
        }
        return requireNotNull(cleanedFaceplate)
    }

    private fun drawBand(canvas: Canvas, x: Float, level: Int, low: Int, high: Int) {
        val midpoint = if (high == low) 0f else (level - low).toFloat() / (high - low)
        val y = 99f - midpoint.coerceIn(0f, 1f) * 63f
        drawKnob(canvas, x, y)
    }

    private fun drawKnob(canvas: Canvas, x: Float, y: Float) {
        knobPaint.shader = android.graphics.LinearGradient(
            x, y - 4.5f, x, y + 4.5f,
            intArrayOf(Color.rgb(250, 252, 254), Color.rgb(168, 180, 194), Color.rgb(226, 232, 239)),
            null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(x - 5.5f, y - 4.5f, x + 5.5f, y + 4.5f), 4.5f, 4.5f, knobPaint)
        knobPaint.shader = null
        fillPaint.color = Color.rgb(109, 120, 133)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 0.55f
        canvas.drawRoundRect(RectF(x - 5.5f, y - 4.5f, x + 5.5f, y + 4.5f), 4.5f, 4.5f, fillPaint)
        fillPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val value = snapshot ?: return false
        val x = event.x * NATIVE_WIDTH / max(1, width)
        val nativeHeight = if (shaded) SHADED_HEIGHT else NATIVE_HEIGHT
        val y = event.y * nativeHeight / max(1, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y in 0f..14f && x in 258f..275f) {
                    onCloseRequested?.invoke()
                    return true
                }
                if (y in 0f..SHADED_HEIGHT.toFloat()) {
                    shaded = !shaded
                    requestLayout()
                    invalidate()
                    return true
                }
                if (y in 14f..31f && x in 14f..40f) {
                    enabled = !enabled
                    onEnabledChanged?.invoke(enabled)
                    invalidate()
                    return true
                }
                if (y in 14f..31f && x in 215f..270f) {
                    onPresetsRequested?.invoke()
                    return true
                }
                if (y !in 31f..106f) return true
                draggingBand = nearestBand(x, displayBands.size)
                if (draggingBand >= 0 && displayBands.getOrNull(draggingBand)?.supported == true) {
                    updateBandFromTouch(y, value)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (draggingBand >= 0) {
                updateBandFromTouch(y, value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingBand >= 0) {
                draggingBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun nearestBand(x: Float, count: Int): Int {
        if (count == 0 || x < BAND_LEFT - 7 || x > BAND_RIGHT + 7) return -1
        return WINAMP_EQ_FREQUENCIES_HZ.indices.minByOrNull { index -> abs(slotX(index) - x) } ?: -1
    }

    /** Distribute all Winamp controls evenly across the skin's usable slider width. */
    private fun slotX(index: Int): Float {
        val gaps = (WINAMP_EQ_FREQUENCIES_HZ.size - 1).coerceAtLeast(1)
        val spacing = (BAND_RIGHT - BAND_LEFT) / gaps
        return BAND_LEFT + spacing * index
    }

    private fun updateBandFromTouch(y: Float, value: AudioEqualizerSnapshot) {
        val displayBand = displayBands.getOrNull(draggingBand) ?: return
        val effectBandIndex = displayBand.effectBandIndex ?: return
        val low = value.minLevelMillibels.toInt()
        val high = value.maxLevelMillibels.toInt()
        if (high <= low) return
        val fraction = (1f - ((y - 36f) / 65f)).coerceIn(0f, 1f)
        val level = (low + (high - low) * fraction).toInt().toShort()
        onBandChanged?.invoke(effectBandIndex, level)
        val bands = value.bands.toMutableList()
        bands[effectBandIndex] = bands[effectBandIndex].copy(levelMillibels = level)
        snapshot = value.copy(bands = bands).also { displayBands = mapToWinampEqualizerSlots(it.bands) }
        invalidate()
    }

    companion object {
        private const val NATIVE_WIDTH = 275
        private const val NATIVE_HEIGHT = 116
        private const val SHADED_HEIGHT = 16
        private const val BAND_LEFT = 84f
        private const val BAND_RIGHT = 246f
    }
}
