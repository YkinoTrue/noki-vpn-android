package com.noki.vpn.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** JNI lifecycle check with no TUN; run with the app VPN service stopped. */
@RunWith(AndroidJUnit4::class)
class HotRouteNativeInstrumentedTest {
    @Test
    fun candidateCommitKeepsCoreAndRestartRejectsOldRuntime() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "native-readiness-peer") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    it.soTimeout = 2_000
                    val reader = it.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    it.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
        }
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long { starts.incrementAndGet(); return 0 }
            override fun shutdown(): Long { stops.incrementAndGet(); return 0 }
            override fun onEmitStatus(code: Long, message: String?): Long = 0
        })
        val config = """{"stats":{},"outbounds":[{"protocol":"freedom","tag":"proxy"}]}"""
        val candidate = """{"outbounds":[{"protocol":"freedom","tag":"candidate"}]}"""
        try {
            controller.startLoopWithHotRoute(config, 0, "proxy")
            val runtime = controller.hotRouteRuntimeID()
            val prepared = controller.prepareHotRoute(runtime, 0, candidate)
            assertTrue(runCatching { controller.commitHotRoute(runtime, 0, prepared) }.isFailure)
            assertTrue(controller.probeHotRoute(
                runtime, prepared, "http://127.0.0.1:${server.localPort}/health", 2_000, 256,
            ) > 0)
            controller.commitHotRoute(runtime, 0, prepared)
            assertEquals(prepared, controller.hotRouteVersion(runtime))
            assertEquals(1, starts.get())
            assertEquals(0, stops.get())
            controller.stopLoop()
            controller.startLoopWithHotRoute(config, 0, "proxy")
            val fresh = controller.hotRouteRuntimeID()
            assertTrue(fresh > runtime)
            assertTrue(runCatching { controller.prepareHotRoute(runtime, 0, candidate) }.isFailure)
            assertTrue(runCatching { controller.commitHotRoute(runtime, 0, prepared) }.isFailure)
            assertEquals(0L, controller.hotRouteVersion(fresh))
        } finally {
            try { controller.stopLoop() } finally { server.close(); worker.join(3_000) }
        }
        assertEquals(2, starts.get())
        assertEquals(2, stops.get())
        assertTrue(!worker.isAlive)
    }
}
