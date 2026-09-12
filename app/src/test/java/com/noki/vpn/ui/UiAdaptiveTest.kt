package com.noki.vpn.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class UiAdaptiveTest {
    @Test
    fun widePhoneUsesAvailableWidthInsteadOfNarrowFixedColumn() {
        val metrics = nokiAdaptiveMetrics(512.dp)
        assertEquals(470.dp, metrics.contentWidth)
        assertEquals(21.dp, metrics.contentStart)
        assertEquals(470f, metrics.dp(370f).value, 0.001f)
    }

    @Test
    fun ordinaryPhoneKeepsItsExistingGeometry() {
        val metrics = nokiAdaptiveMetrics(412.dp)
        assertEquals(370.dp, metrics.contentWidth)
        assertEquals(1f, metrics.contentScale)
    }

    @Test
    fun tabletKeepsBoundedCenteredContent() {
        val metrics = nokiAdaptiveMetrics(800.dp)
        assertEquals(480.dp, metrics.contentWidth)
        assertEquals(160.dp, metrics.contentStart)
    }
}
