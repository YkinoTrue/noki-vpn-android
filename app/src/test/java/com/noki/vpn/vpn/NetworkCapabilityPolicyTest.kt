package com.noki.vpn.vpn

import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCapabilityPolicyTest {
    @Test
    fun api26And27DoNotRequireCapabilityIntroducedInApi28() {
        assertTrue(NetworkCapabilityPolicy.isNotSuspended(sdkInt = 26, capabilityPresent = false))
        assertTrue(NetworkCapabilityPolicy.isNotSuspended(sdkInt = 27, capabilityPresent = false))
    }

    @Test
    fun api28AndNewerUseReportedCapability() {
        assertFalse(NetworkCapabilityPolicy.isNotSuspended(sdkInt = 28, capabilityPresent = false))
        assertTrue(NetworkCapabilityPolicy.isNotSuspended(sdkInt = 35, capabilityPresent = true))
    }

    @Test
    fun activeVpnConflictsWhileNokiIsDisconnectedOrFailed() {
        assertTrue(
            NetworkCapabilityPolicy.hasCompetingVpn(
                activeNetworkUsesVpn = true,
                nokiState = VpnConnectionState.DISCONNECTED,
            ),
        )
        assertTrue(
            NetworkCapabilityPolicy.hasCompetingVpn(
                activeNetworkUsesVpn = true,
                nokiState = VpnConnectionState.FAILED,
            ),
        )
        assertFalse(
            NetworkCapabilityPolicy.hasCompetingVpn(
                activeNetworkUsesVpn = false,
                nokiState = VpnConnectionState.DISCONNECTED,
            ),
        )
        assertFalse(
            NetworkCapabilityPolicy.hasCompetingVpn(
                activeNetworkUsesVpn = true,
                nokiState = VpnConnectionState.CONNECTED,
            ),
        )
    }
}
