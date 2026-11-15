package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.infinifolder.theme.ThemeManager

/**
 * Authentic Playlist Window Vertical Border View (Left or Right).
 * Native dimension is 25 x 29 pixels from PLEDIT.BMP, dynamically scaled
 * to match the 275-width skin grid and tiled vertically along the playlist window.
 */
class WinampPlaylistBorderView @JvmOverloads constructor(
    context: Context,
    val isLeft: Boolean = true,
    private val playlistScrollView: ScrollView? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_WIDTH = 25
        const val NATIVE_HEIGHT = 29
        const val BASE_GRID_WIDTH = 275f
    }

    private val smoothPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }

    private val fallbackBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = false
    }

    private val srcRect = Rect()
    private val dstRect = Rect()
    private val scrollSrcRect = Rect()
    private val scrollDstRect = Rect()
    private var draggingThumb = false
    private var dragOffset = 0f
    private var thumbTop = 0f
    private var thumbHeight = 0f
    private var maxScroll = 0
    private var scrollSprites: ThemeManager.PlaylistScrollbarSprites? = null

    init {
        isClickable = playlistScrollView != null
        isFocusable = playlistScrollView != null
        contentDescription = "Playlist scrollbar"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val parentW = if (widthMode == MeasureSpec.AT_MOST && widthSize > 0) widthSize
            else (parent as? View)?.width.takeIf { it != null && it > 0 } ?: screenW

        val borderW = if (widthMode == MeasureSpec.EXACTLY && widthSize > 0) {
            widthSize
        } else {
            ((NATIVE_WIDTH.toFloat() / BASE_GRID_WIDTH) * parentW).toInt().coerceAtLeast(1)
        }
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(borderW, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothPaint.isFilterBitmap = ThemeManager.smoothSkinScaling
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val tile = if (isLeft) ThemeManager.getPlaylistLeftBorderTile() else ThemeManager.getPlaylistRightBorderTile()
        if (tile != null) {
            val tileW = tile.width
            val tileH = tile.height
            val scaledTileH = ((tileH.toFloat() / tileW.toFloat()) * w).toInt().coerceAtLeast(1)

            var y = 0
            while (y < h) {
                val curH = minOf(scaledTileH, h - y)
                val srcH = ((curH.toFloat() / scaledTileH.toFloat()) * tileH).toInt().coerceAtLeast(1)
                srcRect.set(0, 0, tileW, srcH)
                dstRect.set(0, y, w, y + curH)
                canvas.drawBitmap(tile, srcRect, dstRect, smoothPaint)
                y += curH
            }
            if (!isLeft) drawScrollbarThumb(canvas, w, h)
        } else {
            val theme = ThemeManager.getAudioPlayerTheme(context)
            canvas.drawColor(theme.cardBackground)
            fallbackBorderPaint.color = theme.cardStroke
            val x = if (isLeft) w.toFloat() - 1f else 1f
            canvas.drawLine(x, 0f, x, h.toFloat(), fallbackBorderPaint)
        }
    }

    private fun drawScrollbarThumb(canvas: Canvas, w: Int, h: Int) {
        val scroll = playlistScrollView ?: return
        val contentHeight = scroll.getChildAt(0)?.height ?: return
        val viewportHeight = scroll.height
        maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
        if (maxScroll <= 0 || h <= 0) return
        val sprites = ThemeManager.getPlaylistScrollbarSprites() ?: return
        scrollSprites = sprites
        smoothPaint.isFilterBitmap = ThemeManager.smoothSkinScaling

        // The PLEDIT right rail is x=26..51. Its scrollbar groove occupies
        // x=36..44, so place the dedicated thumb over that groove.
        val scale = w.toFloat() / NATIVE_WIDTH
        val thumbWidth = sprites.thumb.width * scale
        val trackHeight = h.toFloat()
        val minimumThumbHeight = sprites.thumb.height * scale
        val geometry = PlaylistScrollHandleGeometry.thumb(
            trackHeight, viewportHeight.toFloat(), contentHeight.toFloat(), scroll.scrollY, minimumThumbHeight)
            ?: return
        thumbHeight = geometry.height
        thumbTop = geometry.top
        val left = 10f * scale
        val right = left + thumbWidth
        val sprite = if (draggingThumb) sprites.pressedThumb else sprites.thumb
        scrollSrcRect.set(0, 0, sprite.width, sprite.height)
        scrollDstRect.set(left.toInt(), thumbTop.toInt(), right.toInt(), (thumbTop + thumbHeight).toInt())
        canvas.drawBitmap(sprite, scrollSrcRect, scrollDstRect, smoothPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scroll = playlistScrollView ?: return false
        val contentHeight = scroll.getChildAt(0)?.height ?: 0
        val viewportHeight = scroll.height
        maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
        if (maxScroll <= 0 || height <= 0) return false
        val scale = width.toFloat() / NATIVE_WIDTH
        val sprites = scrollSprites ?: ThemeManager.getPlaylistScrollbarSprites() ?: return false
        val minimumThumbHeight = sprites.thumb.height * scale
        val geometry = PlaylistScrollHandleGeometry.thumb(
            height.toFloat(), viewportHeight.toFloat(), contentHeight.toFloat(), scroll.scrollY,
            minimumThumbHeight) ?: return false
        thumbHeight = geometry.height
        thumbTop = geometry.top
        val travel = (height - thumbHeight).coerceAtLeast(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingThumb = true
                dragOffset = if (event.y in thumbTop..(thumbTop + thumbHeight)) event.y - thumbTop
                    else thumbHeight / 2f
                parent?.requestDisallowInterceptTouchEvent(true)
                updateScroll(event.y, scroll, travel)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (draggingThumb) {
                updateScroll(event.y, scroll, travel)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingThumb) {
                draggingThumb = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return false
    }

    private fun updateScroll(y: Float, scroll: ScrollView, travel: Float) {
        val targetTop = (y - dragOffset).coerceIn(0f, travel)
        scroll.scrollTo(0, PlaylistScrollHandleGeometry.scrollYForThumbTop(
            targetTop, height.toFloat(), thumbHeight, scroll.height.toFloat(),
            (scroll.getChildAt(0)?.height ?: 0).toFloat()))
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
