package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class ClientLatencySamplerTest {
    @Test
    fun tcpDiagnosticsDistinguishSuccessRefusalAndInvalidHost() {
        val events = mutableListOf<String>()
        val server = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = server.localPort
        try {
            assertTrue(DeviceLatency.measureTcpConnectMs("127.0.0.1", port, 800, events::add) != null)
            assertTrue(events.last().contains("result=connected"))
        } finally {
            server.close()
        }
        assertNull(DeviceLatency.measureTcpConnectMs("127.0.0.1", port, 800, events::add))
        assertTrue(events.last().contains("result=connection_refused"))
        assertNull(DeviceLatency.measureTcpConnectMs("", port, 800, events::add))
        assertTrue(events.last().contains("result=invalid_host"))
    }

    @Test
    fun dnsDiagnosticsDistinguishFailureFromTimeoutWithoutExceptionText() {
        val events = mutableListOf<String>()
        assertNull(DeviceLatency.resolveAddress("test.invalid", 100, onFailure = events::add) {
            throw java.net.UnknownHostException("secret-host-should-not-appear")
        })
        assertEquals(listOf("dns_error"), events)
        events.clear()
        assertNull(DeviceLatency.resolveAddress("test.invalid", 50, onFailure = events::add) {
            Thread.sleep(10_000)
            emptyArray()
        })
        assertEquals(listOf("dns_timeout"), events)
    }

    @Test
    fun tcpDiagnosticReasonsNeverIncludeRawSocketErrors() {
        assertEquals("tcp_timeout", DeviceLatency.tcpFailureReason(java.net.SocketTimeoutException("secret")))
        assertEquals("no_route", DeviceLatency.tcpFailureReason(java.net.NoRouteToHostException("secret")))
        assertEquals("socket_error", DeviceLatency.tcpFailureReason(java.net.SocketException("secret")))
        assertEquals("connect_error", DeviceLatency.tcpFailureReason(java.net.ConnectException("secret")))
    }

    @Test
    fun stalledProbeCannotBlockTheLocationBatch() = runBlocking {
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                Thread.sleep(10_000L)
                null
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 50L,
        )
        var result = emptyMap<String, Int>()

        val elapsed = measureTimeMillis {
            result = withTimeout(1_000L) { sampler.measure(listOf(location())) }
        }

        assertTrue(result.isEmpty())
        assertTrue("Probe batch exceeded its deadline: ${elapsed}ms", elapsed < 1_000L)
    }

    @Test
    fun dnsLookupHasItsOwnBoundedExecutorDeadline() {
        var address: java.net.InetAddress? = null

        val elapsed = measureTimeMillis {
            address = DeviceLatency.resolveAddress(
                host = "blocked.example",
                timeoutMs = 50,
                lookup = {
                    Thread.sleep(10_000L)
                    emptyArray()
                },
            )
        }

        assertNull(address)
        assertTrue("DNS lookup exceeded its deadline: ${elapsed}ms", elapsed < 1_000L)
    }

    @Test
    fun healthyLocationsWaitForBoundedProbeSlotsInsteadOfBeingDropped() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(20L)
                    10
                } finally {
                    active.decrementAndGet()
                }
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 1_000L,
        )
        val locations = (1..8).map { index -> location(index) }

        val result = sampler.measure(locations)

        assertTrue(result.size == locations.size)
        assertTrue(maximumActive.get() <= DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
    }

    @Test
    fun queuedStalledLocationsShareOneBatchDeadline() = runBlocking {
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                Thread.sleep(10_000L)
                null
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 100L,
        )
        val locations = (1..20).map { index -> location(index) }
        var result = emptyMap<String, Int>()

        val elapsed = measureTimeMillis {
            result = sampler.measure(locations)
        }

        assertTrue(result.isEmpty())
        assertTrue("Queued probes multiplied the batch deadline: ${elapsed}ms", elapsed < 300L)
    }

    @Test
    fun vpnPrecheckWaitsForGlobalDnsSlotInsteadOfBeingRejected() {
        val address = InetAddress.getByName("127.0.0.1")
        val blockingLookupsStarted = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val releaseBlockingLookups = CountDownLatch(1)
        val startupLookupStarted = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS + 1)
        try {
            val blocking = (1..DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS).map {
                callers.submit<InetAddress?> {
                    DeviceLatency.resolveAddress("latency-$it.example", 1_000) {
                        blockingLookupsStarted.countDown()
                        check(releaseBlockingLookups.await(1, TimeUnit.SECONDS))
                        arrayOf(address)
                    }
                }
            }
            assertTrue(blockingLookupsStarted.await(1, TimeUnit.SECONDS))
            val startup = callers.submit<InetAddress?> {
                DeviceLatency.resolveAddress("startup.example", 1_000) {
                    startupLookupStarted.countDown()
                    arrayOf(address)
                }
            }

            assertFalse(startupLookupStarted.await(100, TimeUnit.MILLISECONDS))
            assertFalse("Startup lookup was rejected instead of waiting", startup.isDone)
            releaseBlockingLookups.countDown()

            assertEquals(address, startup.get(1, TimeUnit.SECONDS))
            blocking.forEach { result -> assertEquals(address, result.get(1, TimeUnit.SECONDS)) }
        } finally {
            releaseBlockingLookups.countDown()
            callers.shutdownNow()
        }
    }

    @Test
    fun timedOutDnsLookupKeepsItsSlotUntilTheResolverActuallyStops() {
        val address = InetAddress.getByName("127.0.0.1")
        val blockingLookupsStarted = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val blockingLookupsFinished = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val releaseBlockingLookups = CountDownLatch(1)
        val startupLookupStarted = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS + 1)
        try {
            val blocking = (1..DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS).map {
                callers.submit<InetAddress?> {
                    DeviceLatency.resolveAddress("stalled-$it.example", 100) {
                        blockingLookupsStarted.countDown()
                        try {
                            while (releaseBlockingLookups.count > 0L) {
                                try {
                                    releaseBlockingLookups.await(25, TimeUnit.MILLISECONDS)
                                } catch (_: InterruptedException) {
                                    // Model a platform resolver that ignores interruption.
                                }
                            }
                            arrayOf(address)
                        } finally {
                            blockingLookupsFinished.countDown()
                        }
                    }
                }
            }
            assertTrue(blockingLookupsStarted.await(1, TimeUnit.SECONDS))
            blocking.forEach { result -> assertNull(result.get(1, TimeUnit.SECONDS)) }

            val startup = callers.submit<InetAddress?> {
                DeviceLatency.resolveAddress("startup.example", 500) {
                    startupLookupStarted.countDown()
                    arrayOf(address)
                }
            }

            assertFalse("Startup lookup returned before its admission deadline", startup.isDone)
            assertFalse(startupLookupStarted.await(100, TimeUnit.MILLISECONDS))
            assertFalse("Startup lookup was rejected instead of waiting", startup.isDone)
            assertNull(startup.get(1, TimeUnit.SECONDS))
            assertEquals(1L, startupLookupStarted.count)
        } finally {
            releaseBlockingLookups.countDown()
            assertTrue(blockingLookupsFinished.await(1, TimeUnit.SECONDS))
            callers.shutdownNow()
        }
    }

    private fun location(index: Int = 1) = ServerLocation(
        code = "pl-$index",
        country = "Poland",
        city = "Warsaw",
        host = "pl-$index.example",
        isOnline = true,
    )
}
