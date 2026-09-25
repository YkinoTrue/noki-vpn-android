package com.noki.vpn.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor

internal data class WireGuardStatistics(
    val rxBytes: Long,
    val txBytes: Long,
    val lastHandshakeSeconds: Long,
) {
    companion object {
        private val fields = setOf("rx_bytes", "tx_bytes", "last_handshake_time_sec")

        fun parse(raw: String): WireGuardStatistics? {
            val values = mutableMapOf<String, Long>()
            for (line in raw.lineSequence().filter(String::isNotBlank)) {
                val parts = line.split('=', limit = 2)
                if (parts.size != 2 || parts[0] !in fields || parts[0] in values) return null
                val value = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
                values[parts[0]] = value
            }
            if (values.keys != fields) return null
            return WireGuardStatistics(
                rxBytes = values.getValue("rx_bytes"),
                txBytes = values.getValue("tx_bytes"),
                lastHandshakeSeconds = values.getValue("last_handshake_time_sec"),
            )
        }
    }
}

/** Native calls take ownership of the duplicate TUN FD on entry, even on failure. */
internal interface NativeWireGuardBindings {
    fun start(service: VpnService, ownedTunFd: Int, config: String): Int
    fun stop(handle: Int)
    fun stats(handle: Int): String?
}

internal interface TunFdOps {
    fun duplicate(fd: Int): Int
}

private object AndroidTunFdOps : TunFdOps {
    override fun duplicate(fd: Int): Int = ParcelFileDescriptor.fromFd(fd).use { it.detachFd() }
}

internal object NativeWireGuard : NativeWireGuardBindings {
    init {
        System.loadLibrary("noki_wireguard")
    }

    private external fun startNative(service: VpnService, tunFd: Int, config: String): Int
    private external fun stopNative(handle: Int)
    private external fun statsNative(handle: Int): String?

    override fun start(service: VpnService, ownedTunFd: Int, config: String): Int =
        startNative(service, ownedTunFd, config)

    override fun stop(handle: Int) = stopNative(handle)
    override fun stats(handle: Int): String? = statsNative(handle)
}

/**
 * A native WG engine for the existing AppVpnService. The caller retains its
 * original TunHandle; native receives only a dup and closes it on stop/failure.
 * A successful start means sockets are protected, not that a handshake passed.
 */
internal class NativeWireGuardRuntime(
    private val service: VpnService,
    private val fdOps: TunFdOps = AndroidTunFdOps,
    private val bindings: NativeWireGuardBindings = NativeWireGuard,
) {
    private var handle: Int? = null

    fun start(config: String, tunnel: TunHandle): Boolean {
        check(handle == null) { "WireGuard already running" }
        require(config.isNotBlank()) { "WireGuard config missing" }
        val ownedFd = fdOps.duplicate(tunnel.fd)
        // JNI owns the dup from method entry onward, including exceptional
        // paths where it closes the descriptor before Kotlin sees an error.
        val newHandle = bindings.start(service, ownedFd, config)
        if (newHandle < 0) return false
        handle = newHandle
        return true
    }

    fun stop() {
        val previous = handle ?: return
        handle = null
        bindings.stop(previous)
    }

    fun statistics(): WireGuardStatistics? =
        handle?.let(bindings::stats)?.let(WireGuardStatistics::parse)
}
