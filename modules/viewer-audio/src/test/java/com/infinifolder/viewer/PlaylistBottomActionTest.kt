package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistBottomActionTest {
    @Test fun bottomBarActionsMapToFourControlsAndTheSaveIcon() {
        assertEquals(PlaylistBottomAction.ADD, playlistBottomActionAt(22f))
        assertEquals(PlaylistBottomAction.REMOVE, playlistBottomActionAt(53f))
        assertEquals(PlaylistBottomAction.SELECT, playlistBottomActionAt(83f))
        assertEquals(PlaylistBottomAction.LIST_OPTIONS, playlistBottomActionAt(112f))
        assertEquals(PlaylistBottomAction.SAVE, playlistBottomActionAt(243f))
        assertNull(playlistBottomActionAt(140f))
    }

    @Test fun playlistScrollThumbTracksAndMapsToScrollRange() {
        val thumb = PlaylistScrollHandleGeometry.thumb(200f, 400f, 1000f, 300, 28f)!!
        assertEquals(80f, thumb.height, 0.01f)
        assertEquals(60f, thumb.top, 0.01f)
        assertEquals(300, PlaylistScrollHandleGeometry.scrollYForThumbTop(
            thumb.top, 200f, thumb.height, 400f, 1000f))
    }

    @Test fun playlistScrollThumbFillsTrackWhenContentFits() {
        assertNull(PlaylistScrollHandleGeometry.thumb(200f, 200f, 180f, 0, 28f))
    }
}
