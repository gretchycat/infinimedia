package com.infinifolder.viewer

/** Number of skinned player columns for the device's smallest screen width and orientation. */
internal fun audioPlayerColumnCount(smallestWidthDp: Int, landscape: Boolean): Int {
    val tablet = smallestWidthDp >= 600
    return when {
        landscape -> 2
        tablet -> 2
        else -> 1
    }
}
