package com.noki.vpn.ui

import com.noki.vpn.AppUiState
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.VpnEndpointOption

internal fun selectedEndpointLabel(state: AppUiState): String {
    return resolvedEndpointLabel(state)
        ?: state.profile.endpointCode.ifBlank { "Endpoint not selected" }
}

internal fun protocolCardLabel(
    state: AppUiState,
    autoEndpointSelection: Boolean,
): String {
    if (state.advancedSettings.ruRelayEnabled) {
        return if (state.personalizationSettings.language == AppLanguage.RU) {
            "WireGuard · RU-реле"
        } else {
            "WireGuard · RU relay"
        }
    }
    if (!autoEndpointSelection) return selectedEndpointLabel(state)
    val endpointLabel = resolvedEndpointLabel(state) ?: profileEndpointLabel(state)
    return endpointLabel?.let { "Auto ($it)" } ?: "Auto"
}

private fun resolvedEndpointLabel(state: AppUiState): String? {
    val key = selectedEndpointGroupKey(state)
    val option = EndpointGroupPolicy.manualOptions(state.endpointOptions)
        .firstOrNull { EndpointGroupPolicy.groupKey(it) == key }
    return option?.let { endpointDisplayName(it) }
}

private fun profileEndpointLabel(state: AppUiState): String? {
    if (state.profile.endpointCode.isBlank()) return null
    return EndpointGroupPolicy.displayLabelFor(optionFromProfile(state))
}

internal fun selectedEndpointGroupKey(state: AppUiState): String =
    EndpointGroupPolicy.resolveManualGroupKey(
        settings = state.advancedSettings,
        endpointOptions = state.endpointOptions,
        profile = state.profile,
    )

internal fun optionFromProfile(state: AppUiState): VpnEndpointOption {
    val profile = state.profile
    return VpnEndpointOption(
        code = profile.endpointCode.ifBlank { "current" },
        label = profile.remark,
        locationCode = state.userProfile.selectedServerCode,
        locationName = profile.remark,
        host = profile.host,
        port = profile.port.toIntOrNull() ?: 443,
        proxyType = profile.proxyType,
        transport = profile.transport,
        transportMode = profile.transportMode,
        security = profile.security,
    )
}

internal fun endpointDisplayName(endpoint: VpnEndpointOption): String {
    return endpoint.label.ifBlank { endpoint.code.ifBlank { endpoint.host } }
}
