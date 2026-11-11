package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager

/** Full visual-media window, assembled from VIDEO's title and GEN's frame sprites. */
internal class WinampVisualFrameLayout(context: Context) : LinearLayout(context) {
    val title = TextView(context).apply {
        textSize = 10f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = "Show or hide visual media"
        isClickable = true
        isFocusable = true
    }
    val body = FrameLayout(context)
    private val paint = Paint()
    private var fallbackTitle = Color.DKGRAY
    private var fallbackBorder = Color.GRAY

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        addView(title, LayoutParams(-1, 1))
        addView(body, LayoutParams(-1, -2))
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            requestLayout()
        }
    }

    fun refreshTheme(theme: ThemeColors) {
        fallbackTitle = theme.toolbarBackground
        fallbackBorder = theme.cardStroke
        title.setTextColor(theme.textPrimary)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val insets = VisualFrameGeometry.insets(available)
        title.layoutParams.height = insets.title
        title.text = if (validVideo(ThemeManager.skinBitmap("video.bmp"))) "" else "VISUAL MEDIA"
        body.setPadding(insets.side, 0, insets.side, insets.bottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.isFilterBitmap = ThemeManager.smoothSkinScaling
        drawFrame(canvas, width, height, body.visibility == View.GONE,
            ThemeManager.skinBitmap("video.bmp"), ThemeManager.skinBitmap("gen.bmp"),
            paint, fallbackTitle, fallbackBorder)
    }

    companion object {
        private fun validVideo(bitmap: Bitmap?) = bitmap != null && bitmap.width >= 178 && bitmap.height >= 20

        /** Also used by the Android raster regression probe; no View or Activity is required. */
        @JvmStatic fun drawFrame(canvas: Canvas, width: Int, height: Int, shaded: Boolean,
            video: Bitmap?, gen: Bitmap?, paint: Paint, fallbackTitle: Int, fallbackBorder: Int) {
            if (width <= 0 || height <= 0) return
            canvas.drawColor(Color.BLACK)
            val inset = VisualFrameGeometry.insets(width)
            paint.style = Paint.Style.FILL
            paint.color = fallbackTitle
            canvas.drawRect(0f, 0f, width.toFloat(), inset.title.toFloat(), paint)
            paint.color = fallbackBorder
            if (!shaded) {
                canvas.drawRect(0f, inset.title.toFloat(), inset.side.toFloat(), height.toFloat(), paint)
                canvas.drawRect((width - inset.side).toFloat(), inset.title.toFloat(), width.toFloat(), height.toFloat(), paint)
                canvas.drawRect(0f, (height - inset.bottom).toFloat(), width.toFloat(), height.toFloat(), paint)
            }
            for (patch in VisualFrameGeometry.patches(width, height, shaded)) {
                val bitmap = if (patch.sheet == "video") video else gen
                val source = patch.source
                if (bitmap == null || bitmap.width < source.right || bitmap.height < source.bottom) continue
                val destination = patch.destination
                val saved = canvas.save()
                if (patch.mirror) canvas.scale(-1f, 1f, (destination.left + destination.right) / 2f, 0f)
                canvas.drawBitmap(bitmap, Rect(source.left, source.top, source.right, source.bottom),
                    Rect(destination.left, destination.top, destination.right, destination.bottom), paint)
                canvas.restoreToCount(saved)
            }
        }
    }
}
