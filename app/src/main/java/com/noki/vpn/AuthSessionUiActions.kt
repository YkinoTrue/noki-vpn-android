package com.noki.vpn

import com.noki.vpn.data.BackendException
import com.noki.vpn.data.PendingLogoutRevocationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun AppUiRuntime.logout() {
    val notificationHistorySettings = repository.load()
    accountSecurityUiWorkflow.invalidate()
    paymentCheckoutWorkflow.invalidate()
    telegramLoginGateway.cancel()
    telegramAuthPurposeState.invalidate()
    vpnCommands.stop()
    repository.clearVpnRuntimeState()
    val staleEndpointOptionsRefresh = endpointOptionsRefreshJob
    endpointOptionsRefreshJob = null
    endpointOptionsRefreshCountryCode = null
    staleEndpointOptionsRefresh?.cancel()
    val staleClientLatencyRefresh = clientLatencyRefreshJob
    clientLatencyRefreshJob = null
    clientLatencyRefreshTarget = null
    clientLatencyRefreshNetworkSignature = null
    clientLatencyByTarget = emptyMap()
    lastServerStatsRefreshElapsedMs = 0L
    staleClientLatencyRefresh?.cancel()
    appNotificationPollJob?.cancel()
    appNotificationPollJob = null
    val staleLogUpload = logUploadJob
    logUploadJob = null
    logUploadIsAutomatic = false
    automaticLogUploadPending = false
    manualLogUploadPending = false
    staleLogUpload?.cancel()
    advanceAndroidUpdateRevision()
    androidUpdateJob?.cancel()
    androidx.work.WorkManager.getInstance(application).cancelUniqueWork(AndroidUpdateWorker.WORK_NAME)
    val staleAvatarMutation = avatarMutationJob
    avatarMutationJob = null
    staleAvatarMutation?.cancel()
    repository.clearAndroidUpdateAvailable()
    authSessionCoordinator.clear()
    repository.clearAppNotificationHistory(notificationHistorySettings)
    FcmTokenRegistrar.cancelPendingRegistration(repository)
    runCatching { PendingLogoutRevocationWorker.enqueue(application) }
    registrationCodeCooldownJob?.cancel()
    registrationCodeCooldownJob = null
    registrationCodeRequestJob?.cancel()
    registrationCodeRequestJob = null
    registrationCodeVerificationJob?.cancel()
    registrationCodeVerificationJob = null
    registrationUsernameCheckJob?.cancel()
    registrationUsernameCheckJob = null
    registrationWorkflow.invalidate()
    val stalePasswordRecoveryCooldown = passwordRecoveryCooldownJob
    passwordRecoveryCooldownJob = null
    stalePasswordRecoveryCooldown?.cancel()
    val stalePasswordRecoveryOperation = passwordRecoveryOperationJob
    passwordRecoveryOperationJob = null
    stalePasswordRecoveryOperation?.cancel()
    val staleRuntimeSettingsSync = runtimeSettingsSyncJob
    runtimeSettingsSyncJob = null
    staleRuntimeSettingsSync?.cancel()
    accountRecoveryWorkflow.invalidate()
    val staleSessionOperation = sessionOperationJob
    sessionOperationJob = null
    sessionOperationAllowsReplacement = false
    staleSessionOperation?.cancel()
    deviceWorkflow.invalidate()
    cancelBackendRefresh()
    backendDeviceId = ""
    backendDeviceKey = repository.ensureBackendDeviceKey(backendDeviceKey)
    backendDeviceAccessRole = "owner"
    syncedDevices = emptyList()
    syncedLocations = emptyList()
    syncedPlans = emptyList()
    val loggedOutState = LogoutStateReducer.reduce(uiState)
    persistLogoutState(loggedOutState)
    applyAndPersist(loggedOutState)
}

internal fun AppUiRuntime.authFlowCoordinator(): AuthFlowCoordinator {
    return AuthFlowCoordinator(
        authApi = backendApi,
        authenticatedSessionCoordinator = authenticatedSessionCoordinator(),
    )
}

internal fun AppUiRuntime.telegramAuthCoordinator(): TelegramAuthCoordinator =
    TelegramAuthCoordinator(
        authApi = backendApi,
        oidcApi = backendApi,
        authenticatedSessionCoordinator = authenticatedSessionCoordinator(),
    )

internal fun AppUiRuntime.googleAuthCoordinator(): GoogleAuthCoordinator =
    GoogleAuthCoordinator(
        authApi = backendApi,
        authenticatedSessionCoordinator = authenticatedSessionCoordinator(),
    )

internal fun <T> AppUiRuntime.launchSessionOperation(
    allowsReplacement: Boolean = false,
    onStarted: () -> Unit,
    operation: suspend () -> T,
    isCurrent: (Job) -> Boolean = { true },
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    if (sessionOperationJob != null) return false
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Session operation coroutine has no Job")
        try {
            val result = operation()
            if (sessionOperationJob === ownerJob && ownerJob.isActive && isCurrent(ownerJob)) {
                onSuccess(result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (sessionOperationJob === ownerJob && ownerJob.isActive && isCurrent(ownerJob)) {
                onFailure(error)
            }
        } finally {
            if (sessionOperationJob === ownerJob) {
                sessionOperationJob = null
                sessionOperationAllowsReplacement = false
            }
        }
    }
    sessionOperationAllowsReplacement = allowsReplacement
    sessionOperationJob = job
    onStarted()
    job.start()
    return true
}

internal fun <T> AppUiRuntime.launchAuthenticatedSessionOperation(
    replaceDeviceRead: Boolean,
    onStarted: () -> Unit,
    operation: suspend (AuthSessionAttempt) -> T,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null) {
        onFailure(BackendException("auth_required", 401))
        return false
    }
    if (sessionOperationJob != null) {
        if (!replaceDeviceRead || !cancelReplaceableDeviceOperation()) return false
    }
    return launchSessionOperation(
        onStarted = onStarted,
        operation = { operation(attempt) },
        isCurrent = { authSessionCoordinator.isCurrent(attempt) },
        onSuccess = onSuccess,
        onFailure = onFailure,
    )
}

internal fun AppUiRuntime.authenticatedSessionCoordinator(): AuthenticatedSessionCoordinator {
    return AuthenticatedSessionCoordinator(
        authSessionCoordinator = authSessionCoordinator,
        registerCurrentDevice = { token ->
            ensureCurrentDeviceRegistered(token)
        },
        stageTemporaryVpnForRevoke = {
            withContext(Dispatchers.IO) {
                if (repository.loadTemporaryVpnPendingRevoke() == null) {
                    repository.loadTemporaryVpnLease()?.let {
                        repository.markTemporaryVpnLeasePendingRevoke(it)
                    }
                }
            }
        },
        stopTemporaryVpn = vpnCommands::stopAndRevokeTemporary,
        syncBootstrap = { token, baseState ->
            syncFromBackendState(token, baseState)
        },
        publishAuthenticatedState = { state ->
            persistBackendState(state)
            applyAndPersist(state)
        },
    )
}
