package com.noki.vpn

import android.os.SystemClock
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.AppDiagnosticLogPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val APP_NOTIFICATION_POLL_INTERVAL_MS = 60_000L

internal enum class ManualLogUploadAction {
    Start,
    QueueAfterAutomatic,
    Ignore,
}

internal fun manualLogUploadAction(
    isManualUploadVisible: Boolean,
    hasActiveLane: Boolean,
    laneIsAutomatic: Boolean,
): ManualLogUploadAction = when {
    isManualUploadVisible -> ManualLogUploadAction.Ignore
    !hasActiveLane -> ManualLogUploadAction.Start
    laneIsAutomatic -> ManualLogUploadAction.QueueAfterAutomatic
    else -> ManualLogUploadAction.Ignore
}

internal fun AppUiRuntime.refreshAppNotificationHistoryState() {
    val history = repository.loadAppNotificationHistoryState()
    uiState = uiState.copy(
        appNotificationHistory = history.notifications,
        hasUnreadAppNotifications = history.hasUnread,
    )
}

internal fun AppUiRuntime.openAppNotificationHistory() {
    val history = repository.openAppNotificationHistory()
    uiState = uiState.copy(
        appNotificationHistory = history.notifications,
        hasUnreadAppNotifications = history.hasUnread,
    )
}

internal fun AppUiRuntime.deleteAppNotification(notificationId: String) {
    val history = repository.deleteAppNotification(notificationId)
    uiState = uiState.copy(
        appNotificationHistory = history.notifications,
        hasUnreadAppNotifications = history.hasUnread,
    )
}

internal fun AppUiRuntime.uploadLocalLogs() {
    val language = uiState.personalizationSettings.language
    when (manualLogUploadAction(
        isManualUploadVisible = uiState.isUploadingLogs,
        hasActiveLane = logUploadJob != null,
        laneIsAutomatic = logUploadIsAutomatic,
    )) {
        ManualLogUploadAction.Ignore -> return
        ManualLogUploadAction.QueueAfterAutomatic -> {
            manualLogUploadPending = true
            uiState = uiState.copy(
                isUploadingLogs = true,
                logUploadMessage = tr(language, "Отправка логов…", "Sending logs…"),
            )
            logUploadJob?.cancel()
            return
        }
        ManualLogUploadAction.Start -> Unit
    }
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null) {
        uiState = uiState.copy(
            logUploadMessage = tr(language, "Сначала войдите в аккаунт", "Please sign in first"),
        )
        return
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Log upload coroutine has no Job")
        try {
            val logsText = withContext(Dispatchers.IO) { appLogUploadCoordinator.captureLogs() }
            appLogUploadCoordinator.uploadManual(
                context = appLogUploadContext(attempt.accessToken),
                logsText = logsText,
            )
            if (!isCurrentLogUpload(ownerJob, attempt)) return@launch
            recordAppLog("support", message = "local_logs_uploaded")
            uiState = uiState.copy(
                isUploadingLogs = false,
                logUploadMessage = tr(language, "Логи отправлены", "Logs sent"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrentLogUpload(ownerJob, attempt)) {
                recordAppLog(
                    category = "support",
                    level = "error",
                    message = "local_logs_upload_failed",
                    details = error.message,
                    errorType = AppErrorMapper.readableErrorType(error),
                )
                uiState = uiState.copy(
                    isUploadingLogs = false,
                    logUploadMessage = AppErrorMapper.readableNetworkError(language, error),
                )
            }
        } finally {
            val retryAutomaticUpload = logUploadJob === ownerJob && automaticLogUploadPending
            if (logUploadJob === ownerJob) {
                logUploadJob = null
                logUploadIsAutomatic = false
            }
            if (retryAutomaticUpload) {
                automaticLogUploadPending = false
                maybeUploadLogsAutomatically()
            }
        }
    }
    logUploadIsAutomatic = false
    logUploadJob = job
    uiState = uiState.copy(
        isUploadingLogs = true,
        logUploadMessage = tr(language, "Отправка логов…", "Sending logs…"),
    )
    job.start()
}

internal fun AppUiRuntime.maybeUploadLogsAutomatically() {
    val attempt = authSessionCoordinator.attempt() ?: return
    val due = appLogUploadCoordinator.isAutomaticUploadDue(
        AppDiagnosticLogPolicy.shouldUploadAutomatically(uiState.advancedSettings),
    )
    if (!due) {
        automaticLogUploadPending = false
        return
    }
    if (logUploadJob != null) {
        if (!logUploadIsAutomatic) automaticLogUploadPending = true
        return
    }

    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Automatic log upload coroutine has no Job")
        try {
            if (
                !authSessionCoordinator.isCurrent(attempt) ||
                !AppDiagnosticLogPolicy.shouldUploadAutomatically(uiState.advancedSettings)
            ) {
                return@launch
            }
            val logsText = withContext(Dispatchers.IO) { appLogUploadCoordinator.captureLogs() }
            if (!authSessionCoordinator.isCurrent(attempt) ||
                !AppDiagnosticLogPolicy.shouldUploadAutomatically(repository.load().advancedSettings)
            ) return@launch
            appLogUploadCoordinator.uploadAutomatic(
                context = appLogUploadContext(attempt.accessToken),
                logsText = logsText,
            )
            if (isCurrentLogUpload(ownerJob, attempt)) {
                recordAppLog("support", message = "automatic_logs_uploaded")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrentLogUpload(ownerJob, attempt)) {
                recordAppLog(
                    category = "support",
                    level = "error",
                    message = "automatic_logs_upload_failed",
                    details = error.message,
                    errorType = AppErrorMapper.readableErrorType(error),
                )
            }
        } finally {
            val startPendingManualUpload = logUploadJob === ownerJob && manualLogUploadPending
            if (logUploadJob === ownerJob) {
                logUploadJob = null
                logUploadIsAutomatic = false
            }
            if (startPendingManualUpload) {
                manualLogUploadPending = false
                uiState = uiState.copy(isUploadingLogs = false)
                uploadLocalLogs()
            }
        }
    }
    automaticLogUploadPending = false
    logUploadIsAutomatic = true
    logUploadJob = job
    job.start()
}

internal fun AppUiRuntime.cancelAutomaticLogUpload() {
    automaticLogUploadPending = false
    if (!logUploadIsAutomatic) return
    logUploadJob?.cancel()
}

private fun AppUiRuntime.isCurrentLogUpload(ownerJob: Job, attempt: AuthSessionAttempt): Boolean =
    logUploadJob === ownerJob && ownerJob.isActive && authSessionCoordinator.isCurrent(attempt)

internal fun AppUiRuntime.appLogUploadContext(token: String): AppLogUploadCoordinator.DeviceContext {
    return AppLogUploadCoordinator.DeviceContext(
        token = token,
        deviceId = backendDeviceId.takeIf { it.isNotBlank() },
        deviceKey = backendDeviceKey.takeIf { it.isNotBlank() },
        deviceName = AndroidDeviceInfo.deviceName(),
    )
}

internal fun AppUiRuntime.recordConnectionLog(action: ConnectionStateReducer.LogAction) {
    recordAppLog(
        category = action.category,
        level = action.level,
        message = action.message,
        details = action.details,
        errorType = action.errorType,
        connectionSuccess = action.connectionSuccess,
        endpointRating = action.endpointRating,
    )
}

internal fun AppUiRuntime.recordAppLog(
    category: String,
    level: String = "info",
    message: String,
    details: String? = null,
    errorType: String? = null,
    apiResponseTimeMs: Long? = null,
    connectionSuccess: Boolean? = null,
    endpointRating: String? = null,
) {
    runCatching {
        repository.recordAppLog(
            category = category,
            level = level,
            message = message,
            details = details,
            errorType = errorType,
            serverCountry = currentServerCountry(),
            apiResponseTimeMs = apiResponseTimeMs,
            connectionSuccess = connectionSuccess,
            endpointRating = endpointRating,
        )
    }
}

internal fun AppUiRuntime.currentServerCountry(): String? {
    val selectedCode = uiState.userProfile.selectedCountryCode
    return uiState.locations.firstOrNull { it.code == selectedCode }
        ?.country
        ?.takeIf { it.isNotBlank() }
}

internal fun AppUiRuntime.elapsedSince(startedAtMs: Long): Long {
    return (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
}

internal fun AppUiRuntime.startAppNotificationPolling() {
    if (appNotificationPollJob?.isActive == true) return
    val app = application
    appNotificationPollJob = scope.launch {
        while (isActive) {
            if (authSessionCoordinator.snapshot().accessToken.isNullOrBlank() || !uiState.isAuthenticated) break
            if (!repository.isFcmPushRegistered()) {
                syncFcmTokenIfAvailable()
            }
            try {
                authSessionCoordinator.run { token ->
                    AppNotificationPoller.pollOnce(
                        context = app,
                        repository = repository,
                        backendApi = backendApi,
                        token = token,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthRefreshRejectedException) {
                logout()
                return@launch
            } catch (_: Exception) {
                Unit
            }
            refreshAppNotificationHistoryState()
            delay(APP_NOTIFICATION_POLL_INTERVAL_MS)
        }
    }
}

internal fun AppUiRuntime.syncFcmTokenIfAvailable() {
    FcmTokenRegistrar.syncCurrentTokenIfAvailable(
        context = application,
        repository = repository,
        backendApi = backendApi,
    )
}
