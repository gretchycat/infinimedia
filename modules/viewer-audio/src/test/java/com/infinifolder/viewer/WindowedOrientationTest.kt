package com.infinifolder.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowedOrientationTest {
    @Test fun restoresPortraitFacingBeforeFullscreen() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            windowedOrientationRequest(
                Configuration.ORIENTATION_PORTRAIT,
                Surface.ROTATION_0,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            windowedOrientationRequest(
                Configuration.ORIENTATION_PORTRAIT,
                Surface.ROTATION_180,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
        )
    }

    @Test fun restoresEitherLandscapeFacingBeforeFullscreen() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            windowedOrientationRequest(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_90,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            windowedOrientationRequest(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_270,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
        )
    }

    @Test fun preservesAnExplicitWindowedOrientationPolicy() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            windowedOrientationRequest(
                Configuration.ORIENTATION_PORTRAIT,
                Surface.ROTATION_0,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ),
        )
    }

    @Test fun preservesUnspecifiedPolicyWhenTheWindowedOrientationIsUnknown() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            windowedOrientationRequest(
                Configuration.ORIENTATION_UNDEFINED,
                Surface.ROTATION_0,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ),
        )
    }
}
