package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTrafficMonitorTest {
    @Test
    fun trafficCountsBothDirectionsPreservesReconnectAndResetsForANewConnection() {
        val first = DeviceTrafficSnapshot().forSession(100L)
            .withTraffic(rxDelta = 1_000_000L, txDelta = 500_000L, elapsedSeconds = 2.0)
        assertEquals(1_500_000L, first.sessionBytes)
        assertEquals(4.0, first.downloadMbps!!, 0.001)
        assertEquals(2.0, first.uploadMbps!!, 0.001)
        val reconnected = first.forSession(100L).withTraffic(200L, -100L, 1.0)
        assertEquals(1_500_200L, reconnected.sessionBytes)
        assertEquals(0L, reconnected.forSession(200L).sessionBytes)
    }
}
