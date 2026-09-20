package com.noki.vpn.vpn

enum class VpnRuntimeMode {
    ACCOUNT,
    AUTH_TEMP,
}

object VpnServiceStartCommandPolicy {
    enum class ActiveStartDecision { QueueAfterCleanup, CoalesceWithRestart, Ignore }

    enum class TemporaryStopDecision { NotRequested, StopAndRevoke, RevokeOnly }

    internal fun activeStartDecision(operation: VpnConnectionOperation?): ActiveStartDecision = when (operation) {
        VpnConnectionOperation.STOP -> ActiveStartDecision.QueueAfterCleanup
        VpnConnectionOperation.RESTART -> ActiveStartDecision.CoalesceWithRestart
        VpnConnectionOperation.CONNECTION, null -> ActiveStartDecision.Ignore
    }

    data class StartOptions(
        val strategy: VpnPreparationStrategy,
        val runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
    )

    fun startOptions(
        isNullIntent: Boolean,
        action: String?,
        refreshSessionExtra: Boolean,
    ): StartOptions {
        if (action == TEMPORARY_VPN_ACTION) {
            return StartOptions(
                strategy = VpnPreparationStrategy.FreshOnly,
                runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            )
        }
        val isSystemAlwaysOn = action == SYSTEM_VPN_SERVICE_ACTION
        val forceRefreshSession = isNullIntent || isSystemAlwaysOn || refreshSessionExtra
        return StartOptions(
            strategy = if (forceRefreshSession) VpnPreparationStrategy.FreshWithCachedFallback else VpnPreparationStrategy.CachedFirst,
            runtimeMode = VpnRuntimeMode.ACCOUNT,
        )
    }

    fun temporaryStopDecision(
        action: String?,
        runtimeMode: VpnRuntimeMode,
    ): TemporaryStopDecision {
        if (action != STOP_AND_REVOKE_TEMPORARY_ACTION) return TemporaryStopDecision.NotRequested
        return if (runtimeMode == VpnRuntimeMode.AUTH_TEMP) {
            TemporaryStopDecision.StopAndRevoke
        } else {
            TemporaryStopDecision.RevokeOnly
        }
    }

    private const val SYSTEM_VPN_SERVICE_ACTION = "android.net.VpnService"
    private const val TEMPORARY_VPN_ACTION = "com.noki.vpn.START_TEMPORARY"
    private const val STOP_AND_REVOKE_TEMPORARY_ACTION =
        "com.noki.vpn.STOP_AND_REVOKE_AUTH_TEMP"
}
