package com.noki.vpn.vpn

import java.util.concurrent.atomic.AtomicLong

class VpnLifecycleGeneration {
    private val current = AtomicLong(0L)

    fun currentId(): Long = current.get()

    fun begin(): Long = current.incrementAndGet()

    fun invalidate(): Long = current.incrementAndGet()

    fun isCurrent(generationId: Long): Boolean = current.get() == generationId
}
