package com.noki.vpn.data

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceTrafficSnapshot(
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val sessionBytes: Long? = null,
    internal val sessionId: Long? = null,
) {
    internal fun forSession(id: Long): DeviceTrafficSnapshot =
        if (sessionId == id) copy(downloadMbps = 0.0, uploadMbps = 0.0)
        else DeviceTrafficSnapshot(0.0, 0.0, 0L, id)

    internal fun withTraffic(rxDelta: Long, txDelta: Long, elapsedSeconds: Double): DeviceTrafficSnapshot = copy(
        downloadMbps = rxDelta.coerceAtLeast(0L) * 8.0 / 1_000_000.0 / elapsedSeconds,
        uploadMbps = txDelta.coerceAtLeast(0L) * 8.0 / 1_000_000.0 / elapsedSeconds,
        sessionBytes = (sessionBytes ?: 0L) + rxDelta.coerceAtLeast(0L) + txDelta.coerceAtLeast(0L),
    )
}

object DeviceTrafficMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(DeviceTrafficSnapshot())
    val snapshot: StateFlow<DeviceTrafficSnapshot> = _snapshot.asStateFlow()

    private var monitorJob: Job? = null

    fun start(sessionId: Long) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            val uid = Process.myUid()
            var lastRxBytes = TrafficStats.getUidRxBytes(uid)
            var lastTxBytes = TrafficStats.getUidTxBytes(uid)
            var lastTimestamp = SystemClock.elapsedRealtime()

            if (lastRxBytes == TrafficStats.UNSUPPORTED.toLong() ||
                lastTxBytes == TrafficStats.UNSUPPORTED.toLong()
            ) {
                _snapshot.value = DeviceTrafficSnapshot()
                return@launch
            }

            _snapshot.value = _snapshot.value.forSession(sessionId)

            while (true) {
                delay(1_000L)

                val currentRxBytes = TrafficStats.getUidRxBytes(uid)
                val currentTxBytes = TrafficStats.getUidTxBytes(uid)
                val currentTimestamp = SystemClock.elapsedRealtime()
                if (currentRxBytes == TrafficStats.UNSUPPORTED.toLong() ||
                    currentTxBytes == TrafficStats.UNSUPPORTED.toLong()
                ) {
                    _snapshot.value = DeviceTrafficSnapshot()
                    continue
                }

                val elapsedSeconds = ((currentTimestamp - lastTimestamp).coerceAtLeast(1L)) / 1_000.0
                val rxDelta = (currentRxBytes - lastRxBytes).coerceAtLeast(0L)
                val txDelta = (currentTxBytes - lastTxBytes).coerceAtLeast(0L)
                _snapshot.value = _snapshot.value.withTraffic(rxDelta, txDelta, elapsedSeconds)

                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes
                lastTimestamp = currentTimestamp
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _snapshot.value = _snapshot.value.copy(downloadMbps = null, uploadMbps = null)
    }
}
