package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistBottomActionTest {
    @Test fun fourthPleEditButtonOpensListOptions() {
        assertEquals(PlaylistBottomAction.ADD, playlistBottomActionAt(22f))
        assertEquals(PlaylistBottomAction.REMOVE, playlistBottomActionAt(53f))
        assertEquals(PlaylistBottomAction.SELECT, playlistBottomActionAt(83f))
        assertEquals(PlaylistBottomAction.LIST_OPTIONS, playlistBottomActionAt(112f))
        assertNull(playlistBottomActionAt(140f))
    }
}
