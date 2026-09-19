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
import com.noki.vpn.data.NokiBackendConfig
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import kotlinx.coroutines.Dispatchers
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
    val globalNetworkFailure: Boolean,
    val success: Boolean,
    val slow: Boolean,
)

class EndpointProbeRunner(
    private val context: Context,
    private val repository: SettingsRepository,
    private val endpointHealthReporter: EndpointHealthEventReporter = EndpointHealthEventReporter(repository),
) {
    suspend fun probeAutoCandidates(
        session: BackendVpnSession,
        settings: StoredSettings,
        networkKind: EndpointRankingPolicy.NetworkKind = EndpointSelector.currentNetworkKind(context),
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<EndpointProbeOutcome> {
        val advancedSettings: AdvancedSettings = settings.advancedSettings
        if (advancedSettings.endpointSelectionMode != EndpointSelectionMode.AUTO) return emptyList()
        val codes = session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .map { it.code }
        val health = repository.loadEndpointHealth(networkKind)
        val rankedCandidates = EndpointRankingPolicy.rankCandidates(
            candidates = session.endpointCandidates,
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
            initCoreEnv()
            val globalNetworkFailure = DeviceLatency.measureTcpConnectMs(
                rawHost = NokiBackendConfig.backendProbeHost,
                port = 443,
                timeoutMs = TCP_CONNECT_TIMEOUT_MS,
            ) == null
            candidates.map { candidate ->
                probeCandidate(session, candidate, globalNetworkFailure).also { outcome ->
                    if (!outcome.globalNetworkFailure) {
                        val success = outcome.success && !outcome.slow
                        val health = repository.recordEndpointResult(
                            endpointCode = outcome.endpointCode,
                            success = success,
                            slow = outcome.slow,
                            latencyMs = outcome.xrayDelayMs,
                            networkKind = networkKind,
                        )
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
                        outcome.runtimeIssue?.let { issue ->
                            recordRuntimeIssue(
                                networkKind = networkKind,
                                outcome = outcome,
                                issue = issue,
                                endpointCodes = codes,
                            )
                        }
                    }
                }
            }.also { outcomes ->
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

    private fun probeCandidate(
        session: BackendVpnSession,
        candidate: BackendEndpointCandidate,
        globalNetworkFailure: Boolean,
    ): EndpointProbeOutcome {
        val tcpPrecheckRequired = EndpointTransportPolicy.requiresTcpPrecheck(candidate)
        val tcpMs = if (tcpPrecheckRequired) {
            DeviceLatency.measureTcpConnectMs(
                rawHost = candidate.connectionHost(),
                port = candidate.entryPort,
                timeoutMs = TCP_CONNECT_TIMEOUT_MS,
            )
        } else {
            null
        }
        if (tcpPrecheckRequired && tcpMs == null) {
            return EndpointProbeOutcome(
                endpointCode = candidate.code,
                tcpPrecheckRequired = true,
                tcpConnectMs = null,
                xrayDelayMs = null,
                runtimeIssue = if (globalNetworkFailure) null else XrayRuntimeIssue.PROXY_TCP_TIMEOUT,
                successfulHttpTargets = 0,
                totalHttpTargets = VpnProbePlanPolicy.candidateTargets().size,
                globalNetworkFailure = globalNetworkFailure,
                success = false,
                slow = false,
            )
        }

        val profile = EndpointSelector.profileFromCandidate(session, candidate)
        val config = XrayConfigFactory.buildProbe(profile)
        val targetResults = mutableListOf<ActiveProbePolicy.TargetResult>()
        for (target in VpnProbePlanPolicy.candidateTargets()) {
            val result = measureOutboundDelay(config, target.url)
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
            globalNetworkFailure = globalNetworkFailure,
            success = success,
            slow = slow,
        )
    }

    private fun measureOutboundDelay(config: String, url: String): XrayDelayResult =
        try {
            XrayDelayResult(delayMs = Libv2ray.measureOutboundDelay(config, url).takeIf { it > 0L })
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
            details = "source=candidate_probe; tcp_precheck=${outcome.tcpPrecheckRequired}; xray_ok=${outcome.successfulHttpTargets > 0}; global_network_fail=${outcome.globalNetworkFailure}",
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
            "${outcome.endpointCode}:tcp=$tcpState,xray_ok=$xrayOk,http_ok=$httpOk,global_network_fail=${outcome.globalNetworkFailure}$issue"
        }.take(2048)
    }

    private companion object {
        const val TAG = "NokiEndpointProbe"
        const val DEFAULT_MAX_CANDIDATES = 2
        const val TCP_CONNECT_TIMEOUT_MS = 1_200
        const val SLOW_TCP_CONNECT_MS = 900
        const val SLOW_XRAY_DELAY_MS = 2_500L
    }
}
