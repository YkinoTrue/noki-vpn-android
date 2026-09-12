package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EndpointStartupPreflightPolicyTest {
    @Test
    fun autoReachesHysteriaAfterAllTcpFailuresInOneSelectionOnEveryNetwork() {
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val checked = mutableListOf<String>()
            val result = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                candidates = (1..4).map { tcpCandidate("tcp$it") } + hysteriaCandidate("hy2"),
                health = emptyMap(),
                networkKind = network,
                nowMillis = 0L,
                rotationIndex = { 0 },
                canReach = { checked += it.code; false },
            )

            assertEquals(network.name, "hy2", result.selected?.code)
            assertEquals(setOf("tcp1", "tcp2", "tcp3", "tcp4"), checked.toSet())
            assertEquals(4, checked.size)
            assertFalse(result.shouldPenalizeFailedTcp)
        }
    }

    @Test
    fun unprovenHysteriaDoesNotValidateEarlierTcpFailures() {
        val result = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
            candidates = listOf(tcpCandidate("tcp"), hysteriaCandidate("hy2")),
            health = emptyMap(),
            networkKind = EndpointRankingPolicy.NetworkKind.CELLULAR,
            nowMillis = 0L,
            rotationIndex = { 0 },
            canReach = { false },
        )

        assertEquals("hy2", result.selected?.code)
        assertFalse(result.shouldPenalizeFailedTcp)
    }
}
