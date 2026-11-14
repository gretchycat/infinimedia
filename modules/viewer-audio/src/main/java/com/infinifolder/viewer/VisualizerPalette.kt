package com.infinifolder.viewer

/** Standard level-meter ranges, measured upward from the bottom of a spectrum bar. */
internal fun visualizerColorForHeight(heightRatio: Float): Int = when {
    heightRatio >= 0.90f -> 0xFFF44336.toInt() // red: top 10%
    heightRatio >= 0.70f -> 0xFFFFEB3B.toInt() // yellow: next 20%
    else -> 0xFF4CAF50.toInt() // green: lower 70%
}
