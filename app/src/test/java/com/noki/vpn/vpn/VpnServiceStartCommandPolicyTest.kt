package com.noki.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnServiceStartCommandPolicyTest {
    @Test
    fun startDuringStopIsQueuedUntilCleanupCompletes() {
        assertEquals(
            VpnServiceStartCommandPolicy.ActiveStartDecision.QueueAfterCleanup,
            VpnServiceStartCommandPolicy.activeStartDecision(
                VpnConnectionOperation.STOP,
            ),
        )
    }

    @Test
    fun startDuringRestartCoalescesWithRestartStart() {
        assertEquals(
            VpnServiceStartCommandPolicy.ActiveStartDecision.CoalesceWithRestart,
            VpnServiceStartCommandPolicy.activeStartDecision(
                VpnConnectionOperation.RESTART,
            ),
        )
    }

    @Test
    fun systemAlwaysOnIsFreshFirstWithCachedFallback() {
        val options = VpnServiceStartCommandPolicy.startOptions(
            isNullIntent = false,
            action = "android.net.VpnService",
            refreshSessionExtra = false,
        )

        assertEquals(VpnPreparationStrategy.FreshWithCachedFallback, options.strategy)
        assertEquals(VpnRuntimeMode.ACCOUNT, options.runtimeMode)
    }

    @Test
    fun stickyRestartIsFreshFirstWithCachedFallback() {
        val options = VpnServiceStartCommandPolicy.startOptions(
            isNullIntent = true,
            action = null,
            refreshSessionExtra = false,
        )

        assertEquals(VpnPreparationStrategy.FreshWithCachedFallback, options.strategy)
        assertEquals(VpnRuntimeMode.ACCOUNT, options.runtimeMode)
    }

    @Test
    fun manualRefreshUsesCachedProfileWhenFreshBackendIsTransientlyUnavailable() {
        val options = VpnServiceStartCommandPolicy.startOptions(
            isNullIntent = false,
            action = "com.noki.vpn.START",
            refreshSessionExtra = true,
        )

        assertEquals(VpnPreparationStrategy.FreshWithCachedFallback, options.strategy)
        assertEquals(VpnRuntimeMode.ACCOUNT, options.runtimeMode)
    }

    @Test
    fun temporaryStartUsesIsolatedAuthTempRuntime() {
        val options = VpnServiceStartCommandPolicy.startOptions(
            isNullIntent = false,
            action = "com.noki.vpn.START_TEMPORARY",
            refreshSessionExtra = false,
        )

        assertEquals(VpnRuntimeMode.AUTH_TEMP, options.runtimeMode)
        assertEquals(VpnPreparationStrategy.FreshOnly, options.strategy)
    }

    @Test
    fun stopAndRevokeTemporaryStopsOnlyAuthTempRuntime() {
        assertEquals(
            VpnServiceStartCommandPolicy.TemporaryStopDecision.StopAndRevoke,
            VpnServiceStartCommandPolicy.temporaryStopDecision(
                action = "com.noki.vpn.STOP_AND_REVOKE_AUTH_TEMP",
                runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            ),
        )
        assertEquals(
            VpnServiceStartCommandPolicy.TemporaryStopDecision.RevokeOnly,
            VpnServiceStartCommandPolicy.temporaryStopDecision(
                action = "com.noki.vpn.STOP_AND_REVOKE_AUTH_TEMP",
                runtimeMode = VpnRuntimeMode.ACCOUNT,
            ),
        )
        assertEquals(
            VpnServiceStartCommandPolicy.TemporaryStopDecision.NotRequested,
            VpnServiceStartCommandPolicy.temporaryStopDecision(
                action = "com.noki.vpn.STOP",
                runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            ),
        )
    }
}
