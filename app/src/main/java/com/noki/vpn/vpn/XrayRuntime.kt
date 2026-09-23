package com.noki.vpn.vpn

internal interface XrayRuntime {
    fun start(config: String, tunFd: Int): Boolean

    fun switchRoute(config: String, canCommit: () -> Boolean): XrayProbeResult

    fun cancelRouteSwitch()

    fun stop()

    fun cancelMeasureDelay()

    fun measureDelay(targetUrl: String, timeoutMillis: Long): XrayProbeResult
}

internal data class XrayProbeResult(
    val delayMs: Long?,
    val issue: XrayRuntimeIssue? = null,
)
