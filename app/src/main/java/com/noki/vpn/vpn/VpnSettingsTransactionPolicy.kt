package com.noki.vpn.vpn

import com.noki.vpn.data.StoredSettings

object VpnSettingsTransactionPolicy {
    data class RuntimeCommitOutcome(
        val persisted: StoredSettings,
        val runtime: StoredSettings,
        val candidateStale: Boolean,
        val desiredSelectionChanged: Boolean = false,
        val requiresFreshPrepare: Boolean = candidateStale,
    )

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
            candidateStale = stale,
            desiredSelectionChanged = desiredSelectionChanged,
            requiresFreshPrepare = desiredSelectionChanged &&
                (result == Result.Accepted || result == Result.RolledBack),
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
        return persisted.advancedSettings.endpointSelectionMode != previous.advancedSettings.endpointSelectionMode ||
            persisted.advancedSettings.manualEndpointCode != previous.advancedSettings.manualEndpointCode ||
            persisted.advancedSettings.manualEndpointGroupKey != previous.advancedSettings.manualEndpointGroupKey ||
            persisted.userProfile.selectedPlanCode != previous.userProfile.selectedPlanCode ||
            persisted.userProfile.selectedPlanCodeRaw != previous.userProfile.selectedPlanCodeRaw ||
            persisted.userProfile.selectedCountryCode != previous.userProfile.selectedCountryCode ||
            persisted.userProfile.serverSelectionMode != previous.userProfile.serverSelectionMode ||
            persisted.userProfile.selectedNodeId != previous.userProfile.selectedNodeId
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
