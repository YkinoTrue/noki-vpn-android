package com.noki.vpn

import android.os.SystemClock
import com.noki.vpn.data.BillingCycle
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile

internal fun isCurrentServerSelection(profile: UserProfile, code: String, mode: ServerSelectionMode): Boolean =
    profile.serverSelectionMode == mode && when (mode) {
        ServerSelectionMode.AUTO -> true
        ServerSelectionMode.COUNTRY -> profile.selectedCountryCode.equals(code.trim(), ignoreCase = true)
        ServerSelectionMode.SERVER -> profile.selectedNodeId == code.trim()
    }

private const val SERVER_STATS_REFRESH_MIN_INTERVAL_MS = 60_000L

internal fun shouldRefreshServerStats(
    lastSuccessElapsedMs: Long,
    nowElapsedMs: Long,
    minIntervalMs: Long,
): Boolean = lastSuccessElapsedMs <= 0L ||
    nowElapsedMs < lastSuccessElapsedMs ||
    nowElapsedMs - lastSuccessElapsedMs >= minIntervalMs

internal fun AppUiRuntime.setBillingCycle(cycle: BillingCycle) {
    uiState = uiState.copy(billingCycle = cycle)
}

internal fun AppUiRuntime.selectServer(code: String, mode: ServerSelectionMode = ServerSelectionMode.COUNTRY) {
    val selectedCode = code.trim()
    if (isCurrentServerSelection(uiState.userProfile, selectedCode, mode)) return
    if (confirmedServerCode(AppDialog.ChangeServer(selectedCode, mode), uiState.locations) == null) return
    val node = if (mode == ServerSelectionMode.SERVER) {
        uiState.locations.flatMap { it.servers }.firstOrNull { it.id == selectedCode } ?: return
    } else null

    val preChangeConnectionState = uiState.connectionState
    val staleEndpointOptionsRefresh = endpointOptionsRefreshJob
    endpointOptionsRefreshJob = null
    endpointOptionsRefreshCountryCode = null
    staleEndpointOptionsRefresh?.cancel()
    val persisted = settingsMutationCoordinator.persistServerSelection(
        countryCode = node?.countryCode ?: selectedCode,
        mode = mode,
        nodeId = node?.id.orEmpty(),
    )
    val next = uiState.copy(
        userProfile = persisted.userProfile,
        profile = persisted.profile,
        endpointOptions = persisted.endpointOptions,
        endpointOptionsCountryCode = null,
        inlineMessage = tr(
            uiState.personalizationSettings.language,
            "Сервер выбран",
            "Server selected",
        ),
    )
    uiState = next
    when (preChangeConnectionState) {
        VpnConnectionState.CONNECTED,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.FAILED,
        -> vpnCommands.restart()
        VpnConnectionState.DISCONNECTED -> Unit
    }
}

internal fun AppUiRuntime.refreshServers() {
    refreshAllData(showNetworkFailureInline = true)
}

internal fun AppUiRuntime.refreshServerStats() {
    if (uiState.connectionState == VpnConnectionState.CONNECTED) vpnCommands.refreshLatency()
    if (authSessionCoordinator.attempt() == null) return
    // Local probes must not wait for the separately throttled backend refresh.
    refreshClientLatenciesAsync(uiState.locations, refreshCached = true)
    val now = SystemClock.elapsedRealtime()
    if (!shouldRefreshServerStats(
            lastSuccessElapsedMs = lastServerStatsRefreshElapsedMs,
            nowElapsedMs = now,
            minIntervalMs = SERVER_STATS_REFRESH_MIN_INTERVAL_MS,
        )
    ) {
        return
    }
    launchBackendRefresh(BackendRefreshTrigger.Stats)
}

internal fun AppUiRuntime.refreshOfflineStats() {
    uiState = uiState.copy(dailyStats = repository.loadDailyStats())
}

internal fun AppUiRuntime.refreshAllData(
    showNetworkFailureInline: Boolean = true,
    refreshClientLatency: Boolean = false,
) {
    if (uiState.connectionState == VpnConnectionState.CONNECTED) vpnCommands.refreshLatency()
    launchBackendRefresh(
        trigger = BackendRefreshTrigger.UserRefresh,
        showNetworkFailureInline = showNetworkFailureInline,
        refreshClientLatency = refreshClientLatency,
    )
}
