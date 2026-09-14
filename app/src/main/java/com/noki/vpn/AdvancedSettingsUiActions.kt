package com.noki.vpn

import android.content.Context
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppDiagnosticLogPolicy
import com.noki.vpn.data.EndpointHealthEvents
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.VpnSessionCoordinator
import com.noki.vpn.vpn.AppVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal fun shouldRefreshEndpointOptions(
    force: Boolean,
    optionsCount: Int,
    loadedCountryCode: String?,
    selectedCountryCode: String?,
): Boolean = force || optionsCount <= 1 || loadedCountryCode != selectedCountryCode

internal fun isManualEndpointSelectable(
    option: VpnEndpointOption,
    endpointOptions: List<VpnEndpointOption>,
    loadedCountryCode: String?,
    selectedCountryCode: String?,
): Boolean = !selectedCountryCode.isNullOrBlank() &&
    loadedCountryCode.equals(selectedCountryCode, ignoreCase = true) &&
    option in EndpointGroupPolicy.manualOptions(endpointOptions)

internal fun manualEndpointOptionsForCurrentCountry(state: AppUiState): List<VpnEndpointOption> {
    if (!state.endpointOptionsCountryCode.equals(
            state.userProfile.selectedCountryCode,
            ignoreCase = true,
        )
    ) {
        return emptyList()
    }
    return EndpointGroupPolicy.manualOptions(state.endpointOptions)
}

internal fun automaticLogUploadEffect(
    previous: AdvancedSettings,
    next: AdvancedSettings,
): SettingsEffect = if (
    !AppDiagnosticLogPolicy.shouldUploadAutomatically(previous) &&
    AppDiagnosticLogPolicy.shouldUploadAutomatically(next)
) {
    SettingsEffect.StartAutomaticLogUpload
} else {
    SettingsEffect.None
}

internal fun AppUiRuntime.updateFilterMode(mode: AppFilterMode) {
    applyAndPersist(AdvancedSettingsStateReducer.setFilterMode(uiState, mode))
}

internal fun AppUiRuntime.togglePackageSelection(packageName: String) {
    applyAndPersist(AdvancedSettingsStateReducer.togglePackageSelection(uiState, packageName))
}

internal fun AppUiRuntime.clearPackageSelection() {
    applyAndPersist(AdvancedSettingsStateReducer.clearPackageSelection(uiState))
}

internal fun AppUiRuntime.applyAppRoutingSettings() {
    val state = uiState.connectionState
    if (state != VpnConnectionState.CONNECTED && state != VpnConnectionState.CONNECTING) return
    recordAppLog("vpn", message = "app_routing_apply_restart")
    vpnCommands.restart()
}

internal fun AppUiRuntime.setYoutubeDirectDpiEnabled(enabled: Boolean) {
    val next = AdvancedSettingsStateReducer.setYoutubeDirectDpiEnabled(uiState, enabled)
    applyAndPersist(next)
    applyAppRoutingSettings()
}

internal fun AppUiRuntime.toggleBiometric(enabled: Boolean) {
    applyAndPersist(AdvancedSettingsStateReducer.setBiometric(uiState, enabled))
}

internal fun AppUiRuntime.toggleLoginAlerts(enabled: Boolean) {
    applyAndPersist(AdvancedSettingsStateReducer.setLoginAlerts(uiState, enabled))
}

internal fun AppUiRuntime.toggleProtectNewDevices(enabled: Boolean) {
    applyAndPersist(AdvancedSettingsStateReducer.setProtectNewDevices(uiState, enabled))
}

internal fun AppUiRuntime.changeProtocol(protocol: VpnProtocol) {
    val next = AdvancedSettingsStateReducer.changeProtocol(uiState, protocol)
    val persisted = settingsMutationCoordinator.persistProtocolChange(next, protocol)
    uiState = withFreeTrafficLimitNotice(next.copy(profile = persisted.profile))
}

internal fun AppUiRuntime.toggleAutoEndpointSelection(enabled: Boolean) {
    val next = AdvancedSettingsStateReducer.setAutoEndpointSelection(uiState, enabled)
    val persisted = settingsMutationCoordinator.persistAutoEndpointSelection(next)
    uiState = withFreeTrafficLimitNotice(next.copy(profile = persisted.profile))
}

internal fun AppUiRuntime.setKillSwitchEnabled(enabled: Boolean) {
    applyAndPersist(uiState.copy(advancedSettings = uiState.advancedSettings.copy(killSwitchEnabled = enabled)))
    if (!enabled && AppVpnService.liveRuntimeState().state == VpnConnectionState.FAILED) {
        vpnCommands.restart()
    }
}

internal fun AppUiRuntime.refreshEndpointOptions(
    context: Context,
    force: Boolean = false,
) {
    val attempt = authSessionCoordinator.attempt() ?: return
    val selectedCountryCode = uiState.userProfile.selectedCountryCode
    val selectedMode = uiState.userProfile.serverSelectionMode
    val selectedNodeId = uiState.userProfile.selectedNodeId
    if (!shouldRefreshEndpointOptions(
            force = force,
            optionsCount = uiState.endpointOptions.size,
            loadedCountryCode = uiState.endpointOptionsCountryCode,
            selectedCountryCode = selectedCountryCode,
        )
    ) {
        return
    }
    val activeJob = endpointOptionsRefreshJob
    if (activeJob != null) {
        if (endpointOptionsRefreshCountryCode == selectedCountryCode) return
        endpointOptionsRefreshJob = null
        endpointOptionsRefreshCountryCode = null
        activeJob.cancel()
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Endpoint options coroutine has no Job")
        try {
            val result = authSessionCoordinator.run(attempt) { token ->
                VpnSessionCoordinator(
                    context = context.applicationContext,
                    repository = repository,
                    backendApi = backendApi,
                ).endpointOptions(
                    token = token,
                    settings = repository.load(),
                    knownDevices = syncedDevices,
                )
            }
            if (
                endpointOptionsRefreshJob !== ownerJob ||
                !ownerJob.isActive ||
                !authSessionCoordinator.isCurrent(attempt) ||
                uiState.userProfile.selectedCountryCode != selectedCountryCode ||
                uiState.userProfile.serverSelectionMode != selectedMode ||
                uiState.userProfile.selectedNodeId != selectedNodeId
            ) {
                return@launch
            }
            val reduced = EndpointOptionsStateReducer.applyEndpointOptions(
                current = uiState,
                result = result,
                syncedDevices = syncedDevices,
            ) ?: return@launch
            backendDeviceId = result.backendDeviceId
            backendDeviceKey = result.backendDeviceKey
            backendDeviceAccessRole = result.backendDeviceAccessRole
            syncedDevices = reduced.syncedDevices
            val loadedState = reduced.state.copy(endpointOptionsCountryCode = selectedCountryCode)
            persistBackendState(loadedState)
            applyAndPersist(loadedState)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (
                endpointOptionsRefreshJob === ownerJob &&
                ownerJob.isActive &&
                authSessionCoordinator.isCurrent(attempt)
            ) {
                recordAppLog(
                    category = "vpn",
                    level = "error",
                    message = "endpoint_options_refresh_fail",
                    details = error.message,
                    errorType = AppErrorMapper.readableErrorType(error),
                )
            }
        } finally {
            if (endpointOptionsRefreshJob === ownerJob) {
                endpointOptionsRefreshJob = null
                endpointOptionsRefreshCountryCode = null
            }
        }
    }
    endpointOptionsRefreshCountryCode = selectedCountryCode
    endpointOptionsRefreshJob = job
    job.start()
}

internal fun AppUiRuntime.selectManualEndpoint(option: VpnEndpointOption) {
    if (!isManualEndpointSelectable(
            option = option,
            endpointOptions = uiState.endpointOptions,
            loadedCountryCode = uiState.endpointOptionsCountryCode,
            selectedCountryCode = uiState.userProfile.selectedCountryCode,
        )
    ) {
        return
    }
    val next = AdvancedSettingsStateReducer.selectManualEndpoint(uiState, option)
    val persisted = settingsMutationCoordinator.persistManualEndpointSelection(next, option)
    uiState = withFreeTrafficLimitNotice(next.copy(profile = persisted.profile))
}

internal fun AppUiRuntime.setLoggingEnabled(enabled: Boolean) {
    val next = AdvancedSettingsStateReducer.setAllLogging(uiState, enabled)
    applyAndPersist(next, automaticLogUploadEffect(uiState.advancedSettings, next.advancedSettings))
    applyLoggingPrivacyState(next.advancedSettings)
}

internal fun AppUiRuntime.toggleAnonymousLogs(enabled: Boolean) {
    val next = AdvancedSettingsStateReducer.setAnonymousLogs(uiState, enabled)
    applyAndPersist(next, automaticLogUploadEffect(uiState.advancedSettings, next.advancedSettings))
    applyLoggingPrivacyState(next.advancedSettings)
}

internal fun AppUiRuntime.updateAlwaysOnInput(value: String) {
    uiState = AdvancedSettingsStateReducer.setAlwaysOnInput(uiState, value)
}

internal fun AppUiRuntime.updateBypassInput(value: String) {
    uiState = AdvancedSettingsStateReducer.setBypassInput(uiState, value)
}

internal fun AppUiRuntime.addAlwaysOnDomain() {
    addAlwaysOnDomain(uiState.alwaysOnInput)
}

internal fun AppUiRuntime.addAlwaysOnDomain(value: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.addAlwaysOnDomain(uiState, value))
}

internal fun AppUiRuntime.addBypassDomain() {
    addBypassDomain(uiState.bypassInput)
}

internal fun AppUiRuntime.addBypassDomain(value: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.addBypassDomain(uiState, value))
}

internal fun AppUiRuntime.updateAlwaysOnDomain(oldDomain: String, newDomain: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.updateAlwaysOnDomain(uiState, oldDomain, newDomain))
}

internal fun AppUiRuntime.removeAlwaysOnDomain(domain: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.removeAlwaysOnDomain(uiState, domain))
}

internal fun AppUiRuntime.removeBypassDomain(domain: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.removeBypassDomain(uiState, domain))
}

internal fun AppUiRuntime.updateBypassDomain(oldDomain: String, newDomain: String) {
    persistAndApplyDomainRules(AdvancedSettingsStateReducer.updateBypassDomain(uiState, oldDomain, newDomain))
}

internal fun AppUiRuntime.persistAndApplyDomainRules(newState: AppUiState) {
    val effect = if (AppVpnService.liveRuntimeState().state == VpnConnectionState.CONNECTED) {
        SettingsEffect.ApplyRuntimeSettings
    } else {
        SettingsEffect.None
    }
    applyAndPersist(newState, effect)
}

internal fun AppUiRuntime.applyLoggingPrivacyState(settings: AdvancedSettings) {
    if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(settings)) {
        cancelAutomaticLogUpload()
    }
    if (!EndpointHealthEvents.generalLoggingEnabled(settings)) {
        repository.saveEndpointHealthEventQueue(emptyList())
    }
    if (!AppDiagnosticLogPolicy.shouldStoreAppLog(settings)) {
        repository.clearAppLogs()
    }
}
