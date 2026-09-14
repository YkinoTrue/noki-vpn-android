package com.noki.vpn.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncyDeviceUiContractTest {
    private val source = File("src/main/java/com/noki/vpn/ui/IncyDeviceComponents.kt").readText()

    @Test
    fun `incy import link is copy only on Android`() {
        assertTrue(source.contains("copyIncy(context, it)"))
        assertTrue(source.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
        assertTrue(source.contains("INCY_CLIP_OWNER_MARKER_KEY"))
        assertTrue(source.contains("INCY_CLIPBOARD_CLEAR_DELAY_MILLIS"))
        assertTrue(source.contains("clipboard.clearPrimaryClip()"))
        assertTrue(source.contains("override fun onResume"))
        assertFalse(source.contains("openIncy"))
        assertFalse(source.contains("Intent.ACTION_VIEW"))
        assertFalse(source.contains("\"Открыть\""))
        assertFalse(source.contains("\"Open\""))
    }

    @Test
    fun `incy clipboard cleanup targets only the unchanged app clip`() {
        val link = "incy://crypt1/abc"
        val marker = "owned-clip"

        assertTrue(shouldClearIncyClipboard(link, marker, 1, "INCY", link, marker))
        assertFalse(shouldClearIncyClipboard(link, marker, 1, "INCY", link, "replacement"))
        assertFalse(shouldClearIncyClipboard(link, marker, 1, "INCY", "new clipboard value", marker))
        assertFalse(shouldClearIncyClipboard(link, marker, 1, "another app", link, marker))
        assertFalse(shouldClearIncyClipboard(link, marker, 2, "INCY", link, marker))
    }

}
