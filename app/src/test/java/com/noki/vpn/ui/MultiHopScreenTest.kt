package com.noki.vpn.ui

import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.VpnServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiHopScreenTest {
    private val nodes = listOf(
        ServerLocation("ru", "RU", "Russia", "Moscow", "", isOnline = true, servers = listOf(
            VpnServer("a", "A", "RU", "ru", "", null, true,
                multiHopEntryAvailable = true, multiHopExitAvailable = true),
        )),
        ServerLocation("lv", "LV", "Latvia", "Riga", "", isOnline = true, servers = listOf(
            VpnServer("b", "B", "LV", "lv", "", null, true,
                multiHopEntryAvailable = true, multiHopExitAvailable = true),
        )),
    )

    @Test fun countryAndServerPairMustResolveToDifferentAvailableNodes() {
        assertNull(multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "b")), nodes))
        assertEquals("same_node", multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU")), nodes))
        assertEquals("exit_unavailable", multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "offline")), nodes))
    }
}
