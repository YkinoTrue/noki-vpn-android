package com.noki.vpn.vpn

import java.time.LocalDate

internal data class RuntimeOwner(
    val generationId: Long,
    val coreId: Long,
)

internal data class TrafficBytes(
    val rxBytes: Long,
    val txBytes: Long,
)

internal data class RuntimeStatsDelta(
    val date: String,
    val rxBytes: Long,
    val txBytes: Long,
    val onlineSeconds: Long,
)

internal data class LatencySampleRequest(
    val owner: RuntimeOwner,
    val date: String,
    val locationCode: String,
)

internal data class RuntimeStatsFlush(
    val delta: RuntimeStatsDelta,
    val latencyRequest: LatencySampleRequest?,
)

internal class VpnRuntimeStatsTracker(
    private val currentDate: () -> LocalDate,
    private val elapsedRealtime: () -> Long,
    private val readTraffic: () -> TrafficBytes?,
    private val latencyIntervalMillis: Long,
    private val scheduler: DelayedTaskScheduler? = null,
    private val flushIntervalMillis: Long = 60_000L,
    private val onScheduledFlush: () -> Unit = {},
) {
    private val tickerOwner = Any()
    private var owner: RuntimeOwner? = null
    private var activeDate: LocalDate? = null
    private var lastFlushElapsedMillis: Long = 0L
    private var lastLatencyElapsedMillis: Long = 0L
    private var lastTraffic: TrafficBytes? = null
    private var locationCode: String? = null
    private var schedulingActive = false

    @Synchronized
    fun start(owner: RuntimeOwner, locationCode: String): String {
        this.owner = owner
        activeDate = currentDate()
        lastFlushElapsedMillis = elapsedRealtime()
        lastLatencyElapsedMillis = 0L
        lastTraffic = readTraffic()
        this.locationCode = locationCode
        schedulingActive = true
        scheduleNextFlush()
        return checkNotNull(activeDate).toString()
    }

    @Synchronized
    fun markLatencySample(owner: RuntimeOwner) {
        if (this.owner == owner) {
            lastLatencyElapsedMillis = elapsedRealtime()
        }
    }

    @Synchronized
    fun flush(owner: RuntimeOwner, finalFlush: Boolean): RuntimeStatsFlush? {
        if (this.owner != owner) return null
        val date = activeDate ?: return null
        val nowElapsedMillis = elapsedRealtime()
        val elapsedSeconds = ((nowElapsedMillis - lastFlushElapsedMillis).coerceAtLeast(0L)) / 1_000L
        if (elapsedSeconds <= 0L && !finalFlush) return null
        val traffic = readTraffic()
        val previousTraffic = lastTraffic
        val today = currentDate()
        val targetDate = if (today == date) today else date
        val delta = RuntimeStatsDelta(
            date = targetDate.toString(),
            rxBytes = traffic.deltaFrom(previousTraffic) { it.rxBytes },
            txBytes = traffic.deltaFrom(previousTraffic) { it.txBytes },
            onlineSeconds = elapsedSeconds,
        )
        val latencyRequest = locationCode
            ?.takeIf(String::isNotBlank)
            ?.takeIf {
                lastLatencyElapsedMillis == 0L ||
                    nowElapsedMillis - lastLatencyElapsedMillis >= latencyIntervalMillis
            }
            ?.let { code ->
                lastLatencyElapsedMillis = nowElapsedMillis
                LatencySampleRequest(owner, targetDate.toString(), code)
            }
        lastFlushElapsedMillis = nowElapsedMillis
        activeDate = today
        lastTraffic = traffic
        return RuntimeStatsFlush(delta, latencyRequest)
    }

    @Synchronized
    fun stop(owner: RuntimeOwner): RuntimeStatsFlush? {
        val flush = flush(owner, finalFlush = true)
        if (this.owner == owner) clear()
        return flush
    }

    @Synchronized
    fun clear(owner: RuntimeOwner) {
        if (this.owner == owner) clear()
    }

    @Synchronized
    fun stopScheduling() {
        schedulingActive = false
        scheduler?.cancel(tickerOwner)
    }

    @Synchronized
    fun isOwnedBy(owner: RuntimeOwner): Boolean = this.owner == owner

    @Synchronized
    fun accepts(request: LatencySampleRequest): Boolean = owner == request.owner

    @Synchronized
    fun requestLatencySample(): LatencySampleRequest? {
        val currentOwner = owner ?: return null
        val code = locationCode?.takeIf(String::isNotBlank) ?: return null
        return LatencySampleRequest(currentOwner, currentDate().toString(), code)
    }

    private fun clear() {
        scheduler?.cancel(tickerOwner)
        schedulingActive = false
        owner = null
        activeDate = null
        lastFlushElapsedMillis = 0L
        lastLatencyElapsedMillis = 0L
        lastTraffic = null
        locationCode = null
    }

    private fun scheduleNextFlush() {
        val taskScheduler = scheduler ?: return
        taskScheduler.schedule(tickerOwner, flushIntervalMillis) {
            val hasOwner = synchronized(this) { schedulingActive && owner != null }
            if (!hasOwner) return@schedule
            onScheduledFlush()
            synchronized(this) {
                if (schedulingActive && owner != null) scheduleNextFlush()
            }
        }
    }

    private fun TrafficBytes?.deltaFrom(
        previous: TrafficBytes?,
        select: (TrafficBytes) -> Long,
    ): Long = if (this != null && previous != null) {
        (select(this) - select(previous)).coerceAtLeast(0L)
    } else {
        0L
    }
}
