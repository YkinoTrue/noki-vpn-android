package com.noki.vpn.data

import java.util.Locale

object EndpointGroupPolicy {
    fun groupKey(option: VpnEndpointOption): String {
        if (option.endpointGroupKey.isNotBlank()) return option.endpointGroupKey
        return groupKey(
            locationCode = option.locationCode,
            security = option.security,
            transport = normalizeTransport(option.transport),
            transportMode = normalizeTransportMode(option.transport, option.transportMode),
            priority = option.priority,
        )
    }

    fun groupKey(candidate: BackendEndpointCandidate): String =
        groupKey(
            locationCode = candidate.locationCode,
            security = candidate.security,
            transport = candidate.normalizedTransport(),
            transportMode = candidate.normalizedTransportMode(),
            priority = candidate.priority,
        )

    fun manualOptions(options: List<VpnEndpointOption>): List<VpnEndpointOption> {
        val grouped = linkedMapOf<String, MutableList<VpnEndpointOption>>()
        options.filter { !it.canaryOnly && it.host.isNotBlank() }.forEach { option ->
            grouped.getOrPut(groupKey(option)) { mutableListOf() }.add(option)
        }
        return grouped.map { (key, group) ->
            val first = group.first()
            val ports = group.map { it.port }.distinct().sorted()
            first.copy(
                label = displayLabelFor(first),
                endpointGroupKey = key,
                groupSize = group.size,
                groupPorts = ports,
            )
        }
    }

    fun resolveManualGroupKey(
        settings: AdvancedSettings,
        endpointOptions: List<VpnEndpointOption>,
        profile: VlessProfile,
    ): String {
        settings.manualEndpointGroupKey.trim().takeIf { it.isNotBlank() }?.let { return it }
        val code = settings.manualEndpointCode.ifBlank { profile.endpointCode }
        return endpointOptions.firstOrNull { it.code == code }?.let(::groupKey).orEmpty()
    }

    fun settingsAfterSelection(
        settings: AdvancedSettings,
        endpointCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): AdvancedSettings {
        if (settings.endpointSelectionMode != EndpointSelectionMode.MANUAL) return settings
        val groupKey = endpointOptions.firstOrNull { it.code == endpointCode }?.let(::groupKey)
            ?: settings.manualEndpointGroupKey
        return settings.copy(
            manualEndpointCode = endpointCode,
            manualEndpointGroupKey = groupKey,
        )
    }

    fun manualCandidates(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
    ): List<BackendEndpointCandidate> {
        val targetGroup = settings.manualEndpointGroupKey.trim().ifBlank {
            candidates.firstOrNull { it.code == settings.manualEndpointCode.trim() }
                ?.let(::groupKey)
                .orEmpty()
        }
        if (targetGroup.isNotBlank()) {
            val grouped = candidates.filter { groupKey(it) == targetGroup }
            if (grouped.isNotEmpty()) return grouped
        }
        val legacyCode = settings.manualEndpointCode.trim()
        return candidates.firstOrNull { it.code == legacyCode }?.let(::listOf) ?: candidates
    }

    fun selectManualCandidate(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
        health: Map<String, EndpointHealth>,
        nowMillis: Long,
        rotationIndex: (String) -> Int,
    ): EndpointRankingPolicy.Selection? {
        val groupCandidates = manualCandidates(candidates, settings)
        return EndpointRankingPolicy.select(
            candidates = groupCandidates,
            health = health,
            networkKind = EndpointRankingPolicy.NetworkKind.OTHER,
            nowMillis = nowMillis,
            rotationIndex = rotationIndex,
        )
    }

    private fun groupKey(
        locationCode: String,
        security: String,
        transport: String,
        transportMode: String,
        priority: Int,
    ): String = listOf(
        locationCode.lowercase(Locale.ROOT).ifBlank { "all" },
        security.lowercase(Locale.ROOT).ifBlank { "tls" },
        transport.lowercase(Locale.ROOT).ifBlank { "tcp" },
        transportMode.lowercase(Locale.ROOT),
        priority.toString(),
    ).joinToString("|")

    fun displayLabelFor(option: VpnEndpointOption): String {
        if (option.proxyType.equals("hysteria", ignoreCase = true) || normalizeTransport(option.transport) == "hysteria") {
            return "Hysteria / UDP"
        }
        val security = when (val normalized = option.security.lowercase(Locale.ROOT).ifBlank { "tls" }) {
            "tls" -> "TLS"
            "reality" -> "Reality"
            else -> normalized.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        val transport = normalizeTransport(option.transport)
        val transportLabel = when (transport) {
            "xhttp" -> "XHTTP ${normalizeTransportMode(option.transport, option.transportMode)}".trim()
            "tcp" -> "TCP"
            else -> transport.uppercase(Locale.ROOT)
        }
        return "$security / $transportLabel"
    }

    private fun normalizeTransport(raw: String): String {
        return when (raw.lowercase(Locale.ROOT).trim()) {
            "raw" -> "tcp"
            "hysteria2", "hy2" -> "hysteria"
            "" -> "tcp"
            else -> raw.lowercase(Locale.ROOT).trim()
        }
    }

    private fun normalizeTransportMode(
        transport: String,
        rawMode: String,
    ): String {
        val mode = rawMode.lowercase(Locale.ROOT).trim()
        if (mode.isNotBlank()) return mode
        return if (normalizeTransport(transport) == "xhttp") "stream-up" else ""
    }
}
