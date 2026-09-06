package com.noki.vpn.data

import android.content.Context
import kotlinx.coroutines.CancellationException

class VpnStartCoordinator internal constructor(
    private val sessionPreparer: VpnSessionPreparer,
) {
    constructor(
        context: Context,
        repository: VpnSessionStore,
        backendApi: VpnSessionApi = BackendApiClient(),
        startupTcpPrecheckTimeoutMs: Int,
        onDiagnostic: (String) -> Unit = {},
    ) : this(
        sessionPreparer = VpnSessionPreparer { token, settings, knownDevices, sessionSelection ->
            VpnSessionCoordinator(
                context = context.applicationContext,
                repository = repository,
                backendApi = backendApi,
                startupTcpPrecheckTimeoutMs = startupTcpPrecheckTimeoutMs,
                onDiagnostic = onDiagnostic,
            ).prepare(
                token = token,
                settings = settings,
                knownDevices = knownDevices,
                sessionSelection = sessionSelection,
            )
        },
    )

    data class Result(
        val settings: StoredSettings,
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val selection: EndpointSelector.EndpointSelectionResult,
        val mergedDevices: List<BackendDevice>,
    )

    sealed interface StartDecision {
        data class FreshSession(val result: Result) : StartDecision
        data class CachedFallback(val error: Throwable) : StartDecision
        data class Failure(val error: Throwable) : StartDecision
    }

    suspend fun resolveStart(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        allowCachedFallback: Boolean = true,
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): StartDecision {
        return runCatching {
            prepare(
                token = token,
                settings = settings,
                knownDevices = knownDevices,
                sessionSelection = sessionSelection,
            ).also { result ->
                if (!VpnProfileValidator.isUsable(result.settings)) {
                    throw UnusableFreshProfileException()
                }
            }
        }.fold(
            onSuccess = { StartDecision.FreshSession(it) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                if (allowCachedFallback &&
                    RuntimeProfilePolicy.isCachedProfileUsable(settings) &&
                    BackendRetryPolicy.isTransient(error)
                ) {
                    StartDecision.CachedFallback(error)
                } else {
                    StartDecision.Failure(error)
                }
            },
        )
    }

    suspend fun prepare(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): Result {
        val prepared = sessionPreparer.prepare(
            token = token,
            settings = settings,
            knownDevices = knownDevices,
            sessionSelection = sessionSelection,
        )
        return Result(
            settings = prepared.settings,
            session = prepared.session,
            currentDevice = prepared.currentDevice,
            selection = prepared.selection,
            mergedDevices = mergeCurrentDevice(knownDevices, prepared.currentDevice),
        )
    }

    fun interface VpnSessionPreparer {
        suspend fun prepare(
            token: String,
            settings: StoredSettings,
            knownDevices: List<BackendDevice>,
            sessionSelection: VpnSessionSelection,
        ): VpnSessionCoordinator.Result
    }

    private companion object {
        fun mergeCurrentDevice(
            knownDevices: List<BackendDevice>,
            currentDevice: BackendDevice,
        ): List<BackendDevice> {
            return knownDevices.filterNot { it.deviceKey == currentDevice.deviceKey } + currentDevice
        }
    }
}

internal class UnusableFreshProfileException : RuntimeException("unusable_profile")
