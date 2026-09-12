package com.noki.vpn.data

object RuntimeProfilePolicy {
    fun profileAfterServerSelection(profile: VlessProfile): VlessProfile = profile.withoutRuntimeCredentials()

    fun normalize(settings: StoredSettings): StoredSettings {
        return settings.copy(
            profile = profileAfterProtocolChange(
                profile = settings.profile,
                protocol = settings.advancedSettings.protocol,
            ),
        )
    }

    fun profileAfterProtocolChange(
        profile: VlessProfile,
        protocol: VpnProtocol,
    ): VlessProfile {
        if (protocol == VpnProtocol.AUTO) return profile
        return if (profile.security.equals(protocol.name, ignoreCase = true)) {
            profile
        } else {
            profile.withoutRuntimeCredentials()
        }
    }

    fun profileAfterLocationSelection(
        profile: VlessProfile,
        selectedLocationCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): VlessProfile {
        val selectedCode = selectedLocationCode.trim()
        if (selectedCode.isBlank() || profile.endpointCode.isBlank()) return profile
        val endpoint = endpointOptions.firstOrNull { it.code == profile.endpointCode }
            ?: return profile.withoutRuntimeCredentials()
        return if (endpoint.locationCode.equals(selectedCode, ignoreCase = true)) {
            profile
        } else {
            profile.withoutRuntimeCredentials()
        }
    }

    fun profileForManualEndpoint(
        profile: VlessProfile,
        option: VpnEndpointOption,
    ): VlessProfile {
        return profile.copy(
            endpointCode = option.code,
            proxyType = option.proxyType,
            transport = option.transport,
            transportMode = option.transportMode,
            host = option.host,
            port = option.port.toString(),
            security = option.security,
            uuid = "",
            serverName = "",
            requestHost = "",
            path = "",
            alpn = "",
            publicKey = "",
            shortId = "",
            flow = "",
        )
    }

    fun isCachedProfileUsable(settings: StoredSettings): Boolean {
        val endpointCode = settings.profile.endpointCode.trim()
        if (endpointCode.isBlank()) return false
        if (settings.endpointOptions.none { it.code == endpointCode }) return false
        return VpnProfileValidator.isUsable(settings)
    }

    private fun VlessProfile.withoutRuntimeCredentials(): VlessProfile {
        return copy(
            endpointCode = "",
            uuid = "",
            serverName = "",
            requestHost = "",
            path = "",
            alpn = "",
            publicKey = "",
            shortId = "",
            flow = "",
        )
    }
}
