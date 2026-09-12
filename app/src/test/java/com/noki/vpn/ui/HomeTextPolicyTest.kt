package com.noki.vpn.ui

import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTextPolicyTest {
    @Test
    fun `duration formatter preserves minute hour and day forms`() {
        assertEquals("00:00", formatConnectionDuration(-1L))
        assertEquals("01:02", formatConnectionDuration(62_000L))
        assertEquals("01:01:01", formatConnectionDuration(3_661_000L))
        assertEquals("01:01:01:01", formatConnectionDuration(90_061_000L))
    }

    @Test
    fun `country marker accepts only normalized two-letter country code`() {
        assertEquals("LV", countryMarkerCode(location(countryCode = " lv ")))
        assertNull(countryMarkerCode(location(countryCode = "LVA")))
        assertNull(countryMarkerCode(location(countryCode = "1v")))
    }

    @Test
    fun `country flag resource names support arbitrary ISO alpha two countries`() {
        mapOf(
            "lv" to "flag_lv",
            "FR" to "flag_fr",
            "jp" to "flag_jp",
            "BR" to "flag_br",
            "za" to "flag_za",
        ).forEach { (code, expectedResourceName) ->
            assertEquals(expectedResourceName, countryFlagResourceName(location(countryCode = code)))
        }
        assertNull(countryFlagResourceName(location(countryCode = "LVA")))
        assertNull(countryFlagResourceName(location(countryCode = "1v")))
    }

    @Test
    fun `server menu keeps source order and code identity`() {
        val first = location(code = "lv")
        val second = location(code = "de")

        val menu = serverMenuLocations(listOf(first, second))

        assertEquals(listOf("lv", "de"), menu.map { it.key })
        assertEquals(listOf(first, second), menu.map { it.location })
    }

    @Test
    fun `home location resolves selected node when country and location codes differ`() {
        val selectedServer = VpnServer(
            id = "lv4",
            name = "Latvia 4",
            countryCode = "LV",
            locationCode = "lv4",
            host = "lv4.example",
            probePort = 443,
            isOnline = true,
        )
        val location = location(code = "lv4").copy(servers = listOf(selectedServer))
        val profile = UserProfile(
            serverSelectionMode = ServerSelectionMode.SERVER,
            selectedCountryCode = "LV",
            selectedNodeId = "lv4",
        )

        assertEquals(location, homeSelectedLocation(listOf(location), profile))
    }

    @Test
    fun `metrics expose traffic only while connected but keep server health`() {
        val server = location(latencyMs = 24, loadPercent = 31)
        val traffic = HomeDeviceTrafficSnapshot(downloadMbps = "2.5", uploadMbps = "1", sessionBytes = 1_048_576L)

        assertEquals(
            HomeMetricsSnapshot(download = null, upload = null, latency = "24", sessionBytes = null),
            currentMetrics(server, VpnConnectionState.CONNECTING, traffic),
        )
        assertEquals(
            HomeMetricsSnapshot(download = "2.5", upload = "1", latency = "39", sessionBytes = 1_048_576L),
            currentMetrics(server, VpnConnectionState.CONNECTED, traffic, activeLatencyMs = 39),
        )
        assertNull(currentMetrics(server, VpnConnectionState.CONNECTED, traffic).latency)
    }

    private fun location(
        code: String = "lv",
        countryCode: String = "LV",
        latencyMs: Int? = null,
        loadPercent: Int? = null,
    ) = ServerLocation(
        code = code,
        countryCode = countryCode,
        country = "Latvia",
        city = "Riga",
        host = "vpn.example",
        latencyMs = latencyMs,
        loadPercent = loadPercent,
        isOnline = true,
    )
}
