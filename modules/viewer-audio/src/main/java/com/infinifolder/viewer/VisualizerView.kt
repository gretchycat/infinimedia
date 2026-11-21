package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.infinifolder.theme.ThemeColors
import kotlin.math.max

/**
 * Lightweight, high-performance spectrum analyzer & oscilloscope visualizer.
 * Supports classic 16-band Winamp segmented bars with peak falloff, WMP glow bars,
 * and customizable color maps from active skins.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 16
    private val barHeights = FloatArray(barCount)
    private val barTargets = FloatArray(barCount)
    private val peakHeights = FloatArray(barCount)
    private val peakSpeed = FloatArray(barCount)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakColor: Int = Color.WHITE

    var isPlaying: Boolean = false
        set(value) {
            field = value
            if (value || minimumDisplayLevel > 0f) postInvalidateOnAnimation()
        }

    /** Optional visible floor for a host fallback while a GPU visualizer starts or recovers. */
    var minimumDisplayLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    fun updateData(newBands: FloatArray, newPeaks: FloatArray? = null) {
        val count = minOf(barCount, newBands.size)
        for (i in 0 until count) {
            barTargets[i] = newBands[i]
            if (newPeaks != null && i < newPeaks.size) {
                peakHeights[i] = newPeaks[i] * height
            }
        }
        invalidate()
    }

    fun applyTheme(@Suppress("UNUSED_PARAMETER") theme: ThemeColors) = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val spacing = (w * 0.02f).coerceAtLeast(2f)
        val barWidth = (w - spacing * (barCount + 1)) / barCount

        // Update animation values
        for (i in 0 until barCount) {
            val targetH = max(if (isPlaying) barTargets[i] else 0f, minimumDisplayLevel) * h
            // Smooth interpolation
            barHeights[i] += (targetH - barHeights[i]) * 0.35f

            // Peak calculation with simulated gravity
            if (barHeights[i] >= peakHeights[i]) {
                peakHeights[i] = barHeights[i]
                peakSpeed[i] = 0f
            } else {
                peakSpeed[i] += 0.8f // Gravity
                peakHeights[i] = (peakHeights[i] - peakSpeed[i]).coerceAtLeast(0f)
            }
        }

        // Draw segmented bars
        for (i in 0 until barCount) {
            val left = spacing + i * (barWidth + spacing)
            val right = left + barWidth
            val barH = barHeights[i].coerceIn(0f, h)

            // Draw segmented blocks (Winamp classic style)
            val segmentH = 4f
            val segmentGap = 2f
            var curY = h - 2f
            while (curY > h - barH && curY > 0) {
                val blockTop = (curY - segmentH).coerceAtLeast(0f)
                val levelHeight = ((h - (blockTop + curY) * 0.5f) / h).coerceIn(0f, 1f)
                barPaint.color = visualizerColorForHeight(levelHeight)
                canvas.drawRect(left, blockTop, right, curY, barPaint)
                curY -= (segmentH + segmentGap)
            }

            // Draw floating peak dot
            val peakY = h - peakHeights[i]
            if (peakY < h - 4f) {
                peakPaint.color = peakColor
                canvas.drawRect(left, peakY - 3f, right, peakY, peakPaint)
            }
        }

        if (isPlaying || minimumDisplayLevel > 0f || barHeights.any { it > 1f }) {
            postInvalidateOnAnimation()
        }
    }
}
