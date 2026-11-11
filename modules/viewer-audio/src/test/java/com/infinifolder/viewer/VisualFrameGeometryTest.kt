package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class VisualFrameGeometryTest {
    @Test fun frameScalesWithThePlayers275PixelGrid() {
        assertEquals(VisualFrameGeometry.Insets(20, 7, 14), VisualFrameGeometry.insets(275))
        assertEquals(VisualFrameGeometry.Insets(40, 14, 28), VisualFrameGeometry.insets(550))
        val patches = VisualFrameGeometry.patches(550, 400, false)
        assertEquals(10, patches.size)
        assertEquals(5, patches.count { it.sheet == "video" })
        assertEquals(5, patches.count { it.sheet == "gen" })
        assertEquals(VisualFrameGeometry.Box(225, 0, 325, 40), patches[2].destination)
        assertTrue(patches[7].mirror)
    }

    @Test fun edgesCoverEveryFramePixelWithoutCoveringTheContent() {
        for (width in listOf(177, 275, 413, 550, 1080)) {
            val height = width * 9 / 16 + 34 * width / 275
            val inset = VisualFrameGeometry.insets(width)
            val patches = VisualFrameGeometry.patches(width, height, false)
            for (y in 0 until height) for (x in 0 until width) {
                val count = patches.count { p -> p.destination.let { x >= it.left && x < it.right && y >= it.top && y < it.bottom } }
                val frame = y < inset.title || y >= height - inset.bottom || x < inset.side || x >= width - inset.side
                assertEquals("$width: ($x,$y)", if (frame) 1 else 0, count)
            }
            for (patch in patches) {
                assertTrue(patch.source.left >= 0 && patch.source.top >= 0)
                assertTrue(patch.source.right <= if (patch.sheet == "gen") 194 else 234)
                assertTrue(patch.source.bottom <= if (patch.sheet == "gen") 109 else 119)
            }
        }
    }

    @Test fun collapsedWindowHasOnlyTheVideoTitle() {
        val patches = VisualFrameGeometry.patches(275, 20, true)
        assertEquals(5, patches.size)
        assertTrue(patches.all { it.sheet == "video" && it.destination.bottom == 20 })
        assertTrue(VisualFrameGeometry.patches(0, 0, false).isEmpty())
    }
}
