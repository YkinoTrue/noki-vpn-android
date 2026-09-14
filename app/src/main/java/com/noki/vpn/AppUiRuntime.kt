package com.noki.vpn

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendLocation
import com.noki.vpn.data.BackendPlan
import com.noki.vpn.data.BootstrapStateMapper
import com.noki.vpn.data.ClientLatencySampler
import com.noki.vpn.data.PendingLogoutRevocationWorker
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.AppVpnService
import com.noki.vpn.vpn.VpnRuntimeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

internal class AppUiRuntime(
    val application: Application,
    savedStateHandle: SavedStateHandle,
    val scope: CoroutineScope,
) {
    internal val pendingVpnStartModeState = PendingVpnStartModeState(savedStateHandle)
    internal val telegramAuthPurposeState = TelegramAuthPurposeState(savedStateHandle)

    val pendingVpnStartMode: VpnRuntimeMode
        get() = pendingVpnStartModeState.mode

    internal val repository = SettingsRepository(application)
    internal val backendApi = BackendApiClient()
    internal val vpnCommands = AndroidVpnServiceCommandGateway(application)
    internal val accountRecoveryWorkflow = AccountRecoveryCoordinator(backendApi)
    internal val authTokenRefresher = AuthTokenRefresher(
        store = repository,
        api = backendApi,
        onRefreshFailure = { error ->
            recordAppLog(
                category = "auth",
                level = "error",
                message = "token_refresh_fail",
                details = error.message,
                errorType = AppErrorMapper.readableErrorType(error),
            )
        },
        onRevocationPending = {
            PendingLogoutRevocationWorker.enqueue(application)
        },
    )
    internal val authSessionCoordinator = AuthSessionCoordinator(
        store = repository,
        refresher = authTokenRefresher,
        onRevocationPending = {
            PendingLogoutRevocationWorker.enqueue(application)
        },
    )
    internal val accountDeletionCoordinator = AccountDeletionCoordinator(authSessionCoordinator, backendApi)
    internal val settingsMutationCoordinator = SettingsMutationCoordinator(repository)
    internal val clientLatencySampler = ClientLatencySampler(context = application)
    internal val appLogUploadCoordinator = AppLogUploadCoordinator(
        exportLogs = { repository.exportAppLogs() },
        shouldUploadAutomatically = { repository.shouldUploadAppLogsAutomatically() },
        markAutomaticallyUploaded = { repository.markAppLogsAutomaticallyUploaded() },
        upload = { request ->
            backendApi.uploadAppLogs(
                token = request.token,
                deviceId = request.deviceId,
                deviceKey = request.deviceKey,
                deviceName = request.deviceName,
                logsText = request.logsText,
                incident = request.incident,
            )
        },
    )
    internal val androidUpdateCoordinator = AndroidUpdateCoordinator(
        app = application,
        repository = repository,
        backendApi = backendApi,
        authRunner = authSessionCoordinator,
        logEvent = { event ->
            recordAppLog(
                category = "android_update",
                message = event.message,
                details = event.details,
                errorType = event.errorType,
            )
        },
    )
    internal val profileAvatarCoordinator = ProfileAvatarCoordinator(
        app = application,
        backendApi = backendApi,
    )
    internal val backendSyncCoordinator = BackendSyncCoordinator(
        bootstrapLoader = backendApi,
        androidUpdateLoader = androidUpdateCoordinator,
        profileAvatarLoader = profileAvatarCoordinator,
    )

    internal var backendDeviceKey: String = ""
    internal var backendDeviceId: String = ""
    internal var backendDeviceAccessRole: String = "owner"
    internal var syncedDevices: List<BackendDevice> = emptyList()
    internal var syncedLocations: List<BackendLocation> = emptyList()
    internal var syncedPlans: List<BackendPlan> = emptyList()
    internal var registrationCodeCooldownJob: Job? = null
    internal var registrationCodeRequestJob: Job? = null
    internal var registrationCodeVerificationJob: Job? = null
    internal var registrationUsernameCheckJob: Job? = null
    internal val registrationWorkflow = RegistrationWorkflowCoordinator()
    internal val deviceWorkflow = DeviceWorkflowCoordinator()
    internal val connectionUiCoordinator = ConnectionUiCoordinator()
    internal var passwordRecoveryCooldownJob: Job? = null
    internal var passwordRecoveryOperationJob: Job? = null
    internal var runtimeSettingsSyncJob: Job? = null
    internal var clientLatencyRefreshJob: Job? = null
    internal var clientLatencyRefreshTarget: List<String>? = null
    internal var clientLatencyRefreshNetworkSignature: String? = null
    internal var sessionOperationJob: Job? = null
    internal var sessionOperationAllowsReplacement: Boolean = false
    internal var logUploadJob: Job? = null
    internal var logUploadIsAutomatic: Boolean = false
    internal var automaticLogUploadPending: Boolean = false
    internal var manualLogUploadPending: Boolean = false
    internal var endpointOptionsRefreshJob: Job? = null
    internal var endpointOptionsRefreshCountryCode: String? = null
    internal var appNotificationPollJob: Job? = null
    internal val telegramLoginGateway = TelegramLoginGateway()
    internal var androidUpdateJob: Job? = null
    internal var androidUpdateRevision: Long = 0L
    internal var avatarMutationJob: Job? = null
    internal var backendRefreshJob: Job? = null
    internal var backendRefreshTrigger: BackendRefreshTrigger? = null
    internal val backendRefreshRequestTracker = BackendSyncRequestTracker()
    internal var lastServerStatsRefreshElapsedMs: Long = 0L
    internal var clientLatencyByTarget: Map<String, Int> = emptyMap()

    internal var _uiState by mutableStateOf(
        SettingsPreparedStatePolicy.withPreparedSettingsState(AppUiState()),
    )
    var uiState: AppUiState
        get() = _uiState
        internal set(value) {
            _uiState = SettingsPreparedStatePolicy.withPreparedSettingsState(value)
        }

    internal val accountSecurityUiWorkflow = AccountSecurityUiWorkflow(
        scope = scope,
        currentState = { uiState },
        publishState = { uiState = it },
        isInvitedDevice = { isCurrentDeviceInvited() },
        currentAuthAttempt = { authSessionCoordinator.attempt() },
        sendEmailCode = { attempt, email ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().sendEmailCode(accountSecurityContext(token), email)
            }
        },
        changeEmail = { attempt, email, code ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().changeEmail(accountSecurityContext(token), email, code)
            }
        },
        changePassword = { attempt, password ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().changePassword(
                    context = accountSecurityContext(token),
                    authAttempt = attempt,
                    currentPassword = null,
                    newPassword = password,
                ).user
            }
        },
        changeUsername = { attempt, username ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().changeUsername(accountSecurityContext(token), username)
            }
        },
        applyUser = { user, returnToSecurity ->
            applyAccountSecurityUser(user = user, returnToSecurity = returnToSecurity)
        },
    )

    internal val paymentCheckoutWorkflow = PaymentCheckoutWorkflow(
        scope = scope,
        currentState = { uiState },
        publishState = { uiState = it },
        currentAuthAttempt = { authSessionCoordinator.attempt() },
        isCurrent = authSessionCoordinator::isCurrent,
        loadConfig = backendApi::paymentConfig,
        create = { attempt, planCode, method ->
            val deviceId = backendDeviceId
            val deviceKey = backendDeviceKey
            authSessionCoordinator.run(attempt) { token ->
                backendApi.createPayment(token, planCode, method, deviceId, deviceKey)
            }
        },
        loadPayments = { attempt ->
            val deviceId = backendDeviceId
            val deviceKey = backendDeviceKey
            authSessionCoordinator.run(attempt) { token -> backendApi.payments(token, deviceId, deviceKey) }
        },
        restorePaymentId = {
            savedStateHandle.get<String>("checkout.device")?.takeIf { it == backendDeviceId && it.isNotBlank() }
                ?.let { savedStateHandle.get<String>("checkout.payment") }
        },
        savePaymentId = { id ->
            savedStateHandle["checkout.device"] = if (id == null) null else backendDeviceId
            savedStateHandle["checkout.payment"] = id
        },
        onPaid = { refreshAllData(showNetworkFailureInline = false) },
    )

    init {
        scope.launch {
            val hasPendingLogoutRevocation = withContext(Dispatchers.IO) {
                try {
                    repository.loadPendingLogoutRevocations().isNotEmpty()
                } catch (_: Exception) {
                    false
                }
            }
            if (hasPendingLogoutRevocation) {
                try {
                    PendingLogoutRevocationWorker.enqueue(application)
                } catch (_: Exception) {
                    // The encrypted tombstone remains and will be scheduled on the next app start.
                }
            }
            val settings = withContext(Dispatchers.IO) { repository.load() }
            val prepared = RuntimeProfilePolicy.normalize(settings)
            val liveVpnRuntimeState = AppVpnService.liveRuntimeState()
            if (liveVpnRuntimeState.state == VpnConnectionState.DISCONNECTED) {
                repository.clearVpnRuntimeState()
            }
            val dailyStats = repository.loadDailyStats()
            val notificationHistoryState = repository.loadAppNotificationHistoryState(prepared)
            authSessionCoordinator.restore(prepared)
            backendDeviceKey = repository.ensureBackendDeviceKey(prepared.backendDeviceKey)
            backendDeviceId = prepared.backendDeviceId
            backendDeviceAccessRole = prepared.backendDeviceAccessRole.ifBlank { "owner" }
            val firstDestination = if (prepared.isAuthenticated) AppDestination.HOME else AppDestination.LOGIN

            uiState = AppUiState(
                isReady = true,
                screenStack = listOf(AppDestination.SPLASH),
                profile = prepared.profile,
                filterMode = prepared.filterMode,
                selectedPackages = prepared.selectedPackages,
                installedApps = emptyList(),
                userProfile = prepared.userProfile,
                personalizationSettings = prepared.personalizationSettings,
                securitySettings = prepared.securitySettings,
                advancedSettings = prepared.advancedSettings,
                endpointOptions = prepared.endpointOptions,
                isAuthenticated = prepared.isAuthenticated,
                connectionState = liveVpnRuntimeState.state,
                vpnRuntimeMode = liveVpnRuntimeState.runtimeMode,
                connectedAtMillis = liveVpnRuntimeState.connectedAtMillis,
                plans = BootstrapStateMapper.initialPlans(),
                devices = BootstrapStateMapper.initialDevices(),
                appNotificationHistory = notificationHistoryState.notifications,
                hasUnreadAppNotifications = notificationHistoryState.hasUnread,
                locations = BootstrapStateMapper.initialLocations(),
                usageBars = BootstrapStateMapper.initialUsageBars(),
                dailyStats = dailyStats,
                currentDeviceAccessRole = backendDeviceAccessRole,
                isAndroidUpdateAvailable = repository.isAndroidUpdateAvailable(),
            )

            if (backendDeviceKey != prepared.backendDeviceKey) {
                repository.updateSettings { latest -> latest.copy(backendDeviceKey = backendDeviceKey) }
            }

            withContext(Dispatchers.IO) { androidUpdateCoordinator.clearSameOrOlderCachedApks() }
            val download = androidUpdateCoordinator.observeDownloads().first()
            uiState = uiState.copy(
                screenStack = listOf(firstDestination),
                androidUpdate = uiState.androidUpdate.copy(
                    isDownloading = download != null && !download.state.isFinished,
                    isReadyToInstall = androidUpdateCoordinator.readyFile(download) != null,
                ),
            )

            scope.launch {
                androidUpdateCoordinator.observeDownloads().collect { work ->
                    if (!uiState.isReady) return@collect
                    advanceAndroidUpdateRevision()
                    uiState = uiState.copy(androidUpdate = uiState.androidUpdate.copy(
                        isDownloading = work != null && !work.state.isFinished,
                        isReadyToInstall = androidUpdateCoordinator.readyFile(work) != null,
                        error = if (work?.state == androidx.work.WorkInfo.State.FAILED) {
                            work.outputData.getString("error")
                        } else uiState.androidUpdate.error,
                    ))
                }
            }

            if (prepared.isAuthenticated && !authSessionCoordinator.snapshot().accessToken.isNullOrBlank()) {
                authSessionCoordinator.attempt()?.let { startupAttempt ->
                    try {
                        authSessionCoordinator.retryAfterUnauthorized(startupAttempt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Keep the encrypted session. Normal authenticated calls retry refresh later.
                    }
                }
                startAppNotificationPolling()
                syncFcmTokenIfAvailable()
                launchBackendRefresh(BackendRefreshTrigger.Initial)
            }
        }
    }

    companion object {
        internal val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}

internal fun AppUiRuntime.tr(
    language: AppLanguage,
    russian: String,
    english: String,
): String = if (language == AppLanguage.RU) russian else english

internal fun AppUiRuntime.isValidEmailAddress(value: String): Boolean =
    AppUiRuntime.EMAIL_PATTERN.matches(value.trim())
