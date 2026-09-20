package com.noki.vpn.vpn

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

internal class VpnStatsCoordinator(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    scheduler: DelayedTaskScheduler,
    private val measureConnectedLatencyMs: suspend () -> Int?,
    private val onLatencySample: (locationCode: String, latencyMs: Int) -> Unit,
) {
    private var owner: RuntimeOwner? = null
    private var latencyJob: Job? = null
    private val tracker = VpnRuntimeStatsTracker(
        currentDate = LocalDate::now,
        elapsedRealtime = SystemClock::elapsedRealtime,
        readTraffic = ::readRuntimeTrafficBytes,
        latencyIntervalMillis = LATENCY_SAMPLE_INTERVAL_MS,
        scheduler = scheduler,
        flushIntervalMillis = STATS_FLUSH_INTERVAL_MS,
        onScheduledFlush = { flush(finalFlush = false) },
    )

    fun start(settings: StoredSettings, runtimeOwner: RuntimeOwner) {
        latencyJob?.cancel()
        latencyJob = null
        owner = runtimeOwner
        val date = tracker.start(runtimeOwner, settings.userProfile.selectedServerCode)
        repository.recordDailyStatsSessionStart(date)
    }

    fun acceptsStop(runtimeOwner: RuntimeOwner?): Boolean = runtimeOwner == null || owner == runtimeOwner

    fun stop(runtimeOwner: RuntimeOwner?) {
        if (!acceptsStop(runtimeOwner)) return
        flush(finalFlush = true)
        reset()
    }

    fun reset() {
        latencyJob?.cancel()
        latencyJob = null
        tracker.stopScheduling()
        owner?.let(tracker::clear)
        owner = null
    }

    fun recordInitialLatency(
        settings: StoredSettings,
        runtimeOwner: RuntimeOwner,
        initialLatencyMs: Long?,
    ) {
        val date = LocalDate.now().toString()
        val locationCode = settings.userProfile.selectedServerCode
        if (initialLatencyMs != null && locationCode.isNotBlank()) {
            val latencyMs = initialLatencyMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            tracker.markLatencySample(runtimeOwner)
            repository.addDailyStatsPingSample(date, latencyMs)
            onLatencySample(locationCode, latencyMs)
        } else {
            tracker.markLatencySample(runtimeOwner)
            recordImmediateLatency(LatencySampleRequest(runtimeOwner, date, locationCode))
        }
    }

    fun flush(finalFlush: Boolean) {
        val runtimeOwner = owner ?: return
        val result = tracker.flush(runtimeOwner, finalFlush) ?: return
        repository.addDailyStatsDelta(
            date = result.delta.date,
            rxBytes = result.delta.rxBytes,
            txBytes = result.delta.txBytes,
            onlineSeconds = result.delta.onlineSeconds,
        )
        result.latencyRequest?.let { recordImmediateLatency(it) }
    }

    fun refreshLatency() {
        val request = tracker.requestLatencySample() ?: return
        recordImmediateLatency(request)
    }

    private fun recordImmediateLatency(request: LatencySampleRequest) {
        if (request.locationCode.isBlank() || latencyJob?.isActive == true) return
        latencyJob = scope.launch {
            if (!tracker.accepts(request)) return@launch
            measureConnectedLatencyMs()?.let { latencyMs ->
                if (!tracker.accepts(request)) return@let
                tracker.markLatencySample(request.owner)
                repository.addDailyStatsPingSample(request.date, latencyMs)
                onLatencySample(request.locationCode, latencyMs)
            }
        }
    }

    private fun readRuntimeTrafficBytes(): TrafficBytes? {
        val uid = Process.myUid()
        val rxBytes = TrafficStats.getUidRxBytes(uid)
        val txBytes = TrafficStats.getUidTxBytes(uid)
        if (rxBytes == TrafficStats.UNSUPPORTED.toLong() || txBytes == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }
        return TrafficBytes(rxBytes, txBytes)
    }

    companion object {
        private const val STATS_FLUSH_INTERVAL_MS = 60_000L
        private const val LATENCY_SAMPLE_INTERVAL_MS = 5 * 60_000L
    }
}
