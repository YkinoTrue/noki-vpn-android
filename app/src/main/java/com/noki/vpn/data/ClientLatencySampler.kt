package com.noki.vpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import android.content.Context
import com.noki.vpn.vpn.AndroidUnderlyingNetworkSource

internal fun clientLatencyTargetKey(location: ServerLocation): String? =
    clientLatencyTargetKey(location.code, location.host)

internal fun clientLatencyTargetKey(server: VpnServer): String? =
    clientLatencyTargetKey(server.id, "${server.host}:${server.probePort ?: 0}")

internal fun ServerLocation.withClientLatencies(samples: Map<String, Int>): ServerLocation {
    val measured = servers.map { server -> server.copy(latencyMs = clientLatencyTargetKey(server)?.let(samples::get)) }
    return copy(
        servers = measured,
        latencyMs = if (measured.isNotEmpty()) measured.filter { it.isOnline }.mapNotNull { it.latencyMs }.minOrNull()
            else clientLatencyTargetKey(this)?.let(samples::get),
    )
}

internal fun clientLatencyTargetKey(codeValue: String, hostValue: String): String? {
    val code = codeValue.trim().uppercase(Locale.ROOT)
    val host = hostValue.trim().lowercase(Locale.ROOT)
    if (code.isBlank() || host.isBlank()) return null
    return "$code\u0000$host"
}

class ClientLatencySampler(
    private val tcpConnect: (host: String, port: Int, timeoutMs: Int) -> Int? = DeviceLatency::measureTcpConnectMs,
    private val icmpPing: (host: String, count: Int, timeoutSeconds: Int) -> Int? = DeviceLatency::measureIcmpPingMs,
    private val locationTimeoutMillis: Long = 8_000L,
    private val context: Context? = null,
) {
    fun networkSignature(): String = context?.let { AndroidUnderlyingNetworkSource(it).currentSnapshot()?.signature } ?: "none"

    suspend fun measure(locations: List<ServerLocation>): Map<String, Int> = coroutineScope {
        val network = context?.let { AndroidUnderlyingNetworkSource(it).currentSnapshot()?.network }
        if (context != null && network == null) return@coroutineScope emptyMap()
        val probeSlots = Semaphore(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        locations
            .asSequence()
            .filter { location -> location.isOnline }
            .flatMap { location ->
                if (location.servers.isEmpty()) sequenceOf(Triple(clientLatencyTargetKey(location), location.host, 443))
                else location.servers.asSequence().filter { it.isOnline }.map { Triple(clientLatencyTargetKey(it), it.host, it.probePort) }
            }
            .distinctBy { it.first }
            .mapNotNull { (key, rawHost, port) ->
                val targetKey = key ?: return@mapNotNull null
                val host = rawHost.trim()
                async {
                    val latency = withTimeoutOrNull(locationTimeoutMillis.coerceAtLeast(1L)) {
                        probeSlots.withPermit {
                            runInterruptible(Dispatchers.IO) {
                                if (port == null) null else {
                                    val samples = List(LATENCY_TCP_ATTEMPTS) {
                                        if (network == null) tcpConnect(host, port, LATENCY_TCP_TIMEOUT_MS)
                                        else DeviceLatency.measureTcpConnectMs(host, port, LATENCY_TCP_TIMEOUT_MS,
                                            onDiagnostic = {}, socketFactory = { network.socketFactory.createSocket() },
                                            lookup = network::getAllByName)
                                    }.filterNotNull().sorted()
                                    samples.getOrNull(samples.size / 2)
                                        ?: if (context == null) icmpPing(host, LATENCY_ICMP_COUNT, LATENCY_ICMP_TIMEOUT_SECONDS) else null
                                }
                            }
                        }
                    }
                    latency?.let { targetKey to it }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    private companion object {
        const val LATENCY_TCP_ATTEMPTS = 3
        const val LATENCY_TCP_TIMEOUT_MS = 650
        const val LATENCY_ICMP_COUNT = 1
        const val LATENCY_ICMP_TIMEOUT_SECONDS = 1
    }
}
