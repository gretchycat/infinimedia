package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.infinifolder.theme.ThemeManager

/** Persistent, touch-draggable scrollbar for the playlist viewport. */
internal class PlaylistScrollHandleView(
    context: Context,
    private val scrollView: ScrollView
) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val thumb = RectF()
    private val inset = 3f * density
    private val thumbWidth = 6f * density
    private val minThumb = 28f * density
    private var dragging = false
    private var dragOffset = 0f
    private var thumbHeight = 0f
    private var contentHeight = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Playlist scroll handle"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewport = scrollView.height.toFloat()
        contentHeight = (scrollView.getChildAt(0)?.height ?: 0).toFloat()
        val geometry = PlaylistScrollHandleGeometry.thumb(
            height - 2f * inset, viewport, contentHeight, scrollView.scrollY, minThumb)
        if (geometry == null) return

        val centerX = width / 2f
        trackPaint.color = ThemeManager.getAudioPlayerTheme(context).cardStroke
        thumbPaint.color = ThemeManager.getAudioPlayerTheme(context).textPrimary
        track.set(centerX - 1f * density, inset, centerX + 1f * density, height - inset)
        thumbHeight = geometry.height
        val top = inset + geometry.top
        thumb.set(centerX - thumbWidth / 2f, top, centerX + thumbWidth / 2f, top + thumbHeight)
        canvas.drawRoundRect(track, track.width() / 2f, track.width() / 2f, trackPaint)
        canvas.drawRoundRect(thumb, thumb.width() / 2f, thumb.width() / 2f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val trackHeight = height - 2f * inset
        val viewport = scrollView.height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val geometry = PlaylistScrollHandleGeometry.thumb(trackHeight, viewport,
                    contentHeight, scrollView.scrollY, minThumb) ?: return false
                thumbHeight = geometry.height
                val currentTop = inset + geometry.top
                dragOffset = if (event.y in currentTop..(currentTop + thumbHeight)) event.y - currentTop
                    else thumbHeight / 2f
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                moveThumb(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                moveThumb(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return false
    }

    private fun moveThumb(y: Float) {
        val trackHeight = height - 2f * inset
        val thumbTop = (y - inset - dragOffset).coerceIn(0f, (trackHeight - thumbHeight).coerceAtLeast(0f))
        val target = PlaylistScrollHandleGeometry.scrollYForThumbTop(
            thumbTop, trackHeight, thumbHeight, scrollView.height.toFloat(), contentHeight)
        scrollView.scrollTo(0, target)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
