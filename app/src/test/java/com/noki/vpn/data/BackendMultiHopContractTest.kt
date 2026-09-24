package com.noki.vpn.data

import com.noki.vpn.vpn.XrayConfigFactory
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendMultiHopContractTest {
    private fun fixture(): JSONObject {
        val path = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "contracts/multihop_session_v2.json") }
            .first { it.isFile }
        return JSONObject(path.readText())
    }

    @Test
    fun `v2 response builds one proxy through entry for every exit protocol`() {
        val session = BackendVpnSessionJsonParser.parse(fixture())
        val route = requireNotNull(session.multiHop)
        assertEquals(2, route.version)
        assertEquals(4, session.endpointCandidates.size)
        for (candidate in session.endpointCandidates) {
            val profile = EndpointSelector.profileFromCandidate(session, candidate)
            val settings = AdvancedSettings(multiHop = route.selection, youtubeDirectDpiEnabled = true)
            assertTrue(candidate.code, VpnProfileValidator.isUsable(profile, settings))
            assertEquals(route.entryIp, profile.host)
            assertEquals(candidate.entryPort.toString(), profile.port)
            assertEquals(candidate.serverName, profile.serverName)
            assertEquals(candidate.publicKey.orEmpty(), profile.publicKey)
            val main = JSONObject(XrayConfigFactory.build(profile, settings))
            val outbounds = main.getJSONArray("outbounds")
            val proxy = (0 until outbounds.length()).map(outbounds::getJSONObject)
                .single { it.getString("tag") == "proxy" }
            val address = if (candidate.proxyType == "hysteria") proxy.getJSONObject("settings").getString("address")
                else proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address")
            assertEquals(route.entryIp, address)
            assertFalse(outbounds.toString().contains("multihop-relay"))
            assertFalse(proxy.getJSONObject("streamSettings").optJSONObject("sockopt")?.has("dialerProxy") == true)
            val youtube = (0 until outbounds.length()).map(outbounds::getJSONObject)
                .single { it.getString("tag") == "youtube-ru-cascade" }
            assertEquals("proxy", youtube.getJSONObject("streamSettings")
                .getJSONObject("sockopt").getString("dialerProxy"))
            val probe = JSONObject(XrayConfigFactory.buildProbe(profile)).getJSONArray("outbounds")
            assertEquals(1, probe.length())
        }
    }

    @Test
    fun `v1 and mismatched candidate are rejected rather than converted to direct`() {
        val legacy = fixture()
        legacy.getJSONObject("multihop").put("version", 1)
        assertTrue(runCatching { BackendVpnSessionJsonParser.parse(legacy) }.isFailure)
        val wrongExit = fixture()
        wrongExit.getJSONArray("endpoint_candidates").getJSONObject(0)
            .put("node_id", "00000000-0000-0000-0000-000000000003")
        assertTrue(runCatching { BackendVpnSessionJsonParser.parse(wrongExit) }.isFailure)
        val wrongAddress = fixture()
        wrongAddress.getJSONArray("endpoint_candidates").getJSONObject(0).put("connect_ip", "1.1.1.1")
        assertTrue(runCatching { BackendVpnSessionJsonParser.parse(wrongAddress) }.isFailure)
    }
}
