package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import okio.ByteString.Companion.decodeBase64

internal fun JSONObject.toBackendPayment(): BackendPayment = BackendPayment(
    publicId = getString("public_id"),
    planCode = getString("plan_code"),
    status = getString("status"),
    amountRub = getInt("amount_rub"),
    paymentUrl = optBackendString("payment_url"),
    createdAt = optBackendString("created_at"),
    description = optBackendString("description"),
)

internal fun JSONObject.toBackendSubscription(): BackendSubscription =
    BackendSubscription(
        status = optString("status", "inactive"),
        planCode = optString("plan_code").takeIf { it.isNotBlank() },
        expiresAt = optString("expires_at").takeIf { it.isNotBlank() },
        trafficUsedGb = optBackendDouble("traffic_used_gb"),
        trafficLimitGb = optBackendDouble("traffic_limit_gb"),
        planName = optBackendString("plan_name"),
        planTier = optBackendString("plan_tier"),
        planBadgeColor = optBackendString("plan_badge_color"),
    )

internal fun JSONObject.toBackendVpnAccess(): BackendVpnAccess =
    BackendVpnAccess(
        canConnect = optBoolean("can_connect", false),
        reason = optString("reason").takeIf { it.isNotBlank() },
        planCode = optString("plan_code").takeIf { it.isNotBlank() },
    )

internal fun JSONObject.toBackendAuthTokens(): BackendAuthTokens =
    BackendAuthTokens(
        accessToken = getString("access_token"),
        refreshToken = optBackendString("refresh_token"),
        tokenType = optString("token_type", "bearer").ifBlank { "bearer" },
        expiresInSeconds = optBackendLong("expires_in") ?: optBackendLong("access_expires_in"),
        refreshExpiresAt = optBackendString("refresh_expires_at"),
    )

internal fun JSONObject.toBackendDevice(): BackendDevice =
    BackendDevice(
        id = getString("id"),
        deviceKey = getString("device_key"),
        deviceName = getString("device_name"),
        customName = optBackendString("custom_name"),
        platform = getString("platform"),
        accessRole = optString("access_role", "owner"),
        isActive = optBoolean("is_active", true),
        lastSeenAt = optString("last_seen_at").takeIf { it.isNotBlank() },
    )

internal fun JSONObject.toBackendIncyDevice(): BackendIncyDevice =
    BackendIncyDevice(
        id = getString("id"),
        name = getString("name"),
        status = optString("status", "waiting"),
        isSlotActive = optBoolean("is_slot_active", true),
        hwidFingerprint = optBackendString("hwid_fingerprint"),
        deviceOs = optBackendString("device_os"),
        osVersion = optBackendString("os_version"),
        deviceModel = optBackendString("device_model"),
        appVersion = optBackendString("app_version"),
        generation = optInt("generation", 1),
        endpointCount = optInt("endpoint_count", 0),
        trafficBytes = optBackendLong("traffic_bytes") ?: 0L,
        boundAt = optBackendString("bound_at"),
        lastRequestedAt = optBackendString("last_requested_at"),
        createdAt = optString("created_at"),
        updatedAt = optString("updated_at"),
    )

internal fun JSONObject.toBackendDeviceInvite(): BackendDeviceInvite =
    BackendDeviceInvite(
        inviteCode = getString("invite_code"),
        expiresAt = optString("expires_at").takeIf { it.isNotBlank() },
    )

internal fun JSONObject.toBackendDeviceChallenge(): BackendDeviceChallenge =
    BackendDeviceChallenge(
        deviceId = getString("device_id"),
        nonce = getString("nonce"),
        expiresAt = optString("expires_at").takeIf { it.isNotBlank() },
    )

internal fun JSONObject.toBackendLocation(): BackendLocation =
    BackendLocation(
        id = getString("id"),
        code = getString("code"),
        name = getString("name"),
        nameRu = optBackendString("name_ru"),
        nameEn = optBackendString("name_en"),
        entryHost = getString("entry_host"),
        servers = optJSONArray("servers")?.let { nodes ->
            (0 until nodes.length()).map { index ->
                val node = nodes.getJSONObject(index)
                VpnServer(
                    id = node.getString("id"),
                    name = node.getString("name"),
                    countryCode = node.getString("country_code"),
                    locationCode = node.getString("location_code"),
                    host = node.getString("host"),
                    probePort = node.optBackendInt("probe_port"),
                    isOnline = node.optBoolean("is_online", false),
                    capacityMbps = node.optBackendInt("capacity_mbps"),
                    loadPercent = node.optBackendInt("load_percent"),
                    metricsAt = node.optBackendString("metrics_at"),
                    weight = node.optInt("weight", 1),
                    multiHopEntryAvailable = node.optBoolean("multihop_entry_available", false),
                    multiHopExitAvailable = node.optBoolean("multihop_exit_available", false),
                )
            }
        }.orEmpty(),
        countryCode = getString("country_code"),
        isOnline = optBoolean("is_online", false),
        capacityMbps = optBackendInt("capacity_mbps"),
        downloadMbps = optBackendDouble("download_mbps"),
        uploadMbps = optBackendDouble("upload_mbps"),
        latencyMs = optBackendInt("latency_ms"),
        loadPercent = optBackendInt("load_percent"),
    )

internal fun JSONObject.toBackendPlan(): BackendPlan =
    BackendPlan(
        code = getString("code"),
        name = getString("name"),
        tier = getString("tier"),
        billingPeriodMonths = optInt("billing_period_months", 1),
        priceRub = optInt("price_rub", 0),
        monthlyEquivalentRub = optInt("monthly_equivalent_rub", optInt("price_rub", 0)),
        deviceLimit = optInt("device_limit", 1),
        trafficLimitGb = optBackendDouble("traffic_limit_gb"),
        speedProfile = optString("speed_profile"),
        features = optJSONArray("features").toBackendStringList(),
        headline = optBackendString("headline"),
        badgeColor = optBackendString("badge_color"),
        isActive = optBoolean("is_active", true),
        sortOrder = optInt("sort_order", 0),
    )

internal fun JSONObject.toBackendAppNotification(): BackendAppNotification =
    BackendAppNotification(
        id = getString("id"),
        title = optString("title", "Noki"),
        message = getString("message"),
        createdAt = optString("created_at"),
        action = optBackendString("action"),
    )

internal fun JSONObject.toBackendVpnSession(): BackendVpnSession =
    BackendVpnSessionJsonParser.parse(this)

internal fun RuRelaySelection.toBackendJson(): JSONObject = when (this) {
    RuRelaySelection.Auto -> JSONObject().put("kind", "auto")
    is RuRelaySelection.Node -> JSONObject().put("kind", "node").put("node_id", canonicalRuUuid(nodeId))
    RuRelaySelection.Off -> throw IllegalArgumentException("ru_relay_disabled")
}

internal fun RuWireGuardSessionRequest.toBackendJson(): JSONObject {
    require(deviceNonce.isNotBlank() && deviceSignature.isNotBlank()) { "ru_wireguard_device_proof_missing" }
    require(relayRttSamples.size <= 32 && relayRttSamples.all { it.rttMs in 1..3000 }) {
        "ru_relay_rtt_invalid"
    }
    require(relayRttSamples.map { it.nodeId }.toSet().size == relayRttSamples.size) {
        "ru_relay_rtt_duplicate"
    }
    return JSONObject()
        .put("device_id", canonicalRuUuid(deviceId))
        .put("device_key", deviceKey)
        .put("device_nonce", deviceNonce)
        .put("device_signature", deviceSignature)
        .put("profile_code", "auto")
        .put("route_mode", "ru_wg_nebula")
        .put("capabilities", JSONArray().put("wireguard_native_v1"))
        .put("request_id", canonicalRuUuid(requestId))
        .put("public_key_id", canonicalRuUuid(publicKeyId))
        .put("exit_node_id", canonicalRuUuid(exitNodeId))
        .put("relay_selection", relaySelection.toBackendJson())
        .put("relay_rtt_samples", JSONArray().apply {
            relayRttSamples.forEach { sample ->
                put(JSONObject().put("node_id", canonicalRuUuid(sample.nodeId))
                    .put("rtt_ms", sample.rttMs))
            }
        })
}

private fun canonicalRuUuid(raw: String): String = runCatching { UUID.fromString(raw).toString() }
    .getOrElse { throw IllegalArgumentException("ru_wireguard_uuid_invalid", it) }
    .also { require(it == raw.lowercase(Locale.ROOT)) { "ru_wireguard_uuid_invalid" } }

internal object RuWireGuardSessionJsonParser {
    fun parse(
        json: JSONObject,
        expected: RuWireGuardSessionExpectation,
        now: Instant = Instant.now(),
    ): BackendRuWireGuardSession {
        require(json.getString("engine") == "wireguard" && json.getInt("contract_version") == 1 &&
            json.getString("route_mode") == "ru_wg_nebula") { "ru_wireguard_engine_invalid" }
        val selection = parseSelection(json.getJSONObject("relay_selection"))
        val relayId = canonicalRuUuid(json.getString("relay_node_id"))
        val exitId = canonicalRuUuid(json.getString("exit_node_id"))
        val keyId = canonicalRuUuid(json.getString("public_key_id"))
        val requestId = canonicalRuUuid(json.getString("request_id"))
        val sessionId = canonicalRuUuid(json.getString("session_id"))
        val expectedSelection = when (val requested = expected.relaySelection) {
            RuRelaySelection.Auto -> RuRelaySelection.Auto
            is RuRelaySelection.Node -> RuRelaySelection.Node(canonicalRuUuid(requested.nodeId))
            RuRelaySelection.Off -> throw IllegalArgumentException("ru_relay_disabled")
        }
        require(requestId == canonicalRuUuid(expected.requestId) &&
            keyId == canonicalRuUuid(expected.publicKeyId) && exitId == canonicalRuUuid(expected.exitNodeId) &&
            selection == expectedSelection &&
            (selection !is RuRelaySelection.Node || relayId == selection.nodeId)) {
            "ru_wireguard_response_mismatch"
        }
        val lease = runCatching { Instant.parse(json.getString("lease_expires_at")) }
            .getOrElse { throw IllegalArgumentException("ru_wireguard_lease_invalid", it) }
        require(lease.isAfter(now)) { "ru_wireguard_lease_expired" }
        val generation = json.getInt("generation")
        require(generation >= 1) { "ru_wireguard_generation_invalid" }
        val identity = BackendRuWireGuardIdentity(
            requestId, sessionId, generation, keyId, selection, relayId, exitId, lease.toEpochMilli(),
        )
        return when (json.getString("status")) {
            "pending" -> {
                require(!json.has("wireguard") || json.isNull("wireguard")) { "ru_wireguard_pending_config_invalid" }
                BackendRuWireGuardSession.Pending(identity)
            }
            "ready" -> BackendRuWireGuardSession.Ready(
                identity, parseConfig(json.optJSONObject("wireguard")
                    ?: throw IllegalArgumentException("ru_wireguard_config_missing")),
            )
            else -> throw IllegalArgumentException("ru_wireguard_status_invalid")
        }
    }

    private fun parseSelection(json: JSONObject): RuRelaySelection = when (json.getString("kind")) {
        "auto" -> {
            require(json.length() == 1) { "ru_relay_selection_invalid" }
            RuRelaySelection.Auto
        }
        "node" -> {
            require(json.length() == 2) { "ru_relay_selection_invalid" }
            RuRelaySelection.Node(canonicalRuUuid(json.getString("node_id")))
        }
        else -> throw IllegalArgumentException("ru_relay_selection_invalid")
    }

    private fun parseConfig(json: JSONObject): BackendRuWireGuardConfig {
        val endpoint = json.getString("endpoint")
        val host = endpoint.substringBeforeLast(':', "")
        val port = endpoint.substringAfterLast(':', "").toIntOrNull()
        require(isIpv4(host) && port != null && port in 1..65535) { "ru_wireguard_endpoint_invalid" }
        val address = json.getString("address")
        require(address.endsWith("/32") && isIpv4(address.removeSuffix("/32"))) {
            "ru_wireguard_address_invalid"
        }
        val key = json.getString("server_public_key")
        val decoded = key.decodeBase64()?.toByteArray()
        require(decoded != null && decoded.size == 32 && decoded.any { it != 0.toByte() }) {
            "ru_wireguard_key_invalid"
        }
        val dnsArray = json.getJSONArray("dns")
        val dns = (0 until dnsArray.length()).map { dnsArray.getString(it) }
        require(dns.size in 1..4 && dns.all(::isIpv4)) { "ru_wireguard_dns_invalid" }
        val allowedArray = json.getJSONArray("allowed_ips")
        val allowed = (0 until allowedArray.length()).map { allowedArray.getString(it) }
        require(allowed == listOf("0.0.0.0/0")) { "ru_wireguard_allowed_ips_invalid" }
        val mtu = json.getInt("mtu")
        require(mtu in 1280..1500) { "ru_wireguard_mtu_invalid" }
        return BackendRuWireGuardConfig(endpoint, key, address, dns, mtu, allowed)
    }

    private fun isIpv4(raw: String): Boolean {
        val octets = raw.split('.')
        return octets.size == 4 && octets.all { token ->
            token.isNotEmpty() && token.length <= 3 && token.all(Char::isDigit) &&
                (token.toIntOrNull() ?: -1) in 0..255 && (token == "0" || !token.startsWith('0'))
        }
    }
}

internal fun JSONArray?.toDeviceList(): List<BackendDevice> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(getJSONObject(index).toBackendDevice())
        }
    }
}

internal fun JSONArray?.toIncyDeviceList(): List<BackendIncyDevice> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(getJSONObject(index).toBackendIncyDevice())
        }
    }
}

internal fun JSONArray?.toLocationList(): List<BackendLocation> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(getJSONObject(index).toBackendLocation())
        }
    }
}

internal fun JSONArray?.toPlanList(): List<BackendPlan> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(getJSONObject(index).toBackendPlan())
        }
    }
}

internal fun JSONArray?.toAppNotificationList(): List<BackendAppNotification> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(item.toBackendAppNotification())
        }
    }
}

internal fun JSONObject.optBackendDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name).takeUnless { it.isNaN() }
}

internal fun JSONObject.optBackendInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

internal fun JSONObject.optBackendLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getLong(name) }.getOrNull()
}

internal object BackendTelegramAuthContract {
    fun nativeStartPayload(codeChallenge: String, clientState: String): JSONObject {
        require(codeChallenge.isNotBlank()) { "telegram_code_challenge_missing" }
        require(clientState.isNotBlank()) { "telegram_client_state_missing" }
        return JSONObject()
            .put("code_challenge", codeChallenge)
            .put("client_state", clientState)
    }

    fun nativeTokenPayload(
        code: String,
        codeVerifier: String,
    ): JSONObject {
        require(code.isNotBlank()) { "telegram_authorization_code_missing" }
        require(codeVerifier.isNotBlank()) { "telegram_code_verifier_missing" }
        return JSONObject()
            .put("code", code)
            .put("code_verifier", codeVerifier)
    }

    fun browserTokenPayload(
        state: String,
        codeVerifier: String,
    ): JSONObject {
        require(state.isNotBlank()) { "telegram_browser_state_missing" }
        require(codeVerifier.isNotBlank()) { "telegram_code_verifier_missing" }
        return JSONObject()
            .put("state", state)
            .put("code_verifier", codeVerifier)
    }

    fun parseTelegramUrl(json: JSONObject): String =
        json.getString("telegram_url").trim().also {
            require(it.isNotBlank()) { "telegram_launch_url_missing" }
        }

    fun parseAuthorizationUrl(json: JSONObject): String =
        json.getString("authorization_url").trim().also {
            require(it.isNotBlank()) { "telegram_authorization_url_missing" }
        }

    fun parseIdToken(json: JSONObject): String =
        json.getString("id_token").trim().also {
            require(it.isNotBlank()) { "telegram_id_token_missing" }
        }

    fun loginPayload(idToken: String, deviceId: String?): JSONObject =
        BackendFederatedAuthContract.loginPayload(
            provider = "telegram",
            idToken = idToken,
            deviceId = deviceId,
        )

    fun parseTokens(json: JSONObject): BackendAuthTokens =
        BackendFederatedAuthContract.parseTokens(json)
}

internal object BackendGoogleAuthContract {
    fun loginPayload(idToken: String, deviceId: String?): JSONObject =
        BackendFederatedAuthContract.loginPayload(
            provider = "google",
            idToken = idToken,
            deviceId = deviceId,
        )

    fun parseTokens(json: JSONObject): BackendAuthTokens =
        BackendFederatedAuthContract.parseTokens(json)
}

private object BackendFederatedAuthContract {
    fun loginPayload(provider: String, idToken: String, deviceId: String?): JSONObject {
        require(idToken.isNotBlank()) { "${provider}_id_token_missing" }
        return JSONObject()
            .put("id_token", idToken)
            .apply {
                deviceId?.takeIf { it.isNotBlank() }?.let { put("device_id", it) }
            }
    }

    fun parseTokens(json: JSONObject): BackendAuthTokens = BackendAuthTokens(
        accessToken = json.getString("access_token"),
        refreshToken = json.optBackendString("refresh_token"),
        tokenType = json.optString("token_type", "bearer").ifBlank { "bearer" },
        expiresInSeconds = json.optBackendLong("expires_in")
            ?: json.optBackendLong("access_expires_in"),
        refreshExpiresAt = json.optBackendString("refresh_expires_at"),
    )
}

internal object BackendUserResponseParser {
    fun parse(json: JSONObject): BackendUser {
        val email = json.getString("email")
        return BackendUser(
            id = json.getString("id"),
            username = json.getString("username"),
            email = email,
            avatarUrl = json.optBackendString("avatar_url"),
            isActive = json.optBoolean("is_active", true),
            isAdmin = json.optBoolean("is_admin", false),
            hasRealEmail = json.optBoolean(
                "has_real_email",
                !email.endsWith("@a.noki", ignoreCase = true),
            ),
            hasPassword = json.optBoolean("has_password", true),
            telegramLinked = json.optBoolean("telegram_linked", false),
        )
    }
}

internal object BackendTemporaryVpnResponseParser {
    fun parseChallenge(json: JSONObject): BackendTemporaryVpnChallenge {
        val nonce = json.getString("nonce").trim()
        val expiresInSeconds = json.getLong("expires_in")
        require(nonce.isNotBlank()) { "temporary_vpn_nonce_missing" }
        require(expiresInSeconds in 1L..60L) { "temporary_vpn_challenge_ttl_invalid" }
        return BackendTemporaryVpnChallenge(
            nonce = nonce,
            expiresInSeconds = expiresInSeconds,
        )
    }

    fun parseSession(json: JSONObject): BackendTemporaryVpnSession {
        val mode = json.getString("mode").trim().lowercase(Locale.ROOT)
        require(mode == "auth_temp") { "temporary_vpn_mode_invalid" }
        val sessionId = json.getString("session_id").trim()
        val controlToken = json.getString("control_token").trim()
        val trafficLimitBytes = json.getLong("traffic_limit_bytes")
        val expiresAtEpochMillis = parseEpochMillis(json.getString("expires_at"))
        require(sessionId.isNotBlank()) { "temporary_vpn_session_id_missing" }
        require(controlToken.isNotBlank()) { "temporary_vpn_control_token_missing" }
        require(trafficLimitBytes > 0L) { "temporary_vpn_traffic_limit_invalid" }
        return BackendTemporaryVpnSession(
            mode = mode,
            sessionId = sessionId,
            controlToken = controlToken,
            trafficLimitBytes = trafficLimitBytes,
            expiresAtEpochMillis = expiresAtEpochMillis,
            vpnSession = BackendVpnSessionJsonParser.parse(json),
        )
    }

    private fun parseEpochMillis(raw: String): Long {
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrElse { throw IllegalArgumentException("temporary_vpn_expiry_invalid", it) }
    }
}

internal object BackendVpnSessionJsonParser {
    fun parse(json: JSONObject): BackendVpnSession {
        val routeMode = json.optString("route_mode", "direct")
        require(routeMode == "direct" || routeMode == "multihop") { "multihop_route_mode_invalid" }
        val candidates = parseEndpointCandidates(json.optJSONArray("endpoint_candidates"))
        val multiHop = if (routeMode == "multihop") parseMultiHop(json.getJSONObject("multihop"), candidates) else null
        return BackendVpnSession(
        canConnect = json.optBoolean("can_connect", false),
        profileCode = json.optString("profile_code", "tls"),
        locationCode = json.getString("location_code"),
        locationName = json.getString("location_name"),
        endpointCode = json.optBackendString("endpoint_code"),
        entryHost = json.getString("entry_host"),
        entryPort = json.optInt("entry_port", 443),
        serverName = json.getString("server_name"),
        proxyType = json.optString("proxy_type", "vless"),
        transport = json.optString("transport", "tcp"),
        transportMode = json.optBackendString("transport_mode"),
        security = json.getString("security"),
        fingerprint = json.optBackendString("fingerprint"),
        requestHost = json.optBackendString("request_host"),
        path = json.optBackendString("path"),
        alpn = json.optBackendString("alpn"),
        allowInsecure = json.optBoolean("allow_insecure", false),
        enableMux = json.optBoolean("enable_mux", false),
        randomUserAgent = json.optBoolean("random_user_agent", false),
        publicKey = json.optBackendString("public_key"),
        shortId = json.optBackendString("short_id"),
        vpnUsername = json.getString("vpn_username"),
        vpnSecret = json.getString("vpn_secret"),
        flow = json.optBackendString("flow"),
        planCode = json.optBackendString("plan_code"),
        endpointCandidates = candidates,
        connectIp = json.optBackendString("connect_ip"),
        youtubeCascade = parseYoutubeCascade(json.optJSONObject("youtube_cascade")),
        routeMode = routeMode,
        multiHop = multiHop,
    )
    }

    private fun parseMultiHop(json: JSONObject, candidates: List<BackendEndpointCandidate>): BackendMultiHopSession {
        fun hop(json: JSONObject): HopSelection = when (json.getString("kind")) {
            "country" -> HopSelection(ServerSelectionMode.COUNTRY, countryCode = json.getString("country_code"))
            "node" -> HopSelection(ServerSelectionMode.SERVER, nodeId = json.getString("node_id"))
            else -> throw IllegalArgumentException("multihop_selector_invalid")
        }
        val selection = json.getJSONObject("selection")
        val version = json.getInt("version")
        val entryNodeId = json.getString("entry_node_id")
        val exitNodeId = json.getString("exit_node_id")
        val entryIp = json.getString("entry_ip")
        val policyHash = json.getString("policy_hash")
        require(version == 2 && entryNodeId.isNotBlank() && entryNodeId != exitNodeId && exitNodeId.isNotBlank()) {
            "multihop_nodes_invalid"
        }
        require(isPublicIpv4(entryIp)) { "multihop_entry_invalid" }
        require(candidates.isNotEmpty() && candidates.all {
            it.nodeId == exitNodeId && it.entryHost == entryIp && it.connectIp == entryIp
                && it.entryPort in 20000..29999 && EndpointSecurityPolicy.isAllowedCandidate(it)
        }) { "multihop_exit_invalid" }
        require(policyHash.matches(Regex("[0-9a-f]{64}"))) {
            "multihop_policy_invalid"
        }
        return BackendMultiHopSession(
            version = version,
            selection = MultiHopSettings(enabled = true,
                entry = hop(selection.getJSONObject("entry")), exit = hop(selection.getJSONObject("exit"))),
            entryNodeId = entryNodeId, exitNodeId = exitNodeId, entryIp = entryIp,
            policyHash = policyHash,
        )
    }

    private fun isPublicIpv4(value: String?): Boolean {
        val tokens = value?.split('.') ?: return false
        if (tokens.size != 4) return false
        val parts = tokens.map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a in 1..223 && a !in setOf(10, 127) && !(a == 169 && b == 254)
            && !(a == 172 && b in 16..31) && !(a == 192 && b == 168)
            && !(a == 100 && b in 64..127) && !(a == 198 && b in 18..19)
    }

    private fun parseYoutubeCascade(json: JSONObject?): YoutubeCascadeProfile? {
        if (json == null) return null
        val profile = YoutubeCascadeProfile(
            host = json.optString("host"),
            port = json.optInt("port", 443),
            uuid = json.optString("uuid"),
            serverName = json.optString("server_name"),
            publicKey = json.optString("public_key"),
            shortId = json.optString("short_id"),
            fingerprint = json.optString("fingerprint", "chrome"),
            flow = json.optString("flow"),
        )
        return profile.takeIf {
            it.port in 1..65535 &&
                it.host.isNotBlank() &&
                it.uuid.isNotBlank() &&
                it.serverName.isNotBlank() &&
                it.publicKey.isNotBlank() &&
                it.shortId.isNotBlank()
        }
    }

    private fun parseEndpointCandidates(array: JSONArray?): List<BackendEndpointCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseEndpointCandidate(item))
            }
        }
    }

    private fun parseEndpointCandidate(item: JSONObject): BackendEndpointCandidate = BackendEndpointCandidate(
        code = item.getString("code"),
        nodeId = item.optBackendString("node_id"),
        label = item.optString("label"),
        locationCode = item.optString("location_code"),
        locationName = item.optString("location_name"),
        entryHost = item.getString("entry_host"),
        entryPort = item.optInt("entry_port", 443),
        serverName = item.optString("server_name"),
        proxyType = item.optString("proxy_type", "vless"),
        transport = item.optString("transport", "tcp"),
        transportMode = item.optBackendString("transport_mode"),
        security = item.optString("security", "tls"),
        fingerprint = item.optBackendString("fingerprint"),
        requestHost = item.optBackendString("request_host"),
        path = item.optBackendString("path"),
        alpn = item.optBackendString("alpn"),
        allowInsecure = item.optBoolean("allow_insecure", false),
        enableMux = item.optBoolean("enable_mux", false),
        randomUserAgent = item.optBoolean("random_user_agent", false),
        publicKey = item.optBackendString("public_key"),
        shortId = item.optBackendString("short_id"),
        flow = item.optBackendString("flow"),
        priority = item.optInt("priority", 100),
        weight = item.optInt("weight", 100),
        canaryOnly = item.optBoolean("canary_only", false),
        tags = item.optJSONArray("tags").toBackendStringList(),
        connectIp = item.optBackendString("connect_ip"),
    )

}

internal fun JSONObject.toBackendAndroidUpdate(): BackendAndroidUpdate =
    BackendAndroidUpdate(
        updateAvailable = optBoolean("update_available", false),
        versionCode = optBackendLong("version_code"),
        versionName = optBackendString("version_name"),
        releaseNotes = optBackendString("release_notes"),
        isForced = optBoolean("is_forced", false),
        architecture = optBackendString("architecture"),
        apkUrl = optBackendString("apk_url"),
        apkSha256 = optBackendString("apk_sha256"),
        apkSizeBytes = optBackendLong("apk_size_bytes"),
    )

internal fun JSONObject.optBackendString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name).trim()
    return value.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}

internal fun JSONArray?.toBackendStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
