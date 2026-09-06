package com.noki.vpn.data

import android.content.Context
import com.noki.vpn.vpn.AndroidUnderlyingNetworkSource

object EndpointSelector {
    data class EndpointSelectionResult(
        val profile: VlessProfile,
        val endpointCode: String,
        val endpointRating: String,
        val precheckFailedEndpointCodes: List<String> = emptyList(),
        val precheckShouldPenalizeFailures: Boolean = false,
        val networkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER,
    )

    fun profileForSession(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
    ): VlessProfile {
        return selectionForSession(
            context = context,
            session = session,
            settings = settings,
            endpointHealth = emptyMap(),
            rotationIndex = { 0 },
        ).profile
    }

    fun selectionForSession(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)? = null,
        networkKind: EndpointRankingPolicy.NetworkKind? = null,
        onDiagnostic: (String) -> Unit = {},
    ): EndpointSelectionResult {
        val selection = selectCandidate(
            context = context,
            session = session,
            settings = settings,
            endpointHealth = endpointHealth,
            rotationIndex = rotationIndex,
            startupTcpPrecheck = startupTcpPrecheck,
            networkKind = networkKind,
            onDiagnostic = onDiagnostic,
        )
        val candidate = selection?.candidate
        onDiagnostic("stage=selection; selected=${candidate?.code.orEmpty()}; tcp_failed=${selection?.precheckFailedEndpointCodes?.size ?: 0}; legacy=${selection == null}")
        val profile = when {
            candidate != null -> profileFromCandidate(session, candidate)
            selection != null -> VlessProfile()
            else -> profileFromLegacySession(session)
        }
        val ratingCodes = session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .map { it.code }
            .ifEmpty { listOf(profile.endpointCode) }
        return EndpointSelectionResult(
            profile = profile,
            endpointCode = profile.endpointCode,
            endpointRating = EndpointRankingPolicy.ratingSnapshot(ratingCodes, endpointHealth),
            precheckFailedEndpointCodes = selection?.precheckFailedEndpointCodes.orEmpty(),
            precheckShouldPenalizeFailures = selection?.precheckShouldPenalizeFailures ?: false,
            networkKind = selection?.networkKind ?: networkKind ?: currentNetworkKind(context),
        )
    }

    private data class CandidateSelection(
        val candidate: BackendEndpointCandidate?,
        val precheckFailedEndpointCodes: List<String> = emptyList(),
        val precheckShouldPenalizeFailures: Boolean = false,
        val networkKind: EndpointRankingPolicy.NetworkKind,
    )

    private fun selectCandidate(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)?,
        networkKind: EndpointRankingPolicy.NetworkKind?,
        onDiagnostic: (String) -> Unit,
    ): CandidateSelection? {
        val withHost = session.endpointCandidates
            .filter { it.entryHost.isNotBlank() }
        val secure = withHost
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
        val matchingProtocol = secure
            .filter { candidate ->
                when (settings.protocol) {
                    VpnProtocol.AUTO -> true
                    VpnProtocol.TLS -> candidate.security.equals("tls", ignoreCase = true)
                    VpnProtocol.REALITY -> candidate.security.equals("reality", ignoreCase = true)
                }
            }
        val candidates = matchingProtocol.filter { !it.canaryOnly }
        onDiagnostic("stage=candidates; received=${session.endpointCandidates.size}; with_host=${withHost.size}; security_allowed=${secure.size}; protocol_allowed=${matchingProtocol.size}; eligible=${candidates.size}; protocol=${settings.protocol}; mode=${settings.endpointSelectionMode}")
        if (candidates.isEmpty()) return null
        val network = networkKind ?: currentNetworkKind(context)
        if (settings.endpointSelectionMode == EndpointSelectionMode.MANUAL) {
            val manualCandidates = EndpointGroupPolicy.manualCandidates(candidates, settings)
            onDiagnostic("stage=manual_group; candidates=${manualCandidates.size}")
            if (startupTcpPrecheck != null) {
                val preflight = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                    candidates = manualCandidates,
                    health = endpointHealth,
                    networkKind = network,
                    nowMillis = System.currentTimeMillis(),
                    rotationIndex = rotationIndex,
                    canReach = startupTcpPrecheck,
                    allowHysteria = true,
                )
                return CandidateSelection(
                    candidate = preflight.selected,
                    precheckFailedEndpointCodes = preflight.failedTcpCodes,
                    precheckShouldPenalizeFailures = preflight.shouldPenalizeFailedTcp,
                    networkKind = network,
                )
            }
            return CandidateSelection(
                EndpointGroupPolicy.selectManualCandidate(
                    candidates = manualCandidates,
                    settings = settings,
                    health = endpointHealth,
                    nowMillis = System.currentTimeMillis(),
                    rotationIndex = rotationIndex,
                )?.candidate,
                networkKind = network,
            )
        }
        if (startupTcpPrecheck != null) {
            val preflight = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                candidates = candidates,
                health = endpointHealth,
                networkKind = network,
                nowMillis = System.currentTimeMillis(),
                rotationIndex = rotationIndex,
                canReach = startupTcpPrecheck,
            )
            return CandidateSelection(
                candidate = preflight.selected,
                precheckFailedEndpointCodes = preflight.failedTcpCodes,
                precheckShouldPenalizeFailures = preflight.shouldPenalizeFailedTcp,
                networkKind = network,
            )
        }
        return CandidateSelection(EndpointRankingPolicy.select(
            candidates = candidates,
            health = endpointHealth,
            networkKind = network,
            nowMillis = System.currentTimeMillis(),
            rotationIndex = rotationIndex,
        )?.candidate, networkKind = network)
    }

    fun currentNetworkKind(context: Context): EndpointRankingPolicy.NetworkKind {
        return currentUnderlyingNetworkKind(context)
    }

    fun currentUnderlyingNetworkKind(context: Context): EndpointRankingPolicy.NetworkKind {
        return AndroidUnderlyingNetworkSource(context).currentSnapshot()?.kind
            ?: EndpointRankingPolicy.NetworkKind.OTHER
    }

    fun profileFromCandidate(
        session: BackendVpnSession,
        candidate: BackendEndpointCandidate,
    ): VlessProfile =
        VlessProfile(
            remark = "Noki ${candidate.locationName.ifBlank { session.locationName }}",
            endpointCode = candidate.code,
            proxyType = candidate.proxyType.ifBlank { session.proxyType },
            transport = candidate.normalizedTransport(),
            transportMode = candidate.transportMode.orEmpty(),
            host = candidate.connectionHost(),
            port = candidate.entryPort.toString(),
            uuid = session.vpnSecret,
            flow = candidate.flow.orEmpty(),
            security = candidate.security,
            fingerprint = candidate.fingerprint ?: "chrome",
            serverName = candidate.serverName,
            requestHost = candidate.requestHost.orEmpty(),
            path = candidate.path.orEmpty(),
            alpn = candidate.alpn.orEmpty(),
            allowInsecure = false,
            enableMux = candidate.enableMux,
            randomUserAgent = candidate.randomUserAgent,
            publicKey = candidate.publicKey.orEmpty(),
            shortId = candidate.shortId.orEmpty(),
            spiderX = "/",
            youtubeCascade = session.youtubeCascade,
        )

    private fun profileFromLegacySession(session: BackendVpnSession): VlessProfile =
        VlessProfile(
            remark = "Noki ${session.locationName}",
            endpointCode = session.endpointCode.orEmpty(),
            proxyType = session.proxyType.ifBlank { "vless" },
            transport = session.transport.ifBlank { "tcp" }.lowercase(),
            transportMode = session.transportMode.orEmpty(),
            host = session.connectIp.orEmpty().ifBlank { session.entryHost },
            port = session.entryPort.toString(),
            uuid = session.vpnSecret,
            flow = session.flow.orEmpty(),
            security = session.security,
            fingerprint = session.fingerprint ?: "chrome",
            serverName = session.serverName,
            requestHost = session.requestHost.orEmpty(),
            path = session.path.orEmpty(),
            alpn = session.alpn.orEmpty(),
            allowInsecure = false,
            enableMux = session.enableMux,
            randomUserAgent = session.randomUserAgent,
            publicKey = session.publicKey.orEmpty(),
            shortId = session.shortId.orEmpty(),
            spiderX = "/",
            youtubeCascade = session.youtubeCascade,
        )

    fun optionsFromSession(session: BackendVpnSession): List<VpnEndpointOption> {
        return session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .map { candidate ->
            val option = VpnEndpointOption(
                code = candidate.code,
                nodeId = candidate.nodeId,
                label = candidate.label,
                locationCode = candidate.locationCode,
                locationName = candidate.locationName,
                host = candidate.entryHost,
                port = candidate.entryPort,
                proxyType = candidate.proxyType,
                transport = candidate.normalizedTransport(),
                transportMode = candidate.transportMode.orEmpty(),
                security = candidate.security,
                canaryOnly = candidate.canaryOnly,
                priority = candidate.priority,
            )
            option.copy(endpointGroupKey = EndpointGroupPolicy.groupKey(option))
        }
    }
}
