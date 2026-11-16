package com.infinifolder.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface

/** Returns the orientation to restore after temporary sensor-landscape fullscreen playback. */
internal fun windowedOrientationRequest(
    configurationOrientation: Int,
    displayRotation: Int,
    requestedOrientation: Int,
): Int {
    if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
        return requestedOrientation
    }

    val reverse = displayRotation == Surface.ROTATION_180 || displayRotation == Surface.ROTATION_270
    return when (configurationOrientation) {
        Configuration.ORIENTATION_PORTRAIT -> if (reverse) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        Configuration.ORIENTATION_LANDSCAPE -> if (reverse) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        else -> requestedOrientation
    }
}
