package com.noki.vpn.vpn

import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.PlanCode
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.RuRelaySelection

object VpnSettingsTransactionPolicy {
    private data class SelectionKey(
        val endpointSelectionMode: EndpointSelectionMode,
        val manualEndpointCode: String,
        val manualEndpointGroupKey: String,
        val planCode: PlanCode,
        val planCodeRaw: String,
        val countryCode: String,
        val serverSelectionMode: ServerSelectionMode,
        val nodeId: String,
        val multiHop: MultiHopSettings,
        val ruRelaySelection: RuRelaySelection,
        val ruRelaySelectionRevision: Long,
    )

    private fun StoredSettings.selectionKey() = SelectionKey(
        advancedSettings.endpointSelectionMode,
        advancedSettings.manualEndpointCode,
        advancedSettings.manualEndpointGroupKey,
        userProfile.selectedPlanCode,
        userProfile.selectedPlanCodeRaw,
        userProfile.selectedCountryCode,
        userProfile.serverSelectionMode,
        userProfile.selectedNodeId,
        advancedSettings.multiHop,
        advancedSettings.ruRelaySelection,
        ruRelaySelectionRevision,
    )

    data class RuntimeCommitOutcome(
        val persisted: StoredSettings,
        val runtime: StoredSettings,
        val result: Result,
        val desiredSelectionChanged: Boolean,
    ) {
        val candidateStale: Boolean
            get() = result == Result.Accepted && desiredSelectionChanged
        val acceptedForActivation: Boolean
            get() = result == Result.Accepted && !desiredSelectionChanged
        val requiresFreshPrepare: Boolean
            get() = desiredSelectionChanged && (result == Result.Accepted || result == Result.RolledBack)
    }

    fun commitRuntimeCandidate(
        preparationBaseline: StoredSettings,
        previousRuntime: StoredSettings,
        candidate: StoredSettings,
        result: Result,
        persisted: StoredSettings,
    ): RuntimeCommitOutcome {
        val desiredSelectionChanged = candidateBecameStale(preparationBaseline, persisted)
        val stale = result == Result.Accepted && desiredSelectionChanged
        val selected = committed(previousRuntime, candidate, result)
        val merged = mergeRuntimeOwnedFields(preparationBaseline, selected, persisted)
        return RuntimeCommitOutcome(
            persisted = merged,
            runtime = when {
                stale -> candidate
                result == Result.Accepted -> merged
                else -> previousRuntime
            },
            result = result,
            desiredSelectionChanged = desiredSelectionChanged,
        )
    }

    data class RuntimeDomainOutcome(
        val persisted: StoredSettings,
        val runtime: StoredSettings,
        val requiresReapply: Boolean,
    )

    fun rollbackRuntimeDomains(
        previous: StoredSettings,
        candidate: StoredSettings,
        persisted: StoredSettings,
    ): RuntimeDomainOutcome {
        val candidateStillOwned = sameRuntimeDomains(persisted, candidate)
        val restoredPersisted = if (candidateStillOwned) {
            withRuntimeDomains(persisted, previous)
        } else {
            persisted
        }
        return RuntimeDomainOutcome(
            persisted = restoredPersisted,
            runtime = withRuntimeDomains(persisted, previous),
            requiresReapply = !candidateStillOwned,
        )
    }

    fun acceptRuntimeDomains(
        candidate: StoredSettings,
        persisted: StoredSettings,
    ): RuntimeDomainOutcome = RuntimeDomainOutcome(
        persisted = persisted,
        runtime = withRuntimeDomains(persisted, candidate),
        requiresReapply = !sameRuntimeDomains(persisted, candidate),
    )

    private fun sameRuntimeDomains(first: StoredSettings, second: StoredSettings): Boolean {
        return first.advancedSettings.alwaysOnDomains == second.advancedSettings.alwaysOnDomains &&
            first.advancedSettings.bypassDomains == second.advancedSettings.bypassDomains
    }

    private fun withRuntimeDomains(base: StoredSettings, source: StoredSettings): StoredSettings {
        return base.copy(
            advancedSettings = base.advancedSettings.copy(
                alwaysOnDomains = source.advancedSettings.alwaysOnDomains,
                bypassDomains = source.advancedSettings.bypassDomains,
            ),
        )
    }

    fun candidateBecameStale(
        previous: StoredSettings,
        persisted: StoredSettings,
    ): Boolean {
        return persisted.selectionKey() != previous.selectionKey()
    }

    enum class Result {
        Pending,
        Accepted,
        RolledBack,
        FailedClosed,
    }

    fun <T> committed(
        previous: T,
        candidate: T,
        result: Result,
    ): T {
        return when (result) {
            Result.Accepted -> candidate
            Result.Pending,
            Result.RolledBack,
            Result.FailedClosed,
            -> previous
        }
    }

    fun mergeRuntimeOwnedFields(
        previous: StoredSettings,
        selected: StoredSettings,
        persisted: StoredSettings,
    ): StoredSettings {
        if (candidateBecameStale(previous, persisted)) return persisted
        return persisted.copy(
            profile = selected.profile,
            endpointOptions = selected.endpointOptions,
            userProfile = persisted.userProfile.copy(
                selectedPlanCode = selected.userProfile.selectedPlanCode,
                selectedPlanCodeRaw = selected.userProfile.selectedPlanCodeRaw,
                selectedServerCode = selected.userProfile.selectedServerCode,
                actualCountryCode = selected.userProfile.actualCountryCode,
            ),
            advancedSettings = persisted.advancedSettings.copy(
                manualEndpointCode = selected.advancedSettings.manualEndpointCode,
                manualEndpointGroupKey = selected.advancedSettings.manualEndpointGroupKey,
            ),
            backendDeviceKey = selected.backendDeviceKey,
            backendDeviceId = selected.backendDeviceId,
            backendDeviceAccessRole = selected.backendDeviceAccessRole,
        )
    }
}
