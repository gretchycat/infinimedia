package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistVisualTimingTest {
    @Test fun visualImageChangesUseOneSecondCrossfades() {
        assertEquals(1_000L, PlaylistVisualTiming.IMAGE_CROSSFADE_DURATION_MS)
    }
}
