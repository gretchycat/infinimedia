package com.infinifolder.viewer

/** Sprite coordinates are in the original Winamp skin grid; destinations are device pixels. */
internal object VisualFrameGeometry {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Patch(val sheet: String, val source: Box, val destination: Box, val mirror: Boolean = false)
    data class Insets(val title: Int, val side: Int, val bottom: Int)

    fun insets(width: Int) = Insets(
        (width * 20 / 275).coerceAtLeast(1),
        (width * 7 / 275).coerceAtLeast(1),
        (width * 14 / 275).coerceAtLeast(1)
    )

    fun patches(width: Int, height: Int, shaded: Boolean): List<Patch> {
        if (width <= 0 || height <= 0) return emptyList()
        val inset = insets(width)
        val title = inset.title.coerceAtMost(height)
        fun x(native: Int) = (native * width / 275).coerceAtMost(width)
        val left = x(25)
        val right = width - x(25)
        val centerLeft = (width - x(50)) / 2
        val centerRight = centerLeft + x(50)
        return buildList {
            fun patch(sheet: String, source: Box, destination: Box, mirror: Boolean = false) {
                if (destination.right > destination.left && destination.bottom > destination.top) add(Patch(sheet, source, destination, mirror))
            }
            patch("video", Box(0, 0, 25, 20), Box(0, 0, left, title))
            patch("video", Box(26, 0, 49, 20), Box(left, 0, centerLeft, title))
            patch("video", Box(50, 0, 100, 20), Box(centerLeft, 0, centerRight, title))
            patch("video", Box(101, 0, 124, 20), Box(centerRight, 0, right, title))
            patch("video", Box(153, 0, 178, 20), Box(right, 0, width, title))
            if (!shaded && height > title) {
                val side = inset.side.coerceAtMost(width / 2)
                val bottom = inset.bottom.coerceAtMost(height - title)
                val y = height - bottom
                patch("gen", Box(127, 42, 134, 70), Box(0, title, side, y))
                patch("gen", Box(141, 42, 148, 70), Box(width - side, title, width, y))
                // GEN's lower-right corner also supplies the mirrored lower-left corner.
                patch("gen", Box(118, 55, 125, 69), Box(0, y, side, height), mirror = true)
                patch("gen", Box(0, 55, 118, 69), Box(side, y, width - side, height))
                patch("gen", Box(118, 55, 125, 69), Box(width - side, y, width, height))
            }
        }
    }
}
