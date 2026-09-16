package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

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
    fun parse(json: JSONObject): BackendVpnSession = BackendVpnSession(
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
        endpointCandidates = parseEndpointCandidates(json.optJSONArray("endpoint_candidates")),
        connectIp = json.optBackendString("connect_ip"),
        youtubeCascade = parseYoutubeCascade(json.optJSONObject("youtube_cascade")),
    )

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
                add(
                    BackendEndpointCandidate(
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
                    ),
                )
            }
        }
    }

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
