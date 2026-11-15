package com.infinifolder.viewer

/** Geometry shared by the draggable playlist thumb and its unit tests. */
internal object PlaylistScrollHandleGeometry {
    data class Thumb(val top: Float, val height: Float)

    fun thumb(trackHeight: Float, viewportHeight: Float, contentHeight: Float, scrollY: Int,
        minThumbHeight: Float): Thumb? {
        if (trackHeight <= 0f || viewportHeight <= 0f || contentHeight <= viewportHeight) return null
        val height = (trackHeight * viewportHeight / contentHeight).coerceIn(
            minThumbHeight.coerceAtMost(trackHeight), trackHeight)
        val scrollRange = contentHeight - viewportHeight
        val top = (trackHeight - height) * scrollY.coerceIn(0, scrollRange.toInt()).toFloat() / scrollRange
        return Thumb(top, height)
    }

    fun scrollYForThumbTop(top: Float, trackHeight: Float, thumbHeight: Float,
        viewportHeight: Float, contentHeight: Float): Int {
        val scrollRange = (contentHeight - viewportHeight).coerceAtLeast(0f)
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        if (scrollRange == 0f || travel == 0f) return 0
        return ((top.coerceIn(0f, travel) / travel) * scrollRange).toInt()
    }
}
