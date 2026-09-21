package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DailyStats
import com.noki.vpn.data.VpnConnectionState

internal object ConnectionStateReducer {
    enum class RuntimeAction {
        None,
        Clear,
    }

    data class LogAction(
        val category: String,
        val level: String = "info",
        val message: String,
        val details: String? = null,
        val errorType: String? = null,
        val connectionSuccess: Boolean? = null,
        val endpointRating: String? = null,
    )

    sealed interface Transition {
        data class Normal(
            val state: AppUiState,
            val runtimeAction: RuntimeAction,
            val logAction: LogAction?,
        ) : Transition

        data class TrafficLimit(val state: AppUiState) : Transition

        data class DeviceLimit(val state: AppUiState) : Transition

        data class EmptySelectedApps(val state: AppUiState) : Transition
    }

    fun reduce(
        current: AppUiState,
        state: VpnConnectionState,
        reason: ConnectionReason,
        connectedAtMillis: Long?,
        nowMillis: Long,
        dailyStats: List<DailyStats>,
        endpointRating: String,
    ): Transition {
        val language = current.personalizationSettings.language
        val rawReason = reason.raw
        if (
            state == VpnConnectionState.DISCONNECTED &&
            current.connectionState == VpnConnectionState.FAILED &&
            rawReason.isBlank()
        ) {
            return Transition.Normal(
                state = current.copy(dailyStats = dailyStats),
                runtimeAction = RuntimeAction.Clear,
                logAction = null,
            )
        }
        if (state == VpnConnectionState.FAILED && reason is ConnectionReason.TrafficLimit) {
            return Transition.TrafficLimit(
                current.copy(
                    connectionState = VpnConnectionState.FAILED,
                    connectedAtMillis = null,
                    connectionReason = tr(language, "Бесплатный трафик закончился", "Free traffic limit reached"),
                    inlineMessage = null,
                    userProfile = current.userProfile.copy(subscriptionStatus = "limited"),
                ),
            )
        }
        if (state == VpnConnectionState.FAILED && AppErrorMapper.isDeviceLimitReason(rawReason)) {
            return Transition.DeviceLimit(
                current.copy(
                    connectionState = VpnConnectionState.FAILED,
                    connectedAtMillis = null,
                    connectionReason = AppErrorMapper.deviceLimitMessage(language),
                    inlineMessage = null,
                ),
            )
        }
        if (state == VpnConnectionState.FAILED && reason is ConnectionReason.EmptySelectedApps) {
            return Transition.EmptySelectedApps(
                current.copy(
                    connectionState = VpnConnectionState.FAILED,
                    connectedAtMillis = null,
                    connectionReason = AppErrorMapper.localizeConnectionReason(language, rawReason),
                    inlineMessage = null,
                    dialog = AppDialog.EmptySelectedApps,
                    dailyStats = dailyStats,
                ),
            )
        }

        val nextConnectedAtMillis = when (state) {
            VpnConnectionState.CONNECTED -> connectedAtMillis ?: current.connectedAtMillis ?: nowMillis
            VpnConnectionState.CONNECTING -> current.connectedAtMillis
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> null
        }
        val nextState = current.copy(
            connectionState = state,
            connectedAtMillis = nextConnectedAtMillis,
            connectionReason = if (state == VpnConnectionState.DISCONNECTED) "" else {
                AppErrorMapper.localizeConnectionReason(language, rawReason)
            },
            inlineMessage = inlineMessage(language, state, rawReason),
            dailyStats = dailyStats,
        )
        return Transition.Normal(
            state = nextState,
            runtimeAction = runtimeAction(state),
            logAction = logAction(state, rawReason, endpointRating),
        )
    }

    private fun inlineMessage(
        language: AppLanguage,
        state: VpnConnectionState,
        reason: String,
    ): String? {
        return when (state) {
            VpnConnectionState.CONNECTED -> tr(language, "VPN подключен", "VPN connected")
            VpnConnectionState.FAILED -> AppErrorMapper.readableVpnError(language, reason)
            VpnConnectionState.CONNECTING -> tr(language, "Подключение…", "Connecting…")
            VpnConnectionState.DISCONNECTED -> null
        }
    }

    private fun runtimeAction(state: VpnConnectionState): RuntimeAction {
        return when (state) {
            VpnConnectionState.CONNECTED -> RuntimeAction.None
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> RuntimeAction.Clear
            VpnConnectionState.CONNECTING -> RuntimeAction.None
        }
    }

    private fun logAction(
        state: VpnConnectionState,
        reason: String,
        endpointRating: String,
    ): LogAction? {
        return when (state) {
            VpnConnectionState.CONNECTED -> LogAction(
                category = "vpn",
                message = "connect_success",
                connectionSuccess = true,
                endpointRating = endpointRating,
            )
            VpnConnectionState.FAILED -> LogAction(
                category = "vpn",
                level = "error",
                message = "connect_fail",
                details = reason,
                errorType = reason.ifBlank { "connect_fail" },
                connectionSuccess = false,
                endpointRating = endpointRating,
            )
            VpnConnectionState.DISCONNECTED -> LogAction(
                category = "vpn",
                message = "disconnect",
            )
            VpnConnectionState.CONNECTING -> null
        }
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
