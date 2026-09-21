package com.noki.vpn.vpn

import android.content.Context
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.BackendEndpointCandidate
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.connectionHost
import com.noki.vpn.data.DeviceLatency
import com.noki.vpn.data.EndpointHealthEvent
import com.noki.vpn.data.EndpointHealthEventReporter
import com.noki.vpn.data.EndpointHealthEventType
import com.noki.vpn.data.EndpointHealthEvents
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.EndpointSecurityPolicy
import com.noki.vpn.data.EndpointTransportPolicy
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.hasSameAuthSessionAs
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

data class EndpointProbeOutcome(
    val endpointCode: String,
    val tcpPrecheckRequired: Boolean,
    val tcpConnectMs: Int?,
    val xrayDelayMs: Long?,
    val runtimeIssue: XrayRuntimeIssue?,
    val successfulHttpTargets: Int,
    val totalHttpTargets: Int,
    val success: Boolean,
    val slow: Boolean,
)

class EndpointProbeRunner(
    private val context: Context,
    private val repository: SettingsRepository,
    private val endpointHealthReporter: EndpointHealthEventReporter = EndpointHealthEventReporter(repository),
    private val measureNativeDelay: suspend (String, String) -> Long = { config, url ->
        val probe = Libv2ray.newOutboundProbe()
        awaitNativeProbe(
            measure = { probe.measure(config, url, 12_000L, 256L * 1024L) },
            cancel = probe::cancel,
        )
    },
    private val measureTcpDelay: (String, Int, Int) -> Int? = { host, port, timeout ->
        DeviceLatency.measureTcpConnectMs(host, port, timeout)
    },
) {
    suspend fun probeAutoCandidates(
        session: BackendVpnSession,
        settings: StoredSettings,
        isCurrent: () -> Boolean,
        networkKind: EndpointRankingPolicy.NetworkKind = EndpointSelector.currentNetworkKind(context),
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        deadlineMillis: Long = 15_000L,
        staggerMillis: Long = 250L,
    ): List<EndpointProbeOutcome> {
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || !repository.load().hasSameAuthSessionAs(settings)) {
                throw CancellationException("Candidate probe context changed")
            }
        }
        ensureCurrent()
        val advancedSettings: AdvancedSettings = settings.advancedSettings
        if (advancedSettings.endpointSelectionMode != EndpointSelectionMode.AUTO) return emptyList()
        val matchingProtocol = session.endpointCandidates
            .filter { EndpointSelector.matchesProtocol(it, advancedSettings.protocol) }
        val codes = matchingProtocol
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .map { it.code }
        val health = repository.loadEndpointHealth(networkKind)
        val rankedCandidates = EndpointRankingPolicy.rankCandidates(
            candidates = matchingProtocol,
            health = health,
            networkKind = networkKind,
            nowMillis = System.currentTimeMillis(),
        )
        val candidates = EndpointRankingPolicy.selectWarmupCandidates(
            rankedCandidates = rankedCandidates,
            health = health,
            maxCandidates = maxCandidates.coerceIn(1, DEFAULT_MAX_CANDIDATES),
        )
        if (candidates.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            ensureCurrent()
            initCoreEnv()
            ensureCurrent()
            collectCandidateProbes(candidates, deadlineMillis, staggerMillis) { candidate ->
                probeCandidate(session, candidate, ::ensureCurrent)
            }.onEach { outcome ->
                ensureCurrent()
                val success = outcome.success && !outcome.slow
                val health = repository.recordEndpointResult(
                    endpointCode = outcome.endpointCode,
                    success = success,
                    slow = outcome.slow,
                    latencyMs = outcome.xrayDelayMs,
                    networkKind = networkKind,
                )
                ensureCurrent()
                endpointHealthReporter.recordEvent(
                    settings = settings,
                    event = EndpointHealthEvent(
                        endpointCode = outcome.endpointCode,
                        networkKind = networkKind,
                        eventType = EndpointHealthEventType.CANDIDATE_PROBE,
                        success = success,
                        slow = outcome.slow,
                        scoreBucket = EndpointHealthEvents.scoreBucket(health),
                    ),
                )
                ensureCurrent()
                outcome.runtimeIssue?.let { issue ->
                    recordRuntimeIssue(
                        networkKind = networkKind,
                        outcome = outcome,
                        issue = issue,
                        endpointCodes = codes,
                    )
                }
            }.also { outcomes ->
                ensureCurrent()
                repository.recordAppLog(
                    category = "vpn",
                    message = "endpoint_probe_complete",
                    details = probeSummary(outcomes),
                    endpointRating = repository.endpointRatingSnapshot(codes, networkKind),
                )
            }
        }
    }

    private fun initCoreEnv() {
        runCatching {
            Libv2ray.initCoreEnv(context.noBackupFilesDir.absolutePath, "")
        }.onFailure { error ->
            SafeLog.w(TAG, "Failed to initialize Xray probe env", error)
        }
    }

    private suspend fun probeCandidate(
        session: BackendVpnSession,
        candidate: BackendEndpointCandidate,
        ensureCurrent: suspend () -> Unit,
    ): EndpointProbeOutcome {
        ensureCurrent()
        val tcpPrecheckRequired = EndpointTransportPolicy.requiresTcpPrecheck(candidate)
        val tcpMs = if (tcpPrecheckRequired) {
            measureTcpDelay(candidate.connectionHost(), candidate.entryPort, TCP_CONNECT_TIMEOUT_MS)
        } else {
            null
        }
        ensureCurrent()
        if (tcpPrecheckRequired && tcpMs == null) {
            return EndpointProbeOutcome(
                endpointCode = candidate.code,
                tcpPrecheckRequired = true,
                tcpConnectMs = null,
                xrayDelayMs = null,
                runtimeIssue = XrayRuntimeIssue.PROXY_TCP_TIMEOUT,
                successfulHttpTargets = 0,
                totalHttpTargets = VpnProbePlanPolicy.candidateTargets().size,
                success = false,
                slow = false,
            )
        }

        val profile = EndpointSelector.profileFromCandidate(session, candidate)
        val config = XrayConfigFactory.buildProbe(profile)
        val targetResults = mutableListOf<ActiveProbePolicy.TargetResult>()
        for (target in VpnProbePlanPolicy.candidateTargets()) {
            ensureCurrent()
            val result = measureOutboundDelay(config, target.url)
            ensureCurrent()
            targetResults += ActiveProbePolicy.TargetResult(
                delayMs = result.delayMs,
                issue = result.issue,
            )
            if (result.delayMs != null) break
        }
        val delayMs = ActiveProbePolicy.representativeDelay(targetResults)
        val decision = ActiveProbePolicy.evaluate(
            targetResults = targetResults,
            previousFailures = 0,
            slowDelayMs = SLOW_XRAY_DELAY_MS,
        )
        val success = decision.treatAsHealthy
        val slowTcp = tcpMs != null && tcpMs >= SLOW_TCP_CONNECT_MS
        val slow = slowTcp || (!success && delayMs != null)
        return EndpointProbeOutcome(
            endpointCode = candidate.code,
            tcpPrecheckRequired = tcpPrecheckRequired,
            tcpConnectMs = tcpMs,
            xrayDelayMs = delayMs,
            runtimeIssue = probeRuntimeIssue(targetResults, decision),
            successfulHttpTargets = targetResults.count { it.delayMs != null },
            totalHttpTargets = targetResults.size,
            success = success,
            slow = slow,
        )
    }

    private suspend fun measureOutboundDelay(config: String, url: String): XrayDelayResult =
        try {
            XrayDelayResult(delayMs = measureNativeDelay(config, url).takeIf { it > 0L })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            XrayDelayResult(delayMs = null, issue = XrayRuntimeIssue.fromThrowable(error))
        }

    private fun probeRuntimeIssue(
        targetResults: List<ActiveProbePolicy.TargetResult>,
        decision: ActiveProbePolicy.Decision,
    ): XrayRuntimeIssue? {
        if (decision.treatAsHealthy) return null
        val issues = targetResults.mapNotNull { it.issue }
        return issues.firstOrNull { it == XrayRuntimeIssue.DNS_TIMEOUT } ?: issues.firstOrNull()
    }

    private fun recordRuntimeIssue(
        networkKind: EndpointRankingPolicy.NetworkKind,
        outcome: EndpointProbeOutcome,
        issue: XrayRuntimeIssue,
        endpointCodes: List<String>,
    ) {
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = issue.logMessage,
            details = "source=candidate_probe; tcp_precheck=${outcome.tcpPrecheckRequired}; xray_ok=${outcome.successfulHttpTargets > 0}",
            errorType = issue.logMessage,
            connectionSuccess = false,
            endpointRating = repository.endpointRatingSnapshot(endpointCodes, networkKind),
        )
    }

    private fun probeSummary(outcomes: List<EndpointProbeOutcome>): String {
        if (outcomes.isEmpty()) return "no_candidates"
        return outcomes.joinToString(";") { outcome ->
            val tcpState = when {
                !outcome.tcpPrecheckRequired -> "skipped"
                outcome.tcpConnectMs != null -> "ok"
                else -> "fail"
            }
            val xrayOk = outcome.successfulHttpTargets > 0
            val httpOk = outcome.success
            val issue = outcome.runtimeIssue?.logMessage?.let { ",issue=$it" }.orEmpty()
            "${outcome.endpointCode}:tcp=$tcpState,xray_ok=$xrayOk,http_ok=$httpOk$issue"
        }.take(2048)
    }

    companion object {
        // The slot belongs to the blocking call, even after its awaiting coroutine is cancelled.
        private val nativeProbeDispatcher = Dispatchers.IO.limitedParallelism(2)

        internal suspend fun awaitNativeProbe(measure: () -> Long, cancel: () -> Unit): Long =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                nativeProbeDispatcher.dispatch(EmptyCoroutineContext) {
                    if (continuation.isActive) {
                        continuation.resumeWith(runCatching(measure))
                    }
                }
            }

        internal suspend fun collectCandidateProbes(
            candidates: List<BackendEndpointCandidate>,
            deadlineMillis: Long,
            staggerMillis: Long,
            probe: suspend (BackendEndpointCandidate) -> EndpointProbeOutcome,
        ): List<EndpointProbeOutcome> {
            require(deadlineMillis in 1L..60_000L)
            require(staggerMillis in 0L..deadlineMillis)
            val boundedCandidates = candidates.take(DEFAULT_MAX_CANDIDATES)
            val completed = AtomicReferenceArray<EndpointProbeOutcome>(boundedCandidates.size)
            withTimeoutOrNull(deadlineMillis) {
                coroutineScope {
                    boundedCandidates.mapIndexed { index, candidate ->
                        async {
                            delay(index * staggerMillis)
                            val outcome = probe(candidate)
                            currentCoroutineContext().ensureActive()
                            completed.set(index, outcome)
                        }
                    }.awaitAll()
                }
            }
            currentCoroutineContext().ensureActive()
            return (0 until completed.length()).mapNotNull(completed::get)
        }

        private const val TAG = "NokiEndpointProbe"
        private const val DEFAULT_MAX_CANDIDATES = 2
        private const val TCP_CONNECT_TIMEOUT_MS = 1_200
        private const val SLOW_TCP_CONNECT_MS = 900
        private const val SLOW_XRAY_DELAY_MS = 2_500L
    }
}
