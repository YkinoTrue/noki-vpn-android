package com.noki.vpn.vpn

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

internal class XrayController(
    context: Context,
    private val runtimeSignal: ((XrayRuntimeIssue?) -> Unit)? = null,
) : XrayRuntime {
    private val controller: CoreController by lazy {
        Libv2ray.newCoreController(
            object : CoreCallbackHandler {
                override fun startup(): Long {
                    Log.i(TAG, "Xray core started")
                    return 0
                }

                override fun shutdown(): Long {
                    Log.i(TAG, "Xray core stopped")
                    runtimeSignal?.invoke(null)
                    return 0
                }

                override fun onEmitStatus(p0: Long, p1: String?): Long {
                    XrayRuntimeIssue.fromDiagnosticText(p1)?.let { issue ->
                        Log.i(TAG, "Xray status issue: ${issue.logMessage}")
                        runtimeSignal?.invoke(issue)
                    }
                    return 0
                }
            },
        )
    }

    init {
        ensureCoreAsset(context, GEOSITE_ASSET)
        Libv2ray.initCoreEnv(context.noBackupFilesDir.absolutePath, "")
    }

    fun isRuntimeAvailable(): Boolean {
        return try {
            Libv2ray.checkVersionX().isNotBlank()
        } catch (error: Throwable) {
            SafeLog.e(TAG, "Failed to load Xray runtime", error)
            false
        }
    }

    override fun start(config: String, tunFd: Int): Boolean {
        return try {
            controller.startLoopWithHotRoute(config, tunFd, "proxy")
            controller.isRunning
        } catch (error: Throwable) {
            SafeLog.e(TAG, "Failed to start Xray core", error)
            false
        }
    }

    private data class RouteAttempt(val runtimeId: Long, val base: Long, val candidateId: Long)
    private val routeAttempt = AtomicReference<RouteAttempt?>(null)

    override fun switchRoute(config: String, canCommit: () -> Boolean): XrayProbeResult {
        var attempt: RouteAttempt? = null
        try {
            if (!canCommit()) return XrayProbeResult(null)
            val runtime = controller.hotRouteRuntimeID()
            val base = controller.hotRouteVersion(runtime)
            val candidate = controller.prepareHotRoute(runtime, base, config)
            val prepared = RouteAttempt(runtime, base, candidate)
            attempt = prepared
            check(routeAttempt.compareAndSet(null, prepared))
            val deadline = SystemClock.elapsedRealtime() + VpnProbePlanPolicy.TOTAL_TIMEOUT_MILLIS
            for (target in VpnProbePlanPolicy.candidateTargets()) {
                if (!canCommit() || routeAttempt.get() !== prepared) break
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val delay = try {
                    controller.probeHotRoute(runtime, candidate, target.url,
                        remaining.coerceAtMost(VpnProbePlanPolicy.PER_TARGET_TIMEOUT_MILLIS), 256 * 1024L)
                } catch (error: Exception) {
                    SafeLog.w(TAG, "Hot-route readiness failed", error)
                    continue
                }
                if (!canCommit() || routeAttempt.get() !== prepared) break
                if (delay > 0) {
                    controller.commitHotRoute(runtime, base, candidate)
                    return XrayProbeResult(delay)
                }
            }
            return XrayProbeResult(null)
        } catch (error: Exception) {
            SafeLog.w(TAG, "Failed to switch Xray route", error)
            return XrayProbeResult(null, XrayRuntimeIssue.fromThrowable(error))
        } finally {
            attempt?.let {
                routeAttempt.compareAndSet(it, null)
                // Abort is harmless after a commit and disposes an unpublished candidate.
                runCatching { controller.abortHotRoute(it.runtimeId, it.candidateId) }
            }
        }
    }

    override fun cancelRouteSwitch() {
        routeAttempt.getAndSet(null)?.let {
            runCatching { controller.abortHotRoute(it.runtimeId, it.candidateId) }
                .onFailure { error -> SafeLog.w(TAG, "Failed to abort Xray route", error) }
        }
    }

    override fun stop() {
        try {
            if (controller.isRunning) {
                controller.stopLoop()
            }
        } catch (error: Throwable) {
            SafeLog.e(TAG, "Failed to stop Xray core", error)
        }
    }

    override fun cancelMeasureDelay() {
        try {
            controller.cancelMeasureDelay()
        } catch (error: Throwable) {
            SafeLog.w(TAG, "Failed to cancel Xray delay measurement", error)
        }
    }

    override fun measureDelay(targetUrl: String, timeoutMillis: Long): XrayProbeResult {
        return try {
            XrayProbeResult(
                delayMs = controller
                    .measureDelayWithTimeout(targetUrl, timeoutMillis)
                    .takeIf { it > 0L },
            )
        } catch (error: Throwable) {
            SafeLog.w(TAG, "Failed to measure Xray delay", error)
            XrayProbeResult(delayMs = null, issue = XrayRuntimeIssue.fromThrowable(error))
        }
    }

    companion object {
        private const val TAG = "NokiXrayController"
        private const val GEOSITE_ASSET = "geosite.dat"

        @Synchronized
        internal fun ensureCoreAsset(context: Context, assetName: String) {
            val target = AtomicFile(File(context.noBackupFilesDir, assetName))
            val output = target.startWrite()
            try {
                context.assets.open(assetName).use { source -> source.copyTo(output) }
                target.finishWrite(output)
            } catch (error: Throwable) {
                target.failWrite(output)
                throw error
            }
        }
    }
}
