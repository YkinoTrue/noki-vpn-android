package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopRuntime
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.YoutubeCascadeProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigFactoryTest {
    @Test
    fun multiHopUsesOneProxyOutboundAndKeepsYoutubeCascade() {
        val intent = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "LV"))
        val exit = validProfile().copy(host = "9.9.9.9", port = "20000", security = "tls", serverName = "lv1.example.com",
            youtubeCascade = validProfile().youtubeCascade?.copy(host = "8.8.8.8"),
            multiHop = MultiHopRuntime(intent,
            "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
            "a".repeat(64)))
        val settings = AdvancedSettings(youtubeDirectDpiEnabled = true, multiHop = intent)
        val config = JSONObject(XrayConfigFactory.build(exit, settings))
        val outbounds = config.getJSONArray("outbounds")
        fun outbound(tag: String) = (0 until outbounds.length())
            .map(outbounds::getJSONObject).single { it.getString("tag") == tag }
        fun dialer(tag: String) = outbound(tag).getJSONObject("streamSettings")
            .getJSONObject("sockopt").getString("dialerProxy")
        assertEquals("9.9.9.9", outbound("proxy").getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertFalse(outbound("proxy").getJSONObject("streamSettings")
            .optJSONObject("sockopt")?.has("dialerProxy") == true)
        assertEquals("tls", outbound("proxy").getJSONObject("streamSettings").getString("security"))
        assertEquals("proxy", dialer("youtube-ru-cascade"))
        assertFalse(outbounds.toString().contains("multihop-relay"))
        val probe = JSONObject(XrayConfigFactory.buildProbe(exit)).getJSONArray("outbounds")
        assertEquals(1, probe.length())
        assertFalse(probe.getJSONObject(0).getJSONObject("streamSettings")
            .optJSONObject("sockopt")?.has("dialerProxy") == true)
    }

    @Test
    fun tunDnsAndSniffingUseLastDeviceValidatedSchema() {
        val root = JSONObject(XrayConfigFactory.build(validProfile(), AdvancedSettings()))
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        val destinationOverrides = inbound.getJSONObject("sniffing").getJSONArray("destOverride")
        val outbounds = root.getJSONArray("outbounds").toString()
        val rulesArray = root.getJSONObject("routing").getJSONArray("rules")
        val rules = rulesArray.toString()
        val dns = root.getJSONObject("dns")
        val dnsServer = dns.getJSONArray("servers").getJSONObject(0)
        val nodeDnsRule = (0 until rulesArray.length())
            .map(rulesArray::getJSONObject)
            .first { it.optJSONArray("inboundTag")?.toString()?.contains("noki-node-dns") == true }
        val tunnelDnsRule = (0 until rulesArray.length())
            .map(rulesArray::getJSONObject)
            .first { it.optString("port") == "53" }
        val secureDnsRuleIndex = (0 until rulesArray.length())
            .firstOrNull { rulesArray.getJSONObject(it).optString("port") == "853" }
            ?: -1
        val cloudflareDohRule = (0 until rulesArray.length())
            .map(rulesArray::getJSONObject)
            .first {
                it.optJSONArray("domain")
                    ?.toString()
                    ?.contains("cloudflare-dns.com") == true
            }

        assertEquals(VpnTunnelPolicy.MTU, inbound.getJSONObject("settings").getInt("MTU"))
        assertTrue(inbound.getJSONObject("sniffing").optBoolean("routeOnly"))
        assertFalse(destinationOverrides.toString().contains("quic"))
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertEquals(1, dns.getJSONArray("servers").length())
        assertEquals("tcp://198.18.0.1:53", dnsServer.getString("address"))
        assertEquals("noki-node-dns", dns.getString("tag"))
        assertFalse(dnsServer.has("tag"))
        assertEquals("proxy", nodeDnsRule.getString("outboundTag"))
        assertEquals("proxy", tunnelDnsRule.getString("outboundTag"))
        assertEquals(2, secureDnsRuleIndex)
        assertEquals("tcp,udp", rulesArray.getJSONObject(secureDnsRuleIndex).getString("network"))
        assertEquals("block", rulesArray.getJSONObject(secureDnsRuleIndex).getString("outboundTag"))
        assertEquals("443", cloudflareDohRule.getString("port"))
        assertEquals("block", cloudflareDohRule.getString("outboundTag"))
        assertFalse(root.toString().contains("1.1.1.1"))
        assertFalse(root.toString().contains("8.8.8.8"))
        assertFalse(outbounds.contains("\"dns-out\""))
        assertTrue(rules.contains("\"port\":\"53\""))
        assertTrue(rules.contains("192.168.0.0/16"))
        assertTrue(rules.contains("10.0.0.0/8"))
        assertTrue(rules.contains("fc00::/7"))
        assertTrue(rules.contains("fe80::/10"))
        assertTrue(rules.contains("224.0.0.0/4"))
        assertTrue(rules.contains("ff00::/8"))
        assertFalse(root.toString().contains("77.88.8.8"))
        assertFalse(root.toString().contains("77.88.8.1"))
        assertFalse(root.toString().contains("youtube-ru-cascade"))
        assertFalse(root.toString().contains("geosite:youtube"))
        assertFalse(root.toString().contains("\"fragment\""))
    }

    @Test
    fun youtubeModeAddsAuthenticatedRussianCascadeThroughPrimaryProxy() {
        val root = JSONObject(
            XrayConfigFactory.build(
                validProfile(),
                AdvancedSettings(
                    youtubeDirectDpiEnabled = true,
                    alwaysOnDomains = listOf("domain:secure.example"),
                    bypassDomains = listOf("domain:local.example"),
                ),
            ),
        )
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        val overrides = inbound.getJSONObject("sniffing").getJSONArray("destOverride")
        val outbounds = root.getJSONArray("outbounds")
        val outbound = (0 until outbounds.length())
            .map(outbounds::getJSONObject)
            .single { it.optString("tag") == "youtube-ru-cascade" }
        val server = outbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
        val user = server.getJSONArray("users").getJSONObject(0)
        val streamSettings = outbound.getJSONObject("streamSettings")
        val rulesArray = root.getJSONObject("routing").getJSONArray("rules")
        val rules = (0 until rulesArray.length()).map(rulesArray::getJSONObject)
        val youtubeUdpIndex = rules.indexOfFirst {
            it.optString("network") == "udp" &&
                it.optString("port") == "443" &&
                it.optJSONArray("domain")?.toString()?.contains("geosite:youtube") == true
        }
        val youtubeTcpIndex = rules.indexOfFirst {
            it.optString("network") == "tcp" &&
                it.optString("outboundTag") == "youtube-ru-cascade" &&
                it.optJSONArray("domain")?.toString()?.contains("geosite:youtube") == true
        }
        val bypassIndex = rules.indexOfFirst {
            it.optJSONArray("domain")?.toString()?.contains("domain:local.example") == true
        }
        val alwaysOnIndex = rules.indexOfFirst {
            it.optJSONArray("domain")?.toString()?.contains("domain:secure.example") == true
        }

        assertTrue(overrides.toString().contains("quic"))
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("ru-cascade.example", server.getString("address"))
        assertEquals(443, server.getInt("port"))
        assertEquals("cascade-uuid", user.getString("id"))
        assertFalse(user.has("flow"))
        assertEquals("proxy", streamSettings.getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals("reality", streamSettings.getString("security"))
        assertEquals(
            "cascade-public",
            streamSettings.getJSONObject("realitySettings").getString("password"),
        )
        assertFalse(streamSettings.getJSONObject("realitySettings").has("publicKey"))
        assertFalse(outbound.toString().contains("\"fragment\""))
        assertTrue(youtubeUdpIndex >= 0)
        assertEquals("block", rules[youtubeUdpIndex].getString("outboundTag"))
        assertTrue(youtubeTcpIndex > youtubeUdpIndex)
        assertTrue(bypassIndex > youtubeTcpIndex)
        assertTrue(alwaysOnIndex > youtubeTcpIndex)
        assertEquals(
            2,
            rules.count {
                it.optJSONArray("domain")?.toString()?.contains("geosite:youtube") == true
            },
        )
        assertEquals(1, root.getJSONObject("dns").getJSONArray("servers").length())
        assertEquals(
            "tcp://198.18.0.1:53",
            root.getJSONObject("dns")
                .getJSONArray("servers")
                .getJSONObject(0)
                .getString("address"),
        )
    }

    @Test
    fun `Russian preset adds domain rule without geoip after explicit always-on`() {
        val root = JSONObject(
            XrayConfigFactory.build(
                validProfile(),
                AdvancedSettings(
                    alwaysOnDomains = listOf("domain:secure.example"),
                    bypassDomains = listOf(
                        "domain:local.example",
                        DomainRulePolicy.RUSSIAN_RESOURCES_RULE,
                    ),
                ),
            ),
        )
        val routing = root.getJSONObject("routing")
        val rules = routing.getJSONArray("rules")
        val jsonRules = (0 until rules.length()).map(rules::getJSONObject)
        val explicitAlwaysIndex = jsonRules.indexOfFirst {
            it.optJSONArray("domain")?.toString()?.contains("domain:secure.example") == true
        }
        val russianDomainIndex = jsonRules.indexOfFirst {
            it.optJSONArray("domain")?.toString()?.contains(DomainRulePolicy.RUSSIAN_RESOURCES_RULE) == true
        }

        assertEquals("AsIs", routing.getString("domainStrategy"))
        assertTrue(explicitAlwaysIndex >= 0)
        assertTrue(russianDomainIndex > explicitAlwaysIndex)
        assertEquals("proxy", jsonRules[explicitAlwaysIndex].getString("outboundTag"))
        assertEquals("direct", jsonRules[russianDomainIndex].getString("outboundTag"))
        assertFalse(jsonRules[russianDomainIndex].has("ip"))
        assertFalse(rules.toString().contains("geoip:ru"))
    }

    @Test
    fun `removing Russian preset removes domain rule and keeps AsIs`() {
        val routing = JSONObject(
            XrayConfigFactory.build(
                validProfile(),
                AdvancedSettings(bypassDomains = listOf("domain:local.example")),
            ),
        ).getJSONObject("routing")
        val rules = routing.getJSONArray("rules").toString()

        assertEquals("AsIs", routing.getString("domainStrategy"))
        assertFalse(rules.contains(DomainRulePolicy.RUSSIAN_RESOURCES_RULE))
        assertFalse(rules.contains("geoip:ru"))
        assertTrue(rules.contains("domain:local.example"))
    }

    private fun validProfile(): VlessProfile {
        return VlessProfile(
            endpointCode = "lv1-a",
            host = "lv1.example.com",
            port = "8443",
            uuid = "75e9f9e8-a9f8-4d42-9de1-f91f1a210991",
            flow = "xtls-rprx-vision",
            security = "reality",
            serverName = "www.lu.lv",
            publicKey = "public",
            shortId = "short",
            youtubeCascade = YoutubeCascadeProfile(
                host = "ru-cascade.example",
                port = 443,
                uuid = "cascade-uuid",
                serverName = "www.lu.lv",
                publicKey = "cascade-public",
                shortId = "cascade-short",
            ),
        )
    }
}
