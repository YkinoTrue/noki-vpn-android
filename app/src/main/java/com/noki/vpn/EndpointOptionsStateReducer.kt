package com.noki.vpn

import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.VpnSessionCoordinator

object EndpointOptionsStateReducer {
    data class Result(
        val state: AppUiState,
        val syncedDevices: List<BackendDevice>,
    )

    fun applyEndpointOptions(
        current: AppUiState,
        result: VpnSessionCoordinator.EndpointOptionsResult,
        syncedDevices: List<BackendDevice>,
    ): Result? {
        if (current.advancedSettings.ruRelayEnabled) return null
        val options = result.endpointOptions
        if (options.isEmpty()) return null

        val manualMode = current.advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL
        val selectedOption = selectedOption(
            current = current,
            options = options,
        )
        val nextAdvanced = if (manualMode) {
            current.advancedSettings.copy(
                protocol = VpnProtocol.fromBackendCode(selectedOption.security),
                manualEndpointCode = selectedOption.code,
                manualEndpointGroupKey = EndpointGroupPolicy.groupKey(selectedOption),
            )
        } else {
            current.advancedSettings
        }
        val nextProfile = if (manualMode) {
            RuntimeProfilePolicy.profileForManualEndpoint(current.profile, selectedOption)
        } else {
            current.profile
        }
        return Result(
            state = current.copy(
                advancedSettings = nextAdvanced,
                endpointOptions = options,
                profile = nextProfile,
                userProfile = current.userProfile.copy(
                    selectedServerCode = result.session.locationCode,
                ),
                currentDeviceAccessRole = result.backendDeviceAccessRole,
            ),
            syncedDevices = syncedDevices.filterNot { it.deviceKey == result.currentDevice.deviceKey } + result.currentDevice,
        )
    }

    private fun selectedOption(
        current: AppUiState,
        options: List<VpnEndpointOption>,
    ): VpnEndpointOption {
        val currentGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
            settings = current.advancedSettings,
            endpointOptions = options,
            profile = current.profile,
        )
        return options.firstOrNull { EndpointGroupPolicy.groupKey(it) == currentGroupKey }
            ?: options.first()
    }
}
