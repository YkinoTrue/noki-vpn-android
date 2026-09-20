package com.noki.vpn.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.SettingsAtomicUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSettingsTransactionPolicyTest {
    @Test
    fun `commit decides and writes against latest stored settings atomically`() {
        val baseline = DefaultStoredSettingsFactory.create()
        val previousRuntime = baseline.copy(profile = baseline.profile.copy(host = "working.example"))
        val candidate = baseline.copy(profile = baseline.profile.copy(host = "candidate.example"))
        val latest = baseline.copy(selectedPackages = setOf("latest.user.edit"))
        val store = RecordingStore(latest)

        val outcome = store.updateAndReturn { persisted ->
            VpnSettingsTransactionPolicy.commitRuntimeCandidate(
            previousRuntime = previousRuntime,
            preparationBaseline = baseline,
            candidate = candidate,
            result = VpnSettingsTransactionPolicy.Result.Accepted,
            persisted = persisted,
            ).let { it.persisted to it }
        }

        assertEquals("candidate.example", outcome.runtime.profile.host)
        assertEquals(setOf("latest.user.edit"), outcome.persisted.selectedPackages)
        assertEquals(outcome.persisted, store.current)
        assertEquals(1, store.updateCount)
    }

    @Test
    fun onlyAcceptedOrRolledBackChangedSelectionsRequestPreparation() {
        val baseline = DefaultStoredSettingsFactory.create()
        for (result in VpnSettingsTransactionPolicy.Result.entries) {
            for (changed in listOf(false, true)) {
                val persisted = if (changed) baseline.copy(
                    userProfile = baseline.userProfile.copy(selectedCountryCode = "DE"),
                ) else baseline
                val outcome = VpnSettingsTransactionPolicy.commitRuntimeCandidate(
                    baseline, baseline, baseline, result, persisted,
                )
                assertEquals(changed && result == VpnSettingsTransactionPolicy.Result.Accepted, outcome.candidateStale)
                assertEquals(changed && result in listOf(
                    VpnSettingsTransactionPolicy.Result.Accepted,
                    VpnSettingsTransactionPolicy.Result.RolledBack,
                ), outcome.requiresFreshPrepare)
            }
        }
    }

    @Test
    fun rolledBackCandidatePreservesNewDesiredSelectionAndRestoredRuntimeAndRequestsReprepare() {
        val baseline = DefaultStoredSettingsFactory.create()
        val previousRuntime = baseline.copy(profile = baseline.profile.copy(host = "working.example"))
        val failedCandidate = baseline.copy(profile = baseline.profile.copy(host = "failed.example"))
        val persistedDesired = baseline.copy(
            userProfile = baseline.userProfile.copy(selectedCountryCode = "DE"),
        )

        val outcome = VpnSettingsTransactionPolicy.commitRuntimeCandidate(
            preparationBaseline = baseline,
            previousRuntime = previousRuntime,
            candidate = failedCandidate,
            result = VpnSettingsTransactionPolicy.Result.RolledBack,
            persisted = persistedDesired,
        )

        assertTrue(outcome.desiredSelectionChanged)
        assertTrue(outcome.requiresFreshPrepare)
        assertEquals("DE", outcome.persisted.userProfile.selectedCountryCode)
        assertEquals(baseline.profile, outcome.persisted.profile)
        assertEquals(previousRuntime, outcome.runtime)
    }

    @Test
    fun firstFreshReprepareCommitsAgainstItsOwnPreparationBaseline() {
        val initial = DefaultStoredSettingsFactory.create()
        val firstCandidate = initial.copy(profile = initial.profile.copy(host = "first.example"))
        val desiredAfterFirstPrepare = initial.copy(
            userProfile = initial.userProfile.copy(selectedCountryCode = "DE"),
        )
        val firstCommit = VpnSettingsTransactionPolicy.commitRuntimeCandidate(
            preparationBaseline = initial,
            previousRuntime = initial,
            candidate = firstCandidate,
            result = VpnSettingsTransactionPolicy.Result.Accepted,
            persisted = desiredAfterFirstPrepare,
        )
        val secondCandidate = desiredAfterFirstPrepare.copy(
            profile = desiredAfterFirstPrepare.profile.copy(host = "germany.example"),
        )

        val secondCommit = VpnSettingsTransactionPolicy.commitRuntimeCandidate(
            preparationBaseline = desiredAfterFirstPrepare,
            previousRuntime = firstCommit.runtime,
            candidate = secondCandidate,
            result = VpnSettingsTransactionPolicy.Result.Accepted,
            persisted = desiredAfterFirstPrepare,
        )

        assertTrue(firstCommit.candidateStale)
        assertEquals(initial.profile, firstCommit.persisted.profile)
        assertEquals(false, secondCommit.candidateStale)
        assertEquals("germany.example", secondCommit.persisted.profile.host)
    }

    @Test
    fun settingsTransformRunsDecisionAndWriteInsideOneCriticalSection() {
        val lock = Any()
        var stored = 1
        var transformHeldLock = false
        var saveHeldLock = false

        val updated = SettingsAtomicUpdate.transform(
            lock = lock,
            load = { stored },
            transform = { current ->
                transformHeldLock = Thread.holdsLock(lock)
                (current + 1) to "committed"
            },
            save = { value ->
                saveHeldLock = Thread.holdsLock(lock)
                stored = value
            },
        )

        assertTrue(transformHeldLock)
        assertTrue(saveHeldLock)
        assertEquals("committed", updated)
        assertEquals(2, stored)
    }

    @Test
    fun runtimeDomainRollbackRestoresPreviousDomainsWhileCandidateIsStillPersisted() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(
            advancedSettings = previous.advancedSettings.copy(
                alwaysOnDomains = listOf("candidate.example"),
            ),
        )

        val outcome = VpnSettingsTransactionPolicy.rollbackRuntimeDomains(previous, candidate, candidate)

        assertEquals(previous.advancedSettings.alwaysOnDomains, outcome.persisted.advancedSettings.alwaysOnDomains)
        assertEquals(false, outcome.requiresReapply)
    }

    @Test
    fun runtimeDomainRollbackPreservesSecondEditAndRequestsReapply() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(
            advancedSettings = previous.advancedSettings.copy(alwaysOnDomains = listOf("candidate.example")),
        )
        val persisted = previous.copy(
            advancedSettings = previous.advancedSettings.copy(alwaysOnDomains = listOf("newer.example")),
        )

        val outcome = VpnSettingsTransactionPolicy.rollbackRuntimeDomains(previous, candidate, persisted)

        assertEquals(listOf("newer.example"), outcome.persisted.advancedSettings.alwaysOnDomains)
        assertEquals(previous.advancedSettings.alwaysOnDomains, outcome.runtime.advancedSettings.alwaysOnDomains)
        assertEquals(true, outcome.requiresReapply)
    }

    @Test
    fun successfulRuntimeDomainCommitMergesLatestSnapshotAndReappliesNewerDomains() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(
            advancedSettings = previous.advancedSettings.copy(
                bypassDomains = listOf("candidate.example"),
            ),
        )
        val persisted = previous.copy(
            filterMode = AppFilterMode.ONLY_SELECTED,
            selectedPackages = setOf("fresh.package"),
            advancedSettings = previous.advancedSettings.copy(bypassDomains = listOf("newer.example")),
        )

        val outcome = VpnSettingsTransactionPolicy.acceptRuntimeDomains(candidate, persisted)

        assertEquals(AppFilterMode.ONLY_SELECTED, outcome.runtime.filterMode)
        assertEquals(setOf("fresh.package"), outcome.runtime.selectedPackages)
        assertEquals(listOf("candidate.example"), outcome.runtime.advancedSettings.bypassDomains)
        assertEquals(listOf("newer.example"), outcome.persisted.advancedSettings.bypassDomains)
        assertEquals(true, outcome.requiresReapply)
    }

    @Test
    fun manualSelectionChangeRejectsPreparedCandidateProfileAndRequestsFreshPrepare() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(
            profile = previous.profile.copy(host = "stale-candidate.example"),
            advancedSettings = previous.advancedSettings.copy(manualEndpointCode = "old-manual"),
        )
        val persisted = previous.copy(
            advancedSettings = previous.advancedSettings.copy(manualEndpointCode = "new-manual"),
        )

        val committed = VpnSettingsTransactionPolicy.mergeRuntimeOwnedFields(previous, candidate, persisted)

        assertEquals(previous.profile, committed.profile)
        assertEquals("new-manual", committed.advancedSettings.manualEndpointCode)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(previous, persisted))
    }

    @Test
    fun planOrServerChangeRejectsPreparedCandidateProfileAndRequestsFreshPrepare() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(profile = previous.profile.copy(host = "stale-candidate.example"))
        val persisted = previous.copy(
            userProfile = previous.userProfile.copy(selectedCountryCode = "DE"),
        )

        val committed = VpnSettingsTransactionPolicy.mergeRuntimeOwnedFields(previous, candidate, persisted)

        assertEquals(previous.profile, committed.profile)
        assertEquals("DE", committed.userProfile.selectedCountryCode)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(previous, persisted))
    }

    @Test
    fun runtimeCommitPreservesUserSettingsSavedWhileCandidateWasPreparing() {
        val previous = DefaultStoredSettingsFactory.create()
        val candidate = previous.copy(
            profile = previous.profile.copy(host = "candidate.example"),
            selectedPackages = setOf("stale.package"),
            advancedSettings = previous.advancedSettings.copy(manualEndpointCode = "candidate-endpoint"),
        )
        val persisted = previous.copy(
            filterMode = AppFilterMode.ONLY_SELECTED,
            selectedPackages = setOf("fresh.package"),
            advancedSettings = previous.advancedSettings.copy(
                bypassDomains = listOf("lan.example"),
            ),
        )

        val committed = VpnSettingsTransactionPolicy.mergeRuntimeOwnedFields(
            previous = previous,
            selected = candidate,
            persisted = persisted,
        )

        assertEquals("candidate.example", committed.profile.host)
        assertEquals(AppFilterMode.ONLY_SELECTED, committed.filterMode)
        assertEquals(setOf("fresh.package"), committed.selectedPackages)
        assertEquals("candidate-endpoint", committed.advancedSettings.manualEndpointCode)
        assertEquals(listOf("lan.example"), committed.advancedSettings.bypassDomains)
    }

    @Test
    fun acceptedCandidateBecomesCommittedSettings() {
        assertEquals(
            "candidate",
            VpnSettingsTransactionPolicy.committed(
                previous = "previous",
                candidate = "candidate",
                result = VpnSettingsTransactionPolicy.Result.Accepted,
            ),
        )
    }

    @Test
    fun pendingAndFailedCandidatesKeepPreviousSettings() {
        listOf(
            VpnSettingsTransactionPolicy.Result.Pending,
            VpnSettingsTransactionPolicy.Result.RolledBack,
            VpnSettingsTransactionPolicy.Result.FailedClosed,
        ).forEach { result ->
            assertEquals(
                "previous",
                VpnSettingsTransactionPolicy.committed(
                    previous = "previous",
                    candidate = "candidate",
                    result = result,
                ),
            )
        }
    }
}

private class RecordingStore(
    var current: StoredSettings,
) : AtomicStoredSettingsStore {
    var updateCount: Int = 0

    override fun load(): StoredSettings = current

    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        updateCount += 1
        val (updated, result) = transform(current)
        current = updated
        return result
    }
}
