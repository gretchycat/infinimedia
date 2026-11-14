package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerPaletteTest {
    @Test fun usesGreenYellowRedMeterZonesForEveryBar() {
        assertEquals(0xFF4CAF50.toInt(), visualizerColorForHeight(0.69f))
        assertEquals(0xFFFFEB3B.toInt(), visualizerColorForHeight(0.70f))
        assertEquals(0xFFFFEB3B.toInt(), visualizerColorForHeight(0.89f))
        assertEquals(0xFFF44336.toInt(), visualizerColorForHeight(0.90f))
        assertEquals(0xFFF44336.toInt(), visualizerColorForHeight(1.0f))
    }
}
