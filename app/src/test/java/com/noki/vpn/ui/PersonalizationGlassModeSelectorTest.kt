package com.noki.vpn.ui

import androidx.compose.ui.graphics.Color
import com.noki.vpn.data.GlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationGlassModeSelectorTest {
    @Test
    fun `full glass toggle maps to the two remaining modes`() {
        assertEquals(listOf(GlassMode.SIMPLE, GlassMode.FULL), GlassMode.entries)
        assertFalse(fullGlassEnabled(GlassMode.SIMPLE))
        assertTrue(fullGlassEnabled(GlassMode.FULL))
        assertEquals(GlassMode.SIMPLE, glassModeForFullGlassEnabled(false))
        assertEquals(GlassMode.FULL, glassModeForFullGlassEnabled(true))
    }

    @Test
    fun `glass modes map to rendering and transition policy`() {
        assertFalse(GlassMode.SIMPLE.liveGlassEnabled)
        assertTrue(GlassMode.SIMPLE.simpleTransitions)
        assertTrue(GlassMode.FULL.liveGlassEnabled)
        assertFalse(GlassMode.FULL.simpleTransitions)
    }

    @Test
    fun `simple mode hides connected Aurora`() {
        assertFalse(shouldShowConnectedAurora(liveGlassEnabled = false, connected = true))
        assertTrue(shouldShowConnectedAurora(liveGlassEnabled = true, connected = true))
        assertFalse(shouldShowConnectedAurora(liveGlassEnabled = true, connected = false))
    }

    @Test
    fun `simple mode uses the flat original graphite background`() {
        assertFalse(shouldDrawHomeBackgroundEffects(liveGlassEnabled = false))
        assertEquals(Color(0xFF080B10), homeBackgroundColor(liveGlassEnabled = false))
        assertTrue(shouldDrawHomeBackgroundEffects(liveGlassEnabled = true))
        assertEquals(HomeBgBase, homeBackgroundColor(liveGlassEnabled = true))
    }

    @Test
    fun `simple mode makes glass surface fills opaque without changing rgb`() {
        val surface = Color(0x66335577)

        assertEquals(surface, glassSurfaceColor(surface, liveGlassEnabled = true))
        assertEquals(
            surface.copy(alpha = 1f),
            glassSurfaceColor(surface, liveGlassEnabled = false),
        )
    }

    @Test
    fun `simple mode can use a dedicated opaque surface color`() {
        val glassSurface = Color(0x66335577)
        val simpleSurface = Color(0x8899AABB)

        assertEquals(
            glassSurface,
            glassSurfaceColor(glassSurface, liveGlassEnabled = true, simpleColor = simpleSurface),
        )
        assertEquals(
            simpleSurface.copy(alpha = 1f),
            glassSurfaceColor(glassSurface, liveGlassEnabled = false, simpleColor = simpleSurface),
        )
    }
}
