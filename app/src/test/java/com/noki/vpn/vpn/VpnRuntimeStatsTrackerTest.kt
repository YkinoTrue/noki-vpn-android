package com.noki.vpn.vpn

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnRuntimeStatsTrackerTest {
    @Test
    fun `foreground latency request bypasses statistics interval and follows runtime owner`() {
        val owner = RuntimeOwner(1L, 1L)
        val tracker = VpnRuntimeStatsTracker(
            currentDate = { LocalDate.of(2026, 9, 13) },
            elapsedRealtime = { 1_000L },
            readTraffic = { TrafficBytes(0L, 0L) },
            latencyIntervalMillis = 300_000L,
        )
        assertEquals(null, tracker.requestLatencySample())
        tracker.start(owner, "lv")
        tracker.markLatencySample(owner)
        val request = checkNotNull(tracker.requestLatencySample())
        assertEquals("lv", request.locationCode)
        assertEquals(owner, request.owner)
        tracker.start(RuntimeOwner(2L, 2L), "de")
        assertFalse(tracker.accepts(request))
        assertEquals("de", tracker.requestLatencySample()?.locationCode)
        tracker.clear(RuntimeOwner(2L, 2L))
        assertEquals(null, tracker.requestLatencySample())
    }

    @Test
    fun `tracker owns periodic flush scheduling and cancellation`() {
        val scheduler = StatsScheduler()
        var scheduledFlushes = 0
        val owner = RuntimeOwner(1L, 2L)
        val tracker = VpnRuntimeStatsTracker(
            currentDate = { LocalDate.of(2026, 7, 12) },
            elapsedRealtime = { 1_000L },
            readTraffic = { TrafficBytes(0L, 0L) },
            latencyIntervalMillis = 5_000L,
            scheduler = scheduler,
            flushIntervalMillis = 60_000L,
            onScheduledFlush = { scheduledFlushes += 1 },
        )

        tracker.start(owner, "lv")
        scheduler.runPending()
        assertEquals(1, scheduledFlushes)

        tracker.stopScheduling()
        scheduler.runPending()
        assertEquals(1, scheduledFlushes)
    }

    @Test
    fun `date rollover attributes elapsed traffic to previous day and rejects stale latency owner`() {
        var date = LocalDate.of(2026, 7, 12)
        var elapsed = 1_000L
        var traffic = TrafficBytes(rxBytes = 100L, txBytes = 200L)
        val owner = RuntimeOwner(generationId = 7L, coreId = 11L)
        val tracker = VpnRuntimeStatsTracker(
            currentDate = { date },
            elapsedRealtime = { elapsed },
            readTraffic = { traffic },
            latencyIntervalMillis = 5_000L,
        )
        tracker.start(owner = owner, locationCode = "lv")

        date = LocalDate.of(2026, 7, 13)
        elapsed = 61_000L
        traffic = TrafficBytes(rxBytes = 160L, txBytes = 240L)
        val flush = tracker.flush(owner = owner, finalFlush = false)

        assertEquals("2026-07-12", flush?.delta?.date)
        assertEquals(60L, flush?.delta?.rxBytes)
        assertEquals(40L, flush?.delta?.txBytes)
        assertEquals(60L, flush?.delta?.onlineSeconds)
        val latencyRequest = checkNotNull(flush?.latencyRequest)

        tracker.start(RuntimeOwner(generationId = 8L, coreId = 12L), locationCode = "de")

        assertFalse(tracker.accepts(latencyRequest))
    }

    @Test
    fun `final flush emits zero-second delta before clearing owner`() {
        val owner = RuntimeOwner(generationId = 1L, coreId = 1L)
        val tracker = VpnRuntimeStatsTracker(
            currentDate = { LocalDate.of(2026, 7, 12) },
            elapsedRealtime = { 1_000L },
            readTraffic = { TrafficBytes(10L, 20L) },
            latencyIntervalMillis = 5_000L,
        )
        tracker.start(owner, locationCode = "lv")

        val flush = tracker.stop(owner)

        assertEquals(0L, flush?.delta?.onlineSeconds)
        assertFalse(tracker.isOwnedBy(owner))
    }
}

private class StatsScheduler : DelayedTaskScheduler {
    private val pending = mutableMapOf<Any, () -> Unit>()

    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        pending[owner] = task
    }

    override fun cancel(owner: Any) {
        pending.remove(owner)
    }

    fun runPending() {
        val tasks = pending.values.toList()
        pending.clear()
        tasks.forEach { it() }
    }
}
