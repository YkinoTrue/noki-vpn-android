package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigFactory {
    private const val NODE_DNS_TAG = "noki-node-dns"

    fun build(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
    ): String {
        val normalizedAdvancedSettings = DomainRulePolicy.normalizeSettings(advancedSettings)
        require(normalizedAdvancedSettings.multiHop.enabled || profile.multiHop == null) { "multihop_runtime_unexpected" }
        val multiHop = profile.multiHop.takeIf { normalizedAdvancedSettings.multiHop.enabled }
        if (normalizedAdvancedSettings.multiHop.enabled) {
            require(multiHop != null && multiHop.selection == normalizedAdvancedSettings.multiHop) {
                "multihop_runtime_missing"
            }
        }
        val youtubeDirectDpiEnabled = normalizedAdvancedSettings.youtubeDirectDpiEnabled
        val youtubeCascade = profile.youtubeCascade?.takeIf { youtubeDirectDpiEnabled &&
            (multiHop == null || it.host.split('.').let { parts ->
                parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { value -> value in 0..255 } == true }
            }) }
        val russianResourcesEnabled = multiHop == null && normalizedAdvancedSettings.bypassDomains
            .contains(DomainRulePolicy.RUSSIAN_RESOURCES_RULE)
        val bypassDomains = normalizedAdvancedSettings.bypassDomains.takeIf { multiHop == null }.orEmpty()
            .filterNot { it == DomainRulePolicy.RUSSIAN_RESOURCES_RULE }
        val destinationOverrides = JSONArray()
            .put("http")
            .put("tls")
            .apply {
                if (youtubeCascade != null) put("quic")
            }

        val tunInbound = JSONObject()
            .put("tag", "tun")
            .put("port", 0)
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "noki0")
                    .put("MTU", VpnTunnelPolicy.MTU)
                    .put("userLevel", 8),
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", destinationOverrides)
                    .put("routeOnly", true),
            )

        val directOutbound = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject().put("domainStrategy", "UseIP"))

        val blockOutbound = JSONObject()
            .put("tag", "block")
            .put("protocol", "blackhole")
            .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))

        val youtubeCascadeOutbound = youtubeCascade?.let {
            proxyOutbound(
                VlessProfile(
                    host = it.host,
                    port = it.port.toString(),
                    uuid = it.uuid,
                    flow = it.flow,
                    security = "reality",
                    fingerprint = it.fingerprint,
                    serverName = it.serverName,
                    publicKey = it.publicKey,
                    shortId = it.shortId,
                ),
                tag = "youtube-ru-cascade",
                dialerProxy = "proxy",
                realityPasswordField = true,
            )
        }

        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(NODE_DNS_TAG))
                    .put("outboundTag", "proxy"),
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun"))
                    .put("port", "53")
                    .put("network", "udp,tcp")
                    .put("outboundTag", "proxy"),
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("port", "853")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "block"),
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put(
                        "domain",
                        JSONArray()
                            .put("domain:cloudflare-dns.com")
                            .put("domain:one.one.one.one"),
                    )
                    .put("port", "443")
                    .put("network", "tcp")
                    .put("outboundTag", "block"),
            )
        if (multiHop == null) rules.put(
            JSONObject().put("type", "field")
                .put("domain", JSONArray().put("domain:googleapis.cn"))
                .put("outboundTag", "direct"),
        ).put(
                JSONObject()
                    .put("type", "field")
                    .put(
                        "ip",
                        JSONArray()
                            .put("10.0.0.0/8")
                            .put("172.16.0.0/12")
                            .put("192.168.0.0/16")
                            .put("169.254.0.0/16")
                            .put("127.0.0.0/8")
                            .put("224.0.0.0/4")
                            .put("fc00::/7")
                            .put("fe80::/10")
                            .put("::1/128")
                            .put("ff00::/8"),
                    )
                    .put("outboundTag", "direct"),
            )

        if (youtubeDirectDpiEnabled) {
            rules
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("domain", JSONArray().put("geosite:youtube"))
                        .put("port", "443")
                        .put("network", "udp")
                        .put("outboundTag", "block"),
                )
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("domain", JSONArray().put("geosite:youtube"))
                        .put("network", "tcp")
                        .put("outboundTag", if (youtubeCascade != null) "youtube-ru-cascade" else "block"),
                )
        }

        if (bypassDomains.isNotEmpty()) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put(
                        "domain",
                        JSONArray().apply {
                            bypassDomains.forEach { put(it) }
                        },
                    )
                    .put("outboundTag", "direct"),
            )
        }

        if (normalizedAdvancedSettings.alwaysOnDomains.isNotEmpty()) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put(
                        "domain",
                        JSONArray().apply {
                            normalizedAdvancedSettings.alwaysOnDomains.forEach { put(it) }
                        },
                    )
                    .put("outboundTag", "proxy"),
            )
        }

        if (russianResourcesEnabled) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", JSONArray().put(DomainRulePolicy.RUSSIAN_RESOURCES_RULE))
                    .put("outboundTag", "direct"),
            )
        }

        val outbounds = transportOutbounds(profile)
            .put(directOutbound)
            .put(blockOutbound)
            .apply {
                youtubeCascadeOutbound?.let { put(it) }
            }

        val logLevel = when {
            normalizedAdvancedSettings.errorLogsEnabled -> "error"
            normalizedAdvancedSettings.connectionLogsEnabled -> "info"
            else -> "warning"
        }

        return JSONObject()
            .put("stats", JSONObject())
            .put("log", JSONObject().put("loglevel", logLevel))
            .put(
                "policy",
                JSONObject()
                    .put(
                        "levels",
                        levelPolicy(handshakeSeconds = 4, connIdleSeconds = 300),
                    )
                    .put(
                        "system",
                        JSONObject()
                            .put("statsOutboundUplink", true)
                            .put("statsOutboundDownlink", true),
                    ),
            )
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", outbounds)
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", rules),
            )
            .put(
                "dns",
                JSONObject()
                    .put("hosts", JSONObject())
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", "tcp://${VpnTunnelPolicy.DNS_SERVER}:53")
                                .put("queryStrategy", "UseIPv4"),
                        ),
                    )
                    .put("tag", NODE_DNS_TAG)
                    .put("queryStrategy", "UseIPv4")
                    .put("enableParallelQuery", false),
            )
            .toString(2)
    }

    fun buildProbe(profile: VlessProfile): String {
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "policy",
                JSONObject()
                    .put("levels", levelPolicy(handshakeSeconds = 3, connIdleSeconds = 20))
                    .put(
                        "system",
                        JSONObject()
                            .put("statsOutboundUplink", false)
                            .put("statsOutboundDownlink", false),
                    ),
            )
            .put("outbounds", transportOutbounds(profile))
            .toString(2)
    }

    private fun transportOutbounds(profile: VlessProfile): JSONArray {
        profile.multiHop?.let { requireMultiHopTransport(profile, it.relay) }
        return JSONArray()
            .put(proxyOutbound(profile, dialerProxy = profile.multiHop?.let { "multihop-relay" }))
            .apply {
                profile.multiHop?.let { put(proxyOutbound(it.relay, tag = "multihop-relay")) }
            }
    }

    private fun requireMultiHopTransport(exit: VlessProfile, relay: VlessProfile) {
        require(exit.proxyType == "vless" && relay.proxyType == "vless"
            && exit.transport == "tcp" && relay.transport == "tcp"
            && exit.security == "reality" && relay.security == "reality"
            && relay.multiHop == null && relay.youtubeCascade == null) { "multihop_transport_unsupported" }
    }

    private fun normalizeTransport(value: String): String {
        return when (value.lowercase()) {
            "raw" -> "tcp"
            "xhttp" -> "xhttp"
            "httpupgrade" -> "httpupgrade"
            "hysteria", "hysteria2", "hy2" -> "hysteria"
            else -> "tcp"
        }
    }

    private fun attachTransportSettings(
        streamSettings: JSONObject,
        profile: VlessProfile,
        transport: String,
    ) {
        val path = profile.path.ifBlank { "/" }
        val host = profile.requestHost.ifBlank { profile.serverName }
        when (transport) {
            "xhttp" -> {
                val settings = JSONObject()
                    .put("path", path)
                    .put("mode", profile.transportMode.ifBlank { "stream-up" })
                if (host.isNotBlank()) settings.put("host", host)
                streamSettings.put("xhttpSettings", settings)
            }
            "httpupgrade" -> {
                val settings = JSONObject()
                    .put("path", path)
                if (host.isNotBlank()) settings.put("host", host)
                streamSettings.put("httpupgradeSettings", settings)
            }
        }
    }

    private fun proxyOutbound(
        profile: VlessProfile,
        tag: String = "proxy",
        dialerProxy: String? = null,
        realityPasswordField: Boolean = false,
    ): JSONObject {
        if (profile.proxyType.equals("hysteria", ignoreCase = true)) {
            val tlsSettings = tlsSettings(profile)
            if (!tlsSettings.has("alpn")) {
                tlsSettings.put("alpn", JSONArray().put("h3"))
            }
            val streamSettings = JSONObject()
                .put("network", "hysteria")
                .put("security", "tls")
                .put("tlsSettings", tlsSettings)
                .put(
                    "hysteriaSettings",
                    JSONObject()
                        .put("version", 2)
                        .put("auth", profile.uuid)
                        .put("udpIdleTimeout", 60),
                )
            return JSONObject()
                .put("tag", tag)
                .put("protocol", "hysteria")
                .put(
                    "settings",
                    JSONObject()
                        .put("version", 2)
                        .put("address", profile.host)
                        .put("port", profile.port.toIntOrNull() ?: 443),
                )
                .put("streamSettings", streamSettings)
                .put("mux", JSONObject().put("enabled", false))
        }

        val user = JSONObject()
            .put("id", profile.uuid)
            .put("encryption", "none")
            .put("level", 8)
        if (profile.flow.isNotBlank()) {
            user.put("flow", profile.flow)
        }

        val vnext = JSONObject()
            .put("address", profile.host)
            .put("port", profile.port.toIntOrNull() ?: 443)
            .put("users", JSONArray().put(user))

        val transport = normalizeTransport(profile.transport)
        val streamSettings = JSONObject()
            .put("network", transport)
            .put("security", profile.security)
        val fingerprint = profile.fingerprint
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: "chrome"

        if (profile.security.equals("reality", ignoreCase = true)) {
            val realitySettings = JSONObject()
                .put("serverName", profile.serverName)
                .put("fingerprint", fingerprint)
                .put("shortId", profile.shortId)
                .put("spiderX", profile.spiderX)
            realitySettings.put(
                if (realityPasswordField) "password" else "publicKey",
                profile.publicKey,
            )
            streamSettings.put("realitySettings", realitySettings)
        } else if (profile.serverName.isNotBlank()) {
            streamSettings.put("tlsSettings", tlsSettings(profile))
        }
        attachTransportSettings(streamSettings, profile, transport)
        dialerProxy?.let {
            streamSettings.put("sockopt", JSONObject().put("dialerProxy", it))
        }

        return JSONObject()
            .put("tag", tag)
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", streamSettings)
            .put("mux", JSONObject().put("enabled", profile.enableMux))
    }

    private fun tlsSettings(profile: VlessProfile): JSONObject {
        val fingerprint = profile.fingerprint
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: "chrome"
        val serverName = profile.serverName.ifBlank { profile.host }
        val tlsSettings = JSONObject()
            .put("serverName", serverName)
            .put("fingerprint", fingerprint)
            .put("allowInsecure", false)
        val alpn = csvArray(profile.alpn)
        if (alpn.length() > 0) tlsSettings.put("alpn", alpn)
        return tlsSettings
    }

    private fun levelPolicy(
        handshakeSeconds: Int,
        connIdleSeconds: Int,
    ): JSONObject {
        return JSONObject().put(
            "8",
            JSONObject()
                .put("handshake", handshakeSeconds)
                .put("connIdle", connIdleSeconds)
                .put("uplinkOnly", 1)
                .put("downlinkOnly", 1),
        )
    }

    private fun csvArray(value: String): JSONArray {
        val result = JSONArray()
        value.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { result.put(it) }
        return result
    }
}
