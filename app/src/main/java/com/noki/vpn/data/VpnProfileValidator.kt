package com.noki.vpn.data

import java.util.Locale
import java.util.UUID

object VpnProfileValidator {
    fun isUsable(
        settings: StoredSettings,
    ): Boolean = rejectionReason(settings) == null

    fun rejectionReason(settings: StoredSettings): String? {
        return rejectionReason(
            profile = settings.profile,
            advancedSettings = settings.advancedSettings,
            selectedLocationCode = settings.userProfile.selectedServerCode,
            endpointOptions = settings.endpointOptions,
        )
    }

    fun isUsable(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String = "",
        endpointOptions: List<VpnEndpointOption> = emptyList(),
    ): Boolean = rejectionReason(profile, advancedSettings, selectedLocationCode, endpointOptions) == null

    private fun rejectionReason(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): String? {
        val expectedSecurity = advancedSettings.protocol.name.lowercase(Locale.ROOT)
        val normalizedSecurity = profile.security.lowercase(Locale.ROOT)
        if (expectedSecurity != "auto" && normalizedSecurity != expectedSecurity) return "protocol_mismatch"

        if (advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL) {
            val manualGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
                settings = advancedSettings,
                endpointOptions = endpointOptions,
                profile = profile,
            )
            if (manualGroupKey.isNotBlank()) {
                val profileGroupKey = endpointOptions.firstOrNull { it.code == profile.endpointCode }
                    ?.let(EndpointGroupPolicy::groupKey)
                if (profileGroupKey != null && profileGroupKey != manualGroupKey) return "manual_group_mismatch"
            } else {
                val manualCode = advancedSettings.manualEndpointCode.trim()
                if (manualCode.isNotBlank() && profile.endpointCode != manualCode) return "manual_endpoint_mismatch"
            }
        }

        val endpointOption = endpointOptions.firstOrNull { it.code == profile.endpointCode }
        val selectedCode = selectedLocationCode.trim()
        if (endpointOption != null &&
            selectedCode.isNotBlank() &&
            endpointOption.locationCode.isNotBlank() &&
            !endpointOption.locationCode.equals(selectedCode, ignoreCase = true)
        ) {
            return "location_mismatch"
        }

        if (profile.host.isBlank()) return "missing_host"
        if (profile.port.isBlank()) return "missing_port"
        if (runCatching { UUID.fromString(profile.uuid) }.isFailure) return "invalid_credential"
        return if (EndpointSecurityPolicy.isAllowedProfile(profile)) null else "security_policy_rejected"
    }
}
