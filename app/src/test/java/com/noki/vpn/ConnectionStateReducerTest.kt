package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateReducerTest {
    @Test
    fun explicitStopClearsPreviousNetworkWait() {
        val stopped = ConnectionStateReducer.reduce(
            current = AppUiState(connectionState = VpnConnectionState.FAILED, connectionReason = "Ожидание сети"),
            state = VpnConnectionState.DISCONNECTED,
            reason = ConnectionReason.Unknown("user_stop"),
            connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
        ) as ConnectionStateReducer.Transition.Normal
        assertEquals(VpnConnectionState.DISCONNECTED, stopped.state.connectionState)
        assertEquals("", stopped.state.connectionReason)
        assertEquals(null, stopped.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, stopped.runtimeAction)
    }

    @Test
    fun unavailableUnderlayExplainsNetworkWaitInBothLanguages() {
        for ((language, label) in listOf(AppLanguage.RU to "Ожидание сети", AppLanguage.EN to "Waiting for network")) {
            val result = ConnectionStateReducer.reduce(
                current = AppUiState(personalizationSettings = PersonalizationSettings(language = language)),
                state = VpnConnectionState.FAILED,
                reason = ConnectionReason.Unknown("underlay_unavailable"),
                connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
            ) as ConnectionStateReducer.Transition.Normal
            assertEquals(label, result.state.connectionReason)
            assertTrue(result.state.inlineMessage.orEmpty().isNotBlank())
            assertTrue(!result.state.inlineMessage.orEmpty().contains("underlay_unavailable"))
        }
    }

    @Test
    fun trafficLimitFailureRequestsTrafficLimitHandling() {
        val transition = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
                userProfile = UserProfile(subscriptionStatus = "active"),
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.TrafficLimit,
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        )

        assertTrue(transition is ConnectionStateReducer.Transition.TrafficLimit)
        val trafficLimit = transition as ConnectionStateReducer.Transition.TrafficLimit
        assertEquals(VpnConnectionState.FAILED, trafficLimit.state.connectionState)
        assertEquals("limited", trafficLimit.state.userProfile.subscriptionStatus)
        assertEquals("Free traffic limit reached", trafficLimit.state.connectionReason)
    }

    @Test
    fun failedStateClearsRuntimeStateAndLogsFailure() {
        val transition = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
                connectedAtMillis = 1234L,
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.Unknown("core_start_error"),
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        )

        assertTrue(transition is ConnectionStateReducer.Transition.Normal)
        val normal = transition as ConnectionStateReducer.Transition.Normal
        assertEquals(null, normal.state.connectedAtMillis)
        assertEquals("Core start error", normal.state.connectionReason)
        assertEquals("Failed to start VPN runtime", normal.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, normal.runtimeAction)
        assertEquals("connect_fail", normal.logAction?.message)
        assertEquals("core_start_error", normal.logAction?.errorType)
    }

    @Test
    fun disconnectedCleanupDoesNotEraseShownFailure() {
        val failed = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.Unknown("core_start_error"),
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        ) as ConnectionStateReducer.Transition.Normal

        val cleanup = ConnectionStateReducer.reduce(
            current = failed.state,
            state = VpnConnectionState.DISCONNECTED,
            reason = ConnectionReason.Unknown(""),
            connectedAtMillis = null,
            nowMillis = 10000L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        ) as ConnectionStateReducer.Transition.Normal

        assertEquals(VpnConnectionState.FAILED, cleanup.state.connectionState)
        assertEquals("Core start error", cleanup.state.connectionReason)
        assertEquals("Failed to start VPN runtime", cleanup.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, cleanup.runtimeAction)
        assertEquals(null, cleanup.logAction)
    }
}
