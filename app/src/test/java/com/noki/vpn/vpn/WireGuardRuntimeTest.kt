package com.noki.vpn.vpn

import android.net.VpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WireGuardRuntimeTest {
    @Test
    fun parserAcceptsOnlyNumericStatisticsWithoutSecrets() {
        val stats = WireGuardStatistics.parse(
            "rx_bytes=125\ntx_bytes=310\nlast_handshake_time_sec=1710000000\n",
        )
        assertNotNull(stats)
        assertEquals(125L, stats?.rxBytes)
        assertEquals(310L, stats?.txBytes)
        assertEquals(1710000000L, stats?.lastHandshakeSeconds)
        assertEquals(null, WireGuardStatistics.parse("private_key=deadbeef\nrx_bytes=1\ntx_bytes=2\nlast_handshake_time_sec=3\n"))
        assertEquals(null, WireGuardStatistics.parse("rx_bytes=-1\ntx_bytes=2\nlast_handshake_time_sec=3\n"))
    }

    @Test
    fun nativeOwnsOnlyDuplicateTunAndStopIsIdempotent() {
        val fd = FakeFdOps()
        val native = FakeNative()
        val runtime = NativeWireGuardRuntime(service(), fd, native)
        val tunnel = object : TunHandle {
            override val fd = 17
            override fun close() = Unit
        }

        assertTrue(runtime.start("private_key=synthetic", tunnel))
        assertEquals(71, native.receivedFd)
        assertEquals(17, fd.original)
        runtime.stop()
        runtime.stop()
        assertEquals(listOf(9), native.stopped)
    }

    @Test
    fun nativeExceptionKeepsRuntimeStopped() {
        val fd = FakeFdOps()
        val native = FakeNative().apply { throwOnStart = true }
        val runtime = NativeWireGuardRuntime(service(), fd, native)
        val tunnel = object : TunHandle {
            override val fd = 17
            override fun close() = Unit
        }
        var thrown = false
        try {
            runtime.start("private_key=synthetic", tunnel)
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals(71, native.receivedFd)
        runtime.stop()
        assertTrue(native.stopped.isEmpty())
    }

    @Test
    fun nativeProtectionFailureLeavesRuntimeStopped() {
        val fd = FakeFdOps()
        val native = FakeNative().apply { handleToReturn = -1 }
        val runtime = NativeWireGuardRuntime(service(), fd, native)
        val tunnel = object : TunHandle {
            override val fd = 17
            override fun close() = Unit
        }
        assertFalse(runtime.start("private_key=synthetic", tunnel))
        assertEquals(null, runtime.statistics())
        runtime.stop()
        assertTrue(native.stopped.isEmpty())
    }

    private fun service(): VpnService = Robolectric.buildService(VpnService::class.java).get()

    private class FakeFdOps : TunFdOps {
        var original = -1
        override fun duplicate(fd: Int): Int { original = fd; return 71 }
    }

    private class FakeNative : NativeWireGuardBindings {
        var receivedFd = -1
        var handleToReturn = 9
        var throwOnStart = false
        val stopped = mutableListOf<Int>()
        override fun start(service: VpnService, ownedTunFd: Int, config: String): Int {
            receivedFd = ownedTunFd
            if (throwOnStart) throw IllegalStateException("synthetic native failure")
            return handleToReturn
        }
        override fun stop(handle: Int) { stopped += handle }
        override fun stats(handle: Int): String? = null
    }
}
