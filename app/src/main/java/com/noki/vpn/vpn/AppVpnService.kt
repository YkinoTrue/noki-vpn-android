package com.noki.vpn.vpn

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.noki.vpn.AppNotificationPoller
import com.noki.vpn.AppLogUploadCoordinator
import com.noki.vpn.NokiQuickSettingsTileService
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppDiagnosticLogPolicy
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendRetryPolicy
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.DeviceTrafficMonitor
import com.noki.vpn.data.DeviceIdentity
import com.noki.vpn.data.EndpointHealth
import com.noki.vpn.data.EndpointHealthEvent
import com.noki.vpn.data.EndpointHealthEventReporter
import com.noki.vpn.data.EndpointHealthEventType
import com.noki.vpn.data.EndpointHealthEvents
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.PendingLogoutRevocationWorker
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.TemporaryVpnLease
import com.noki.vpn.data.TemporaryVpnLeasePolicy
import com.noki.vpn.data.TemporaryVpnSessionCoordinator
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.VpnIncidentReport
import com.noki.vpn.data.VpnSessionSelection
import com.noki.vpn.data.VpnStartCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.Instant
import java.util.UUID

class AppVpnService : VpnService() {
    private lateinit var isXrayRuntimeAvailable: () -> Boolean
    private lateinit var underlyingNetworkSource: AndroidUnderlyingNetworkSource
    private val backendApi = BackendApiClient()
    private val notificationHandler = Handler(Looper.getMainLooper())
    private lateinit var delayedTaskScheduler: DelayedTaskScheduler
    private lateinit var notificationController: VpnNotificationController
    private lateinit var notificationFactory: VpnNotificationFactory
    private lateinit var screenStateMonitor: VpnScreenStateMonitor
    private var connectedAtMillis: Long? = null
    private var tunnel: TunHandle?
        get() = connectionOrchestrator.currentTunnel()
        set(value) = connectionOrchestrator.replaceTunnel(value)
    private var currentState: VpnConnectionState
        get() = connectionOrchestrator.currentState()
        set(value) = connectionOrchestrator.updateState(value)
    private lateinit var statsCoordinator: VpnStatsCoordinator
    private var runtimeCoreSequence: Long = 0L
    private lateinit var warmupController: VpnWarmupController<BackendVpnSession>
    private lateinit var connectionPreparer: VpnConnectionPreparer
    private lateinit var temporaryVpnCoordinator: TemporaryVpnSessionCoordinator
    private lateinit var settingsCommitCoordinator: VpnSettingsCommitCoordinator
    private lateinit var connectionOrchestrator: VpnConnectionOrchestrator
    private var backgroundJob: Job = SupervisorJob()
    private var backgroundScope: CoroutineScope = CoroutineScope(backgroundJob + Dispatchers.IO)
    private val destroyCleanupJob: Job = SupervisorJob()
    private val destroyCleanupScope: CoroutineScope = CoroutineScope(destroyCleanupJob + Dispatchers.IO)
    @Volatile
    private var pendingStartOptions: VpnServiceStartCommandPolicy.StartOptions? = null
    private var activeEndpointNetworkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER
    private val activeUnderlyingNetworkSignature: String?
        get() = connectionOrchestrator.currentUnderlaySignature()
    private lateinit var networkMonitor: VpnNetworkMonitor
    private var lastUnderlyingAvailability: UnderlyingNetworkAvailability? = null
    private var notificationServerLabel: String = "Латвия"
    private var activeSettings: StoredSettings?
        get() = connectionOrchestrator.currentSettings()
        set(value) = connectionOrchestrator.updateSettings(value)
    private lateinit var endpointHealthController: VpnEndpointHealthController
    private lateinit var watchdogController: VpnWatchdogController
    private var lockdownRecoveryActive: Boolean = false
    private var currentRuntimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT
    private var activeTemporaryLease: TemporaryVpnLease? = null
    private val lockdownRecoveryOwner = Any()
    private val selectionReprepareOwner = Any()
    private val runtimeDomainReapplyOwner = Any()
    private val temporaryLeaseExpiryOwner = Any()
    private val transientRecoveryOwner = Any()
    private var transientRecoveryAttempt = 0
    private val lockdownRecoveryTask = {
        if (currentState == VpnConnectionState.FAILED && tunnel != null) {
            reconnectFailedClosedVpn(allowCachedFallback = true)
        }
    }
    private val selectionReprepareTask = {
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            if (connectionOrchestrator.transitionSnapshot().active) {
                scheduleFreshSelectionPrepare()
            } else {
                refreshConnectedVpn(allowCachedFallback = true)
            }
        }
    }
    private val runtimeDomainReapplyTask = {
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            applyRuntimeSettings()
        }
    }
    override fun onCreate() {
        super.onCreate()
        updateLiveRuntimeState(VpnConnectionState.DISCONNECTED, null)
        delayedTaskScheduler = HandlerDelayedTaskScheduler(notificationHandler)
        notificationFactory = VpnNotificationFactory(this)
        warmupController = VpnWarmupController(
            scheduler = delayedTaskScheduler,
            delayMillis = BACKGROUND_ENDPOINT_WARMUP_DELAY_MS,
        )
        notificationController = VpnNotificationController(
            scheduler = delayedTaskScheduler,
            speedUpdateIntervalMillis = NOTIFICATION_SPEED_UPDATE_INTERVAL_MS,
            pollIntervalMillis = APP_NOTIFICATION_POLL_INTERVAL_MS,
            updateActiveNotification = ::updateActiveNotification,
            pollNotifications = ::pollAppNotifications,
        )
        screenStateMonitor = VpnScreenStateMonitor(this, notificationController::setScreenOn)
        val tunFactory = AndroidTunInterfaceFactory(this)
        underlyingNetworkSource = AndroidUnderlyingNetworkSource(this)
        networkMonitor = VpnNetworkMonitor.android(
            context = this,
            source = underlyingNetworkSource,
            scheduler = delayedTaskScheduler,
            debounceMillis = NETWORK_CHANGE_DEBOUNCE_MS,
        )
        val connectionRepository = SettingsRepository(this)
        val startCoordinator = VpnStartCoordinator(
            context = applicationContext,
            repository = connectionRepository,
            startupTcpPrecheckTimeoutMs = STARTUP_TCP_PRECHECK_TIMEOUT_MS,
            onDiagnostic = { details ->
                connectionRepository.recordAppLog(
                    category = "vpn",
                    message = "profile_prepare_diagnostic",
                    details = details,
                )
            },
        )
        connectionPreparer = VpnConnectionPreparer(
            store = connectionRepository,
            currentNetworkKind = {
                underlyingNetworkSource.currentSnapshot()?.kind
                    ?: EndpointRankingPolicy.NetworkKind.OTHER
            },
            resolveStart = { token, settings, fallback, sessionSelection ->
                startCoordinator.resolveStart(
                    token = token,
                    settings = settings,
                    knownDevices = emptyList(),
                    allowCachedFallback = fallback,
                    sessionSelection = sessionSelection,
                )
            },
            refreshAccessToken = {
                try {
                    AuthTokenRefresher(
                        connectionRepository,
                        backendApi,
                        onRevocationPending = {
                            PendingLogoutRevocationWorker.enqueue(applicationContext)
                        },
                    ).refreshStoredTokens()?.accessToken
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            },
            retryCount = FRESH_SESSION_RETRY_COUNT,
            deadlineMillis = FRESH_SESSION_DEADLINE_MS,
            nowMillis = SystemClock::elapsedRealtime,
        )
        temporaryVpnCoordinator = TemporaryVpnSessionCoordinator(
            store = connectionRepository,
            api = backendApi,
            publicKeyProvider = DeviceIdentity::publicKeyBase64,
            deviceKeyProvider = { DeviceIdentity.stableDeviceKey(applicationContext) },
            deviceNameProvider = AndroidDeviceInfo::deviceName,
            platformProvider = { "android" },
            challengeSigner = DeviceIdentity::signChallenge,
            profileSelector = { session ->
                EndpointSelector.profileForSession(
                    context = applicationContext,
                    session = session,
                    settings = AdvancedSettings(),
                )
            },
        )
        settingsCommitCoordinator = VpnSettingsCommitCoordinator(connectionRepository)
        statsCoordinator = VpnStatsCoordinator(
            context = this,
            scope = backgroundScope,
            scheduler = delayedTaskScheduler,
            measureConnectedLatencyMs = {
                if (currentState != VpnConnectionState.CONNECTED) null else {
                    measureRuntimeReadiness(recovery = false)
                        ?.coerceIn(1L, Int.MAX_VALUE.toLong())
                        ?.toInt()
                }
            },
            onLatencySample = { locationCode, latencyMs ->
                broadcastState(
                    state = currentState,
                    latencyLocationCode = locationCode,
                    latencyMs = latencyMs,
                )
            },
        )
        endpointHealthController = VpnEndpointHealthController(
            scheduler = delayedTaskScheduler,
            intervalMillis = EndpointHealthEvents.HEARTBEAT_INTERVAL_MS,
            launchHeartbeat = ::launchEndpointHealthHeartbeat,
        )
        watchdogController = VpnWatchdogController(
            scheduler = delayedTaskScheduler,
            nowMillis = SystemClock::elapsedRealtime,
            launchProbe = ::launchWatchdogProbe,
            isLockdown = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled
            },
            evidenceFreshMillis = DATA_PATH_EVIDENCE_FRESH_MS,
        )
        val connectedSidecars = OwnedVpnConnectedSidecars(
            onStart = { owner, settings -> startConnectedSidecars(owner, settings) },
            onStop = { owner -> stopConnectedSidecars(owner) },
        )
        val controller = XrayController(applicationContext) {
            if (currentState == VpnConnectionState.CONNECTED) watchdogController.forceProbe()
        }
        isXrayRuntimeAvailable = controller::isRuntimeAvailable
        connectionOrchestrator = VpnConnectionOrchestrator(
            xray = controller,
            tunFactory = tunFactory,
            preparer = connectionPreparer,
            settings = settingsCommitCoordinator,
            sidecars = connectedSidecars,
        )
        notificationController.setScreenOn(screenStateMonitor.isInteractive())
        screenStateMonitor.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SettingsRepository(this).recordAppLog(
                category = "vpn",
                message = "vpn_system_mode",
                details = "always_on=$isAlwaysOn; lockdown=$isLockdownEnabled",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val temporaryStopDecision = VpnServiceStartCommandPolicy.temporaryStopDecision(
            action = intent?.action,
            runtimeMode = currentRuntimeMode,
        )
        if (intent?.action != ACTION_START_TEMPORARY && intent?.action != ACTION_STOP_AND_REVOKE_TEMPORARY) {
            backgroundScope.launch { temporaryVpnCoordinator.retryPendingRevoke() }
        }
        when (temporaryStopDecision) {
            VpnServiceStartCommandPolicy.TemporaryStopDecision.StopAndRevoke -> {
                stopVpn(startId, revokeTemporaryLease = true)
                return START_STICKY
            }
            VpnServiceStartCommandPolicy.TemporaryStopDecision.RevokeOnly -> {
                revokeTemporaryLeaseWithoutStoppingAccount(startId)
                return START_STICKY
            }
            VpnServiceStartCommandPolicy.TemporaryStopDecision.NotRequested -> Unit
        }
        when (intent?.action) {
            ACTION_STOP -> stopVpn(startId)
            ACTION_RESTART -> manualRestartVpn()
            ACTION_QUERY_STATE -> broadcastCurrentState()
            ACTION_APPLY_SETTINGS -> applyRuntimeSettings()
            else -> {
                val options = VpnServiceStartCommandPolicy.startOptions(
                    isNullIntent = intent == null,
                    action = intent?.action,
                    refreshSessionExtra = intent?.getBooleanExtra(EXTRA_REFRESH_SESSION, false) == true,
                )
                val repository = SettingsRepository(this)
                val resumeTemporaryLease = intent == null &&
                    !repository.load().isAuthenticated &&
                    TemporaryVpnLeasePolicy.isUsable(
                        repository.loadTemporaryVpnLease(),
                    )
                if (options.runtimeMode == VpnRuntimeMode.AUTH_TEMP || resumeTemporaryLease) {
                    startTemporaryVpn()
                } else {
                    startVpn(
                        forceRefreshSession = options.forceRefreshSession,
                        allowCachedFallback = options.allowCachedFallback,
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        screenStateMonitor.stop()
        stopNetworkChangeMonitor()
        pendingStartOptions = null
        cancelDelayedVpnCallbacks()
        connectionOrchestrator.invalidate()
        connectionOrchestrator.cancelReadinessProbe()
        val activeTransition = connectionOrchestrator.activeTransitionJob()
        val repository = SettingsRepository(this)
        val destroyCoordinator = VpnDestroyCoordinator(
            lifecycleMutex = connectionOrchestrator.lifecycleMutex,
            cancelAndJoinActiveTransition = { activeTransition?.cancelAndJoin() },
            releaseResources = {
                releaseVpnResources(
                    repository = repository,
                    finalState = VpnConnectionState.DISCONNECTED,
                    stopService = false,
                )
            },
            cancelBackgroundWork = backgroundJob::cancel,
        )
        destroyCleanupScope.launch {
            destroyCoordinator.destroy()
        }.invokeOnCompletion {
            destroyCleanupJob.cancel()
        }
        super.onDestroy()
    }

    private fun startVpn(
        forceRefreshSession: Boolean,
        allowCachedFallback: Boolean,
    ) {
        val transition = connectionOrchestrator.transitionSnapshot()
        if (transition.active) {
            val activeOperation = when (transition.operation) {
                VpnConnectionOperation.STOP -> VpnServiceStartCommandPolicy.ActiveOperation.Stop
                VpnConnectionOperation.RESTART -> VpnServiceStartCommandPolicy.ActiveOperation.Restart
                else -> VpnServiceStartCommandPolicy.ActiveOperation.Connection
            }
            if (VpnServiceStartCommandPolicy.activeStartDecision(activeOperation) ==
                VpnServiceStartCommandPolicy.ActiveStartDecision.QueueAfterCleanup
            ) {
                pendingStartOptions = VpnServiceStartCommandPolicy.StartOptions(forceRefreshSession, allowCachedFallback)
            }
            // STOP may have released the tunnel but still be staging a revoke.
            // A state query can stopSelf in that gap; an admitted START must wait.
            broadcastState(currentState)
            return
        }

        pendingStartOptions = null
        if (tunnel != null && currentState == VpnConnectionState.CONNECTED) {
            if (forceRefreshSession) {
                refreshConnectedVpn(allowCachedFallback = allowCachedFallback)
                return
            }
            broadcastCurrentState()
            updateActiveNotification()
            return
        }

        currentRuntimeMode = VpnRuntimeMode.ACCOUNT
        activeTemporaryLease = null
        announceConnecting(VpnRuntimeMode.ACCOUNT)
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "service_connect_start")
        launchConnectionTransition(repository) { generationId ->
            performFreshStart(
                repository = repository,
                forceRefreshSession = forceRefreshSession,
                allowCachedFallback = allowCachedFallback,
                generationId = generationId,
            )
        }
    }

    private fun startTemporaryVpn() {
        val transition = connectionOrchestrator.transitionSnapshot()
        if (transition.active) {
            val activeOperation = when (transition.operation) {
                VpnConnectionOperation.STOP -> VpnServiceStartCommandPolicy.ActiveOperation.Stop
                VpnConnectionOperation.RESTART -> VpnServiceStartCommandPolicy.ActiveOperation.Restart
                else -> VpnServiceStartCommandPolicy.ActiveOperation.Connection
            }
            if (VpnServiceStartCommandPolicy.activeStartDecision(activeOperation) ==
                VpnServiceStartCommandPolicy.ActiveStartDecision.QueueAfterCleanup
            ) {
                pendingStartOptions = VpnServiceStartCommandPolicy.StartOptions(
                    forceRefreshSession = true,
                    allowCachedFallback = false,
                    runtimeMode = VpnRuntimeMode.AUTH_TEMP,
                )
            }
            broadcastState(currentState)
            return
        }

        pendingStartOptions = null
        if (tunnel != null && currentState == VpnConnectionState.CONNECTED) {
            if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                broadcastCurrentState()
                updateActiveNotification()
            }
            return
        }

        currentRuntimeMode = VpnRuntimeMode.AUTH_TEMP
        announceConnecting(VpnRuntimeMode.AUTH_TEMP)
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "temporary_vpn_connect_start")
        launchConnectionTransition(repository) { generationId ->
            performTemporaryStart(repository, generationId)
        }
    }

    private suspend fun performTemporaryStart(
        repository: SettingsRepository,
        generationId: Long,
    ) {
        val lease = try {
            temporaryVpnCoordinator.prepare()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = if (error is BackendException && error.statusCode == 429) {
                "temporary_vpn_limit"
            } else {
                "temporary_vpn_prepare_error"
            }
            failStart(repository, reason, error)
            return
        }
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        val baseline = repository.load()
        val settings = baseline.copy(
            profile = lease.profile,
            filterMode = AppFilterMode.ALL_APPS,
            selectedPackages = emptySet(),
            advancedSettings = AdvancedSettings(),
            endpointOptions = emptyList(),
            userProfile = baseline.userProfile.copy(selectedServerCode = lease.locationCode),
            isAuthenticated = false,
            backendAccessToken = null,
            backendRefreshToken = null,
            backendRefreshRequestId = null,
            backendAccessTokenExpiresInSeconds = null,
            backendRefreshExpiresAt = null,
        )
        activeTemporaryLease = lease
        startTunnel(
            repository = repository,
            settings = settings,
            preparationBaseline = baseline,
            preparedMetadata = null,
            generationId = generationId,
            runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            persistSettings = false,
        )
    }

    private fun announceConnecting(runtimeMode: VpnRuntimeMode = currentRuntimeMode) {
        currentRuntimeMode = runtimeMode
        broadcastState(VpnConnectionState.CONNECTING)
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "Подключение",
                text = "Запуск VPN...",
                showActions = true,
            ),
        )
    }

    private suspend fun performFreshStart(
        repository: SettingsRepository,
        forceRefreshSession: Boolean,
        allowCachedFallback: Boolean,
        generationId: Long,
    ) {
        val prepared = prepareConnectionSettings(
            repository = repository,
            forceRefreshSession = forceRefreshSession,
            allowCachedFallback = allowCachedFallback,
            destructiveOnFailure = true,
        ) ?: return
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        val cachedStart = !forceRefreshSession && prepared.pendingWarmupSession == null
        if (cachedStart) {
            repository.recordAppLog(
                category = "vpn",
                message = "handover_cached_start",
                details = "endpoint=${prepared.candidateSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
            )
        }
        startTunnel(
            repository = repository,
            settings = normalizeInstalledPackages(prepared.candidateSettings),
            preparationBaseline = prepared.preparationBaseline,
            preparedMetadata = prepared,
            generationId = generationId,
        )
        if (cachedStart && currentState == VpnConnectionState.CONNECTED &&
            connectionOrchestrator.isCurrent(generationId)
        ) {
            repository.recordAppLog(
                category = "vpn",
                message = "handover_cached_success",
                details = "endpoint=${prepared.candidateSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
                connectionSuccess = true,
            )
            scheduleFreshSelectionPrepare()
        }
    }

    private fun launchConnectionTransition(
        repository: SettingsRepository,
        block: suspend (Long) -> Unit,
    ) {
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.CONNECTION,
            onError = { generationId, error ->
                handleTransitionException(repository, generationId, error)
            },
            block = block,
        )
    }

    private suspend fun handleTransitionException(
        repository: SettingsRepository,
        generationId: Long,
        error: Throwable,
    ) {
        if (!connectionOrchestrator.isCurrent(generationId)) return
        connectionOrchestrator.withLifecycleLock {
            if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
            SafeLog.e(TAG, "VPN lifecycle transition failed", error)
            if (ConnectedWatchdogPolicy.transitionExceptionDecision(isActiveLockdownRecovery()) ==
                ConnectedWatchdogPolicy.TransitionExceptionDecision.KeepTruthfulLockdownFailure &&
                tunnel != null
            ) {
                connectionOrchestrator.stopXray()
                enterTruthfulLockdownFailure(repository, "transition_error")
                return@withLifecycleLock
            }
            failStart(repository, "transition_error", error)
        }
    }

    private suspend fun prepareConnectionSettings(
        repository: SettingsRepository,
        forceRefreshSession: Boolean,
        allowCachedFallback: Boolean,
        destructiveOnFailure: Boolean,
        sessionSelection: VpnSessionSelection? = null,
        incidentId: String? = null,
        onFailure: (Throwable) -> Unit = {},
    ): PreparedVpnSession? {
        val startedAtMs = SystemClock.elapsedRealtime()
        return when (val outcome = connectionPreparer.prepare(
            forceRefreshSession = forceRefreshSession,
            allowCachedFallback = allowCachedFallback,
            sessionSelection = sessionSelection,
            onRetry = { attempt, error ->
                repository.recordAppLog(
                    category = "vpn",
                    message = "fresh_session_retry",
                    details = incidentDetails(
                        incidentId,
                        "attempt=$attempt; error_type=${prepareFailureReason(error)}",
                    ),
                )
            },
            onCachedFallback = { error ->
                repository.recordAppLog(
                    category = "vpn",
                    level = "error",
                    message = "connect_fail",
                    details = incidentDetails(incidentId, error.message),
                    errorType = prepareFailureReason(error),
                    apiResponseTimeMs = elapsedSince(startedAtMs),
                    connectionSuccess = false,
                )
                repository.recordAppLog(
                    category = "vpn",
                    message = "cached_profile_start",
                    details = "fresh_session_error=${prepareFailureReason(error)}",
                    endpointRating = endpointRatingSnapshot(repository, repository.load()),
                )
            },
        )) {
            is VpnConnectionPreparer.Outcome.Success -> outcome.session.also { prepared ->
                prepared.pendingWarmupSession?.let { result ->
                    repository.recordAppLog(
                        category = "vpn",
                        message = "endpoint_selected",
                        details = "endpoint=${result.selection.endpointCode}",
                        endpointRating = result.selection.endpointRating,
                    )
                    repository.recordAppLog(
                        category = "vpn",
                        message = "vpn_session_created",
                        apiResponseTimeMs = elapsedSince(startedAtMs),
                        endpointRating = repository.endpointRatingSnapshot(
                            result.settings.endpointOptions.map { it.code },
                            result.selection.networkKind,
                        ),
                    )
                }
            }
            is VpnConnectionPreparer.Outcome.Failure -> {
                val error = outcome.error
                onFailure(error)
                if (error.message != "auth_required" && error.message != "unusable_profile") {
                    repository.recordAppLog(
                        category = "vpn",
                        level = "error",
                        message = "connect_fail",
                        details = incidentDetails(incidentId, error.message),
                        errorType = prepareFailureReason(error),
                        apiResponseTimeMs = elapsedSince(startedAtMs),
                        connectionSuccess = false,
                    )
                }
                if (error.message == "unusable_profile") {
                    Log.e(TAG, "Backend returned unusable VPN profile")
                }
                handlePrepareFailure(
                    repository = repository,
                    reason = prepareFailureReason(error),
                    error = error,
                    destructiveOnFailure = destructiveOnFailure,
                )
                null
            }
        }
    }

    private suspend fun acceptPreparedMetadata(
        repository: SettingsRepository,
        prepared: PreparedVpnSession,
    ) {
        activeEndpointNetworkKind = prepared.selectedNetworkKind
        prepared.pendingWarmupSession?.let { result ->
            recordStartupTcpPrecheck(repository, result.selection, result.settings)
            warmupController.setPending(result.session)
        }
    }

    private fun handlePrepareFailure(
        repository: SettingsRepository,
        reason: String,
        error: Throwable?,
        destructiveOnFailure: Boolean,
    ) {
        if (destructiveOnFailure) {
            failStart(repository, reason, error)
            return
        }
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = "refresh_prepare_failed",
            details = error?.message,
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            broadcastState(VpnConnectionState.CONNECTED)
            updateActiveNotification()
        } else if (currentState == VpnConnectionState.FAILED && tunnel != null) {
            if (ConnectedWatchdogPolicy.retryFailureDecision(
                    isLockdown = isActiveLockdownRecovery(),
                    stage = ConnectedWatchdogPolicy.RetryFailureStage.PermanentPrepare,
                ) == ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule
            ) {
                enterTruthfulLockdownFailure(repository, reason)
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "VPN error",
                        text = "VPN engine failed; traffic remains blocked until reconnect or disconnect.",
                        showActions = true,
                    ),
                )
                broadcastState(VpnConnectionState.FAILED, reason)
            }
        }
    }

    private fun elapsedSince(startedAtMs: Long): Long {
        return (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
    }

    private fun normalizeInstalledPackages(settings: StoredSettings): StoredSettings {
        val routingRules = AppVpnRoutingPolicy.rules(
            appPackageName = packageName,
            filterMode = settings.filterMode,
            selectedPackages = settings.selectedPackages,
            isInstalled = { candidate ->
                runCatching { packageManager.getApplicationInfo(candidate, 0) }.isSuccess
            },
        )
        return if (routingRules.removedPackages.isEmpty()) {
            settings
        } else {
            settings.copy(selectedPackages = settings.selectedPackages - routingRules.removedPackages)
        }
    }

    private fun commitSettingsTransaction(
        repository: SettingsRepository,
        preparationBaseline: StoredSettings,
        candidate: StoredSettings,
        result: VpnSettingsTransactionPolicy.Result,
    ): StoredSettings {
        val outcome = settingsCommitCoordinator.commitRuntimeCandidate(
            previousRuntime = activeSettings ?: preparationBaseline,
            preparationBaseline = preparationBaseline,
            candidate = candidate,
            result = result,
        )
        activeSettings = outcome.runtime
        if (outcome.requiresFreshPrepare) scheduleFreshSelectionPrepare()
        return outcome.runtime
    }

    private fun scheduleFreshSelectionPrepare() {
        delayedTaskScheduler.schedule(
            owner = selectionReprepareOwner,
            delayMillis = RUNTIME_SETTINGS_RETRY_DELAY_MS,
            task = selectionReprepareTask,
        )
    }

    private fun restoreRuntimeDomainSettings(
        repository: SettingsRepository,
        previousSettings: StoredSettings,
        candidateSettings: StoredSettings,
    ) {
        var domainOutcome: VpnSettingsTransactionPolicy.RuntimeDomainOutcome? = null
        repository.updateSettings { persisted ->
            VpnSettingsTransactionPolicy.rollbackRuntimeDomains(
                previous = previousSettings,
                candidate = candidateSettings,
                persisted = persisted,
            ).also { domainOutcome = it }.persisted
        }
        val outcome = checkNotNull(domainOutcome)
        activeSettings = outcome.runtime
        if (outcome.requiresReapply) scheduleRuntimeDomainReapply()
    }

    private fun scheduleRuntimeDomainReapply() {
        delayedTaskScheduler.schedule(
            owner = runtimeDomainReapplyOwner,
            delayMillis = RUNTIME_SETTINGS_RETRY_DELAY_MS,
            task = runtimeDomainReapplyTask,
        )
    }

    private fun cancelDelayedVpnCallbacks() {
        delayedTaskScheduler.cancel(selectionReprepareOwner)
        delayedTaskScheduler.cancel(runtimeDomainReapplyOwner)
        delayedTaskScheduler.cancel(lockdownRecoveryOwner)
        delayedTaskScheduler.cancel(temporaryLeaseExpiryOwner)
        delayedTaskScheduler.cancel(transientRecoveryOwner)
        transientRecoveryAttempt = 0
        lockdownRecoveryActive = false
    }

    private fun scheduleTransientRecovery(
        repository: SettingsRepository,
        settings: StoredSettings,
        incidentId: String?,
    ) {
        val attempt = transientRecoveryAttempt
        val delayMillis = ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt)
        transientRecoveryAttempt += 1
        repository.recordAppLog(
            category = "vpn",
            message = "control_plane_retry_scheduled",
            details = incidentDetails(
                incidentId,
                "attempt=${attempt + 1}; delay_ms=$delayMillis; network_kind=${activeEndpointNetworkKind.name.lowercase(Locale.ROOT)}",
            ),
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        delayedTaskScheduler.schedule(transientRecoveryOwner, delayMillis) {
            val startDecision = ConnectedWatchdogPolicy.transientStartDecision(attempt)
            when {
                currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP -> Unit
                currentState == VpnConnectionState.CONNECTED && tunnel != null -> recoverConnectedVpn(settings)
                currentState == VpnConnectionState.FAILED && tunnel != null -> {
                    reconnectFailedClosedVpn(
                        allowCachedFallback = startDecision.allowCachedFallback,
                    )
                }
                currentState == VpnConnectionState.FAILED || currentState == VpnConnectionState.DISCONNECTED -> {
                    startVpn(
                        forceRefreshSession = startDecision.forceRefreshSession,
                        allowCachedFallback = startDecision.allowCachedFallback,
                    )
                }
            }
        }
    }

    private fun resetTransientRecovery() {
        delayedTaskScheduler.cancel(transientRecoveryOwner)
        transientRecoveryAttempt = 0
    }

    private suspend fun startTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        preparedMetadata: PreparedVpnSession?,
        generationId: Long,
        runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
        persistSettings: Boolean = true,
    ) = connectionOrchestrator.withLifecycleLock {
        if (!connectionOrchestrator.isCurrent(generationId) ||
            currentState != VpnConnectionState.CONNECTING
        ) {
            return@withLifecycleLock
        }
        currentRuntimeMode = runtimeMode
        preparedMetadata?.let { acceptPreparedMetadata(repository, it) }
        notificationServerLabel = VpnServiceLogContext.serverLabel(settings)
        val underlyingSnapshot = underlyingNetworkSource.currentSnapshot()
        recordDiagnostic(
            repository = repository,
            message = "tunnel_start",
            details = buildString {
                append("generation=").append(generationId)
                append("; runtime_mode=").append(runtimeMode.name.lowercase(Locale.ROOT))
                append("; endpoint=").append(settings.profile.endpointCode)
                append("; proxy_type=").append(settings.profile.proxyType)
                append("; transport=").append(settings.profile.transport)
                append("; transport_mode=").append(settings.profile.transportMode)
                append("; security=").append(settings.profile.security)
                append("; port=").append(settings.profile.port)
                append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
                append("; underlay=").append(underlyingSnapshot?.details.orEmpty())
            },
        )

        if (!isXrayRuntimeAvailable()) {
            Log.e(TAG, "Xray runtime is unavailable")
            failStart(repository, "runtime_unavailable", category = "xray")
            return@withLifecycleLock
        }

        val established = try {
            connectionOrchestrator.establishTunnel(settings, underlyingSnapshot)
        } catch (error: TunInterfaceConfigurationException) {
            SafeLog.e(TAG, "Failed to apply per-app VPN rules", error)
            failStart(repository, error.reason, error)
            return@withLifecycleLock
        }
        if (established == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            failStart(repository, "interface_error")
            return@withLifecycleLock
        }
        if (!connectionOrchestrator.isCurrent(generationId)) {
            runCatching { established.close() }
            return@withLifecycleLock
        }

        tunnel = established
        connectionOrchestrator.updateUnderlay(underlyingSnapshot)
        recordDiagnostic(
            repository = repository,
            message = "tun_established",
            details = "generation=$generationId; underlay=${underlyingSnapshot?.details.orEmpty()}",
        )
        underlyingSnapshot?.let { snapshot ->
            repository.recordAppLog(
                category = "vpn",
                message = "vpn_underlying_networks",
                details = snapshot.details,
                serverCountry = notificationServerLabel,
            )
        }
        val config = XrayConfigFactory.build(
            settings.profile,
            settings.advancedSettings,
        )
        val started = connectionOrchestrator.startXray(config)
        recordDiagnostic(
            repository = repository,
            message = "xray_start_result",
            details = "generation=$generationId; started=$started; config_chars=${config.length}",
            level = if (started) "info" else "error",
        )
        if (!connectionOrchestrator.isCurrent(generationId)) {
            connectionOrchestrator.stopXray()
            if (tunnel === established) tunnel = null
            runCatching { established.close() }
            return@withLifecycleLock
        }
        val readinessLatencyMs = if (started) measureRuntimeReadiness(recovery = true) else null
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopXray()
            if (tunnel === established) tunnel = null
            runCatching { established.close() }
            return@withLifecycleLock
        }
        val failureReason = VpnReadinessPolicy.failureReason(started, readinessLatencyMs)
        if (failureReason != null) {
            Log.e(TAG, "Xray connection failed: $failureReason")
            connectionOrchestrator.stopXray()
            if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
                val health = repository.recordEndpointResult(
                    endpointCode = settings.profile.endpointCode,
                    success = false,
                    networkKind = activeEndpointNetworkKind,
                )
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = settings,
                    endpointCode = settings.profile.endpointCode,
                    networkKind = activeEndpointNetworkKind,
                    eventType = EndpointHealthEventType.CONNECT_FAIL,
                    success = false,
                    slow = false,
                    health = health,
                )
                failClosedAfterReplacementFailure(
                    repository = repository,
                    settings = settings,
                    reason = failureReason,
                )
            } else {
                failStart(repository, failureReason, category = "xray")
            }
            return@withLifecycleLock
        }

        finishTunnelConnected(
            repository = repository,
            settings = settings,
            preparationBaseline = preparationBaseline,
            generationId = generationId,
            resetConnectedAt = true,
            readinessLatencyMs = readinessLatencyMs,
            persistSettings = persistSettings,
            runtimeMode = runtimeMode,
        )
    }

    private fun refreshConnectedVpn(allowCachedFallback: Boolean) {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            restartTemporaryVpn()
            return
        }
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.CONNECTED) {
            startVpn(forceRefreshSession = true, allowCachedFallback = allowCachedFallback)
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            broadcastCurrentState()
            return
        }
        val repository = SettingsRepository(this)
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: repository.load()
        val previousNetworkKind = activeEndpointNetworkKind
        repository.recordAppLog("vpn", message = "service_refresh_start")
        launchConnectionTransition(repository) { generationId ->
            val prepared = prepareConnectionSettings(
                repository = repository,
                forceRefreshSession = true,
                allowCachedFallback = allowCachedFallback,
                destructiveOnFailure = false,
            ) ?: return@launchConnectionTransition
            val settings = normalizeInstalledPackages(prepared.candidateSettings)
            if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
            replaceXrayOnExistingTunnel(
                repository = repository,
                settings = settings,
                preparationBaseline = prepared.preparationBaseline,
                previousSettings = previousSettings,
                previousNetworkKind = previousNetworkKind,
                activeTunnel = activeTunnel,
                generationId = generationId,
                preparedMetadata = prepared,
            )
        }
    }

    private fun recoverConnectedVpn(failedSettings: StoredSettings) {
        val activeTunnel = tunnel ?: return
        if (currentState != VpnConnectionState.CONNECTED ||
            connectionOrchestrator.transitionSnapshot().active
        ) {
            return
        }
        val repository = SettingsRepository(this)
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: failedSettings
        val previousNetworkKind = activeEndpointNetworkKind
        val countryCode = previousSettings.userProfile.selectedCountryCode
        val failedLocationCode = previousSettings.userProfile.selectedServerCode
        val targets = ConnectedWatchdogPolicy.recoveryTargets(failedLocationCode)
        val incidentId = UUID.randomUUID().toString()
        launchConnectionTransition(repository) { generationId ->
            repository.recordAppLog(
                category = "vpn",
                message = "watchdog_cached_attempt",
                details = "incident_id=$incidentId; attempt=1; country=$countryCode; location=$failedLocationCode",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
            var cachedPrepareFailure: Throwable? = null
            val cachedPrepared = prepareConnectionSettings(
                repository = repository,
                forceRefreshSession = false,
                allowCachedFallback = true,
                destructiveOnFailure = false,
                incidentId = incidentId,
                onFailure = { cachedPrepareFailure = it },
            )
            if (cachedPrepared == null && cachedPrepareFailure?.let(BackendRetryPolicy::isTransient) == true) {
                scheduleTransientRecovery(repository, previousSettings, incidentId)
                return@launchConnectionTransition
            }
            if (cachedPrepared != null) {
                val recovered = replaceXrayOnExistingTunnel(
                    repository = repository,
                    settings = normalizeInstalledPackages(cachedPrepared.candidateSettings),
                    preparationBaseline = cachedPrepared.preparationBaseline,
                    previousSettings = previousSettings,
                    previousNetworkKind = previousNetworkKind,
                    activeTunnel = activeTunnel,
                    generationId = generationId,
                    preparedMetadata = cachedPrepared,
                    rollbackOnFailure = false,
                )
                if (recovered) {
                    completeWatchdogRecovery(
                        repository = repository,
                        prepared = cachedPrepared,
                        incidentId = incidentId,
                        countryCode = countryCode,
                        failedLocationCode = failedLocationCode,
                        attempt = 1,
                        cached = true,
                    )
                    return@launchConnectionTransition
                }
            }
            for ((index, target) in targets.withIndex()) {
                val attempt = index + 2
                repository.recordAppLog(
                    category = "vpn",
                    message = "watchdog_recovery_attempt",
                    details = "incident_id=$incidentId; attempt=$attempt; country=$countryCode; location=${target.locationCode.orEmpty()}; exclude=${target.excludeLocationCode.orEmpty()}",
                    serverCountry = notificationServerLabel,
                    connectionSuccess = false,
                )
                var prepareFailure: Throwable? = null
                val prepared = prepareConnectionSettings(
                    repository = repository,
                    forceRefreshSession = true,
                    allowCachedFallback = false,
                    destructiveOnFailure = false,
                    sessionSelection = VpnSessionSelection(
                        countryCode = countryCode,
                        locationCode = target.locationCode,
                        excludeLocationCode = target.excludeLocationCode,
                    ),
                    incidentId = incidentId,
                    onFailure = { prepareFailure = it },
                )
                if (prepared == null) {
                    if (prepareFailure?.let(BackendRetryPolicy::isTransient) == true) {
                        scheduleTransientRecovery(repository, previousSettings, incidentId)
                        return@launchConnectionTransition
                    }
                    continue
                }
                if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
                val recovered = replaceXrayOnExistingTunnel(
                    repository = repository,
                    settings = normalizeInstalledPackages(prepared.candidateSettings),
                    preparationBaseline = prepared.preparationBaseline,
                    previousSettings = previousSettings,
                    previousNetworkKind = previousNetworkKind,
                    activeTunnel = activeTunnel,
                    generationId = generationId,
                    preparedMetadata = prepared,
                    rollbackOnFailure = false,
                )
                if (recovered) {
                    completeWatchdogRecovery(
                        repository = repository,
                        prepared = prepared,
                        incidentId = incidentId,
                        countryCode = countryCode,
                        failedLocationCode = failedLocationCode,
                        attempt = attempt,
                        cached = false,
                    )
                    return@launchConnectionTransition
                }
            }
            repository.recordAppLog(
                category = "vpn",
                level = "error",
                message = "watchdog_recovery_exhausted",
                details = "incident_id=$incidentId; attempts=${targets.size + 1}; location=$failedLocationCode",
                errorType = "runtime_readiness_probe_failed",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
            enqueueVpnIncident(
                repository = repository,
                settings = previousSettings,
                incident = VpnIncidentReport(
                    id = incidentId,
                    reason = "runtime_readiness_probe_failed",
                    countryCode = countryCode,
                    locationCode = failedLocationCode,
                    recoveryAttempts = targets.size + 1,
                    outcome = "failed",
                    occurredAt = Instant.now().toString(),
                ),
            )
            connectionOrchestrator.withLifecycleLock {
                if (connectionOrchestrator.isCurrent(generationId) && tunnel === activeTunnel) {
                    failClosedAfterReplacementFailure(
                        repository = repository,
                        settings = previousSettings,
                        reason = "watchdog_recovery_attempts_exhausted",
                    )
                }
            }
        }
    }

    private suspend fun completeWatchdogRecovery(
        repository: SettingsRepository,
        prepared: PreparedVpnSession,
        incidentId: String,
        countryCode: String,
        failedLocationCode: String,
        attempt: Int,
        cached: Boolean,
    ) {
        resetTransientRecovery()
        repository.recordAppLog(
            category = "vpn",
            message = if (cached) "watchdog_cached_success" else "watchdog_recovery_success",
            details = "incident_id=$incidentId; attempt=$attempt; location=${prepared.candidateSettings.userProfile.selectedServerCode}",
            serverCountry = notificationServerLabel,
            connectionSuccess = true,
        )
        enqueueVpnIncident(
            repository = repository,
            settings = prepared.candidateSettings,
            incident = VpnIncidentReport(
                id = incidentId,
                reason = "runtime_readiness_probe_failed",
                countryCode = countryCode,
                locationCode = failedLocationCode,
                recoveryAttempts = attempt,
                outcome = "recovered",
                occurredAt = Instant.now().toString(),
            ),
        )
        uploadPendingVpnIncidents(repository, prepared.candidateSettings)
        if (cached) scheduleFreshSelectionPrepare()
    }

    private fun enqueueVpnIncident(
        repository: SettingsRepository,
        settings: StoredSettings,
        incident: VpnIncidentReport,
    ) {
        if (settings.advancedSettings.anonymousLogsEnabled) {
            repository.enqueueVpnIncident(incident)
        }
    }

    private suspend fun uploadPendingVpnIncidents(
        repository: SettingsRepository,
        settings: StoredSettings,
    ) {
        if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(settings.advancedSettings)) return
        val token = settings.backendAccessToken?.takeIf(String::isNotBlank) ?: return
        val coordinator = AppLogUploadCoordinator(
            exportLogs = repository::exportAppLogs,
            shouldUploadAutomatically = { false },
            markAutomaticallyUploaded = {},
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
        val context = AppLogUploadCoordinator.DeviceContext(
            token = token,
            deviceId = settings.backendDeviceId.takeIf(String::isNotBlank),
            deviceKey = settings.backendDeviceKey.takeIf(String::isNotBlank),
            deviceName = AndroidDeviceInfo.deviceName(),
        )
        for (incident in repository.loadPendingVpnIncidents()) {
            val uploaded = try {
                coordinator.uploadIncident(context, incident)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!uploaded) return
            repository.removePendingVpnIncident(incident.id)
        }
    }

    private fun applyRuntimeSettings() {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            broadcastCurrentState()
            return
        }
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.CONNECTED) {
            broadcastCurrentState()
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            scheduleRuntimeDomainReapply()
            return
        }
        val repository = SettingsRepository(this)
        val persisted = repository.load()
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: persisted
        val candidate = previousSettings.copy(
            advancedSettings = previousSettings.advancedSettings.copy(
                alwaysOnDomains = persisted.advancedSettings.alwaysOnDomains,
                bypassDomains = persisted.advancedSettings.bypassDomains,
            ),
        )
        if (candidate.advancedSettings == previousSettings.advancedSettings) {
            broadcastCurrentState()
            return
        }
        val previousNetworkKind = activeEndpointNetworkKind
        launchConnectionTransition(repository) { generationId ->
            replaceXrayOnExistingTunnel(
                repository = repository,
                settings = candidate,
                preparationBaseline = previousSettings,
                previousSettings = previousSettings,
                previousNetworkKind = previousNetworkKind,
                activeTunnel = activeTunnel,
                generationId = generationId,
                runtimeSettingsOnly = true,
            )
        }
    }

    private suspend fun replaceXrayOnExistingTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        previousSettings: StoredSettings,
        previousNetworkKind: EndpointRankingPolicy.NetworkKind,
        activeTunnel: TunHandle,
        generationId: Long,
        preparedMetadata: PreparedVpnSession? = null,
        runtimeSettingsOnly: Boolean = false,
        rollbackOnFailure: Boolean = true,
    ): Boolean = connectionOrchestrator.withLifecycleLock {
        if (!connectionOrchestrator.isCurrent(generationId) ||
            tunnel !== activeTunnel ||
            currentState != VpnConnectionState.CONNECTED
        ) {
            return@withLifecycleLock false
        }
        if (preparedMetadata != null) {
            acceptPreparedMetadata(repository, preparedMetadata)
        }
        if (!isXrayRuntimeAvailable()) {
            if (runtimeSettingsOnly) {
                restoreRuntimeDomainSettings(repository, previousSettings, settings)
            }
            handlePrepareFailure(
                repository = repository,
                reason = "runtime_unavailable",
                error = IllegalStateException("runtime_unavailable"),
                destructiveOnFailure = false,
            )
            return@withLifecycleLock false
        }

        connectionOrchestrator.pauseConnectedSidecars()
        val nextConfig = XrayConfigFactory.build(
            settings.profile,
            settings.advancedSettings,
        )

        connectionOrchestrator.stopXray()
        val nextStarted = connectionOrchestrator.startXray(nextConfig)
        val nextReadiness = if (nextStarted) measureRuntimeReadiness(recovery = true) else null
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopXray()
            return@withLifecycleLock false
        }
        if (nextStarted && VpnReadinessPolicy.accept(nextReadiness)) {
            if (!connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopXray()
                return@withLifecycleLock false
            }
            val connectedSettings = if (runtimeSettingsOnly) {
                var domainOutcome: VpnSettingsTransactionPolicy.RuntimeDomainOutcome? = null
                repository.updateSettings { persisted ->
                    VpnSettingsTransactionPolicy.acceptRuntimeDomains(
                        candidate = settings,
                        persisted = persisted,
                    ).also { domainOutcome = it }.persisted
                }
                checkNotNull(domainOutcome).also { outcome ->
                    activeSettings = outcome.runtime
                    if (outcome.requiresReapply) scheduleRuntimeDomainReapply()
                }.runtime
            } else {
                settings
            }
            finishTunnelConnected(
                repository,
                connectedSettings,
                preparationBaseline = preparationBaseline,
                generationId = generationId,
                resetConnectedAt = true,
                readinessLatencyMs = nextReadiness,
                persistSettings = !runtimeSettingsOnly,
                activationKind = ConnectedActivationKind.EXISTING_TUN,
            )
            return@withLifecycleLock true
        }

        val failedHealth = repository.recordEndpointResult(
            endpointCode = settings.profile.endpointCode,
            success = false,
            networkKind = activeEndpointNetworkKind,
        )
        recordEndpointHealthEvent(
            repository = repository,
            settings = settings,
            endpointCode = settings.profile.endpointCode,
            networkKind = activeEndpointNetworkKind,
            eventType = EndpointHealthEventType.CONNECT_FAIL,
            success = false,
            slow = false,
            health = failedHealth,
        )

        if (!rollbackOnFailure) {
            warmupController.clear()
            activeEndpointNetworkKind = previousNetworkKind
            notificationServerLabel = VpnServiceLogContext.serverLabel(previousSettings)
            return@withLifecycleLock false
        }

        activeEndpointNetworkKind = previousNetworkKind
        notificationServerLabel = VpnServiceLogContext.serverLabel(previousSettings)
        connectionOrchestrator.stopXray()
        val previousConfig = XrayConfigFactory.build(
            previousSettings.profile,
            previousSettings.advancedSettings,
        )
        val previousStarted = connectionOrchestrator.startXray(previousConfig)
        val previousReadiness = if (previousStarted) measureRuntimeReadiness(recovery = true) else null
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopXray()
            return@withLifecycleLock false
        }
        if (previousStarted && VpnReadinessPolicy.accept(previousReadiness)) {
            if (!connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopXray()
                return@withLifecycleLock false
            }
            warmupController.clear()
            if (runtimeSettingsOnly) {
                restoreRuntimeDomainSettings(repository, previousSettings, settings)
            } else {
                commitSettingsTransaction(
                    repository = repository,
                    preparationBaseline = preparationBaseline,
                    candidate = settings,
                    result = VpnSettingsTransactionPolicy.Result.RolledBack,
                )
            }
            val owner = checkNotNull(
                connectionOrchestrator.activateConnected(
                    generationId = generationId,
                    coreId = ++runtimeCoreSequence,
                    tunnel = checkNotNull(tunnel),
                    settings = previousSettings,
                    underlay = connectionOrchestrator.currentUnderlay(),
                    kind = ConnectedActivationKind.ROLLBACK,
                ),
            )
            statsCoordinator.recordInitialLatency(repository, previousSettings, owner, previousReadiness)
            broadcastState(VpnConnectionState.CONNECTED)
            startForeground(NOTIFICATION_ID, createActiveNotification())
            repository.recordAppLog(
                category = "vpn",
                level = "error",
                message = "service_refresh_rollback",
                details = "failed_endpoint=${settings.profile.endpointCode}; restored_endpoint=${previousSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
                endpointRating = endpointRatingSnapshot(repository, settings),
            )
            return@withLifecycleLock true
        }

        if (runtimeSettingsOnly) {
            restoreRuntimeDomainSettings(repository, previousSettings, settings)
        }
        val failureReason = checkNotNull(
            VpnReadinessPolicy.failureReason(previousStarted, previousReadiness),
        )
        failClosedAfterReplacementFailure(
            repository = repository,
            settings = settings,
            reason = failureReason,
        )
        false
    }

    private suspend fun finishTunnelConnected(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        generationId: Long,
        resetConnectedAt: Boolean,
        readinessLatencyMs: Long?,
        persistSettings: Boolean = true,
        activationKind: ConnectedActivationKind = ConnectedActivationKind.NEW_TUN,
        runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
    ) {
        currentRuntimeMode = runtimeMode
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) resetTransientRecovery()
        val connectedSettings = if (persistSettings) {
            commitSettingsTransaction(
                repository = repository,
                preparationBaseline = preparationBaseline,
                candidate = settings,
                result = VpnSettingsTransactionPolicy.Result.Accepted,
            )
        } else {
            activeSettings = settings
            settings
        }
        notificationServerLabel = VpnServiceLogContext.serverLabel(connectedSettings)
        if (resetConnectedAt || connectedAtMillis == null) {
            connectedAtMillis = System.currentTimeMillis()
        }
        val owner = checkNotNull(
            connectionOrchestrator.activateConnected(
                generationId = generationId,
                coreId = ++runtimeCoreSequence,
                tunnel = checkNotNull(tunnel),
                settings = connectedSettings,
                underlay = connectionOrchestrator.currentUnderlay(),
                kind = activationKind,
            ),
        )
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            statsCoordinator.recordInitialLatency(repository, connectedSettings, owner, readinessLatencyMs)
        }
        broadcastState(VpnConnectionState.CONNECTED)
        startForeground(NOTIFICATION_ID, createActiveNotification())
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            val health = repository.recordEndpointResult(
                endpointCode = connectedSettings.profile.endpointCode,
                success = true,
                latencyMs = readinessLatencyMs,
                networkKind = activeEndpointNetworkKind,
            )
            recordEndpointHealthEvent(
                repository = repository,
                settings = connectedSettings,
                endpointCode = connectedSettings.profile.endpointCode,
                networkKind = activeEndpointNetworkKind,
                eventType = EndpointHealthEventType.CONNECT_SUCCESS,
                success = true,
                slow = false,
                health = health,
            )
        }
        repository.recordAppLog(
            category = "vpn",
            message = if (runtimeMode == VpnRuntimeMode.AUTH_TEMP) {
                "temporary_vpn_connect_success"
            } else {
                "service_connect_success"
            },
            details = "server=${connectedSettings.userProfile.selectedServerCode}",
            serverCountry = notificationServerLabel,
            connectionSuccess = true,
            endpointRating = endpointRatingSnapshot(repository, connectedSettings),
        )
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            backgroundScope.launch {
                uploadPendingVpnIncidents(repository, connectedSettings)
            }
        }
    }

    private suspend fun recordStartupTcpPrecheck(
        repository: SettingsRepository,
        selection: EndpointSelector.EndpointSelectionResult,
        settings: StoredSettings,
    ) {
        val failedCodes = selection.precheckFailedEndpointCodes
        if (failedCodes.isEmpty()) return
        if (selection.precheckShouldPenalizeFailures) {
            failedCodes.forEach { endpointCode ->
                val health = repository.recordEndpointResult(
                    endpointCode = endpointCode,
                    success = false,
                    networkKind = selection.networkKind,
                )
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = settings,
                    endpointCode = endpointCode,
                    networkKind = selection.networkKind,
                    eventType = EndpointHealthEventType.STARTUP_PRECHECK_FAIL,
                    success = false,
                    slow = false,
                    health = health,
                )
            }
        } else {
            failedCodes.forEach { endpointCode ->
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = settings,
                    endpointCode = endpointCode,
                    networkKind = selection.networkKind,
                    eventType = EndpointHealthEventType.STARTUP_PRECHECK_FAIL,
                    success = false,
                    slow = false,
                    health = repository.loadEndpointHealth(selection.networkKind)[endpointCode],
                )
            }
        }
        val serverLabel = VpnServiceLogContext.serverLabel(settings)
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = XrayRuntimeIssue.PROXY_TCP_TIMEOUT.logMessage,
            details = "source=startup_precheck; failed_count=${failedCodes.size}; selected=${selection.endpointCode}; penalty=${selection.precheckShouldPenalizeFailures}",
            errorType = XrayRuntimeIssue.PROXY_TCP_TIMEOUT.logMessage,
            serverCountry = serverLabel,
            connectionSuccess = false,
            endpointRating = repository.endpointRatingSnapshot(failedCodes + selection.endpointCode, selection.networkKind),
        )
        repository.recordAppLog(
            category = "vpn",
            level = if (selection.precheckShouldPenalizeFailures) "info" else "warning",
            message = if (selection.precheckShouldPenalizeFailures) {
                "endpoint_startup_tcp_precheck_fallback"
            } else {
                "endpoint_startup_tcp_precheck_unreachable"
            },
            details = "failed_tcp=${failedCodes.joinToString(",")}; selected=${selection.endpointCode}; penalty=${selection.precheckShouldPenalizeFailures}",
            serverCountry = serverLabel,
            endpointRating = repository.endpointRatingSnapshot(failedCodes + selection.endpointCode, selection.networkKind),
        )
    }

    private suspend fun recordEndpointHealthEvent(
        repository: SettingsRepository,
        settings: StoredSettings,
        endpointCode: String,
        networkKind: EndpointRankingPolicy.NetworkKind,
        eventType: EndpointHealthEventType,
        success: Boolean,
        slow: Boolean,
        health: EndpointHealth?,
    ) {
        val latestSettings = repository.load().let { current ->
            if (current.backendAccessToken.isNullOrBlank() && !settings.backendAccessToken.isNullOrBlank()) {
                current.copy(
                    backendAccessToken = settings.backendAccessToken,
                    backendRefreshToken = settings.backendRefreshToken,
                    backendAccessTokenExpiresInSeconds = settings.backendAccessTokenExpiresInSeconds,
                    backendRefreshExpiresAt = settings.backendRefreshExpiresAt,
                )
            } else {
                current
            }
        }
        EndpointHealthEventReporter(repository).recordEvent(
            settings = latestSettings,
            event = EndpointHealthEvent(
                endpointCode = endpointCode,
                networkKind = networkKind,
                eventType = eventType,
                success = success,
                slow = slow,
                scoreBucket = EndpointHealthEvents.scoreBucket(health),
            ),
        )
    }

    private fun launchEndpointHealthHeartbeat(
        owner: RuntimeOwner,
        settings: StoredSettings,
    ): CancelableTask {
        val job = backgroundScope.launch {
            if (currentState != VpnConnectionState.CONNECTED ||
                !endpointHealthController.accepts(owner)
            ) {
                return@launch
            }
            val repository = SettingsRepository(this@AppVpnService)
            val latestSettings = repository.load()
            if (watchdogController.hasFreshXrayEvidence() &&
                endpointHealthController.accepts(owner)
            ) {
                EndpointHealthEventReporter(repository).recordHeartbeatIfDue(
                    settings = latestSettings,
                    endpointCode = settings.profile.endpointCode,
                    networkKind = activeEndpointNetworkKind,
                    health = repository.loadEndpointHealth(activeEndpointNetworkKind)[settings.profile.endpointCode],
                )
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun startConnectedWatchdog(settings: StoredSettings, owner: RuntimeOwner) {
        stopConnectedWatchdog()
        lockdownRecoveryActive = false
        val activeTunnel = tunnel ?: return
        watchdogController.start(
            owner = ConnectedWatchdogPolicy.Owner(
                generationId = owner.generationId,
                tunnelIdentity = System.identityHashCode(activeTunnel),
                coreIdentity = owner.coreId,
            ),
            settings = settings,
            initialXrayEvidence = true,
        )
    }

    private fun stopConnectedWatchdog() {
        watchdogController.stop(null)
        delayedTaskScheduler.cancel(lockdownRecoveryOwner)
    }

    private fun launchWatchdogProbe(
        owner: ConnectedWatchdogPolicy.Owner,
        settings: StoredSettings,
    ): CancelableTask? {
        if (currentState != VpnConnectionState.CONNECTED ||
            tunnel == null ||
            connectionOrchestrator.transitionSnapshot().active ||
            watchdogController.currentSettings()?.profile?.endpointCode != settings.profile.endpointCode ||
            !watchdogController.accepts(owner)
        ) {
            return null
        }
        val job = backgroundScope.launch {
            val latencyMs = measureRuntimeReadiness(recovery = false)
            if (!currentCoroutineContext().isActive) return@launch
            val outcome = watchdogController.completeProbe(owner, latencyMs)
            if (!outcome.accepted) return@launch
            connectionOrchestrator.withLifecycleLock {
                if (!currentCoroutineContext().isActive ||
                    !connectionOrchestrator.isCurrent(owner.generationId) ||
                    tunnel?.let(System::identityHashCode) != owner.tunnelIdentity ||
                    !watchdogController.accepts(owner)
                ) {
                    return@withLifecycleLock
                }
                if (outcome.healthy) {
                    latencyMs?.let { latency ->
                        SettingsRepository(this@AppVpnService).recordEndpointResult(
                            endpointCode = settings.profile.endpointCode,
                            success = true,
                            latencyMs = latency,
                            networkKind = activeEndpointNetworkKind,
                        )
                    }
                }
                when (val action = outcome.action) {
                    WatchdogAction.None -> Unit
                    WatchdogAction.Refresh -> recoverConnectedVpn(settings)
                    WatchdogAction.ReleaseOutsideLockdown -> {
                        connectionOrchestrator.invalidate()
                        connectionOrchestrator.stopXray()
                        failClosedAfterReplacementFailure(
                            repository = SettingsRepository(this@AppVpnService),
                            settings = settings,
                            reason = ConnectedWatchdogPolicy.failureReason(isLockdown = false),
                        )
                    }
                    is WatchdogAction.EnterLockdownFailure -> {
                        val repository = SettingsRepository(this@AppVpnService)
                        connectionOrchestrator.stopXray()
                        watchdogController.stop(owner)
                        enterTruthfulLockdownFailure(
                            repository = repository,
                            reason = ConnectedWatchdogPolicy.failureReason(isLockdown = true),
                            retryDelayMillis = action.retryDelayMillis,
                        )
                    }
                }
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun isActiveLockdownRecovery(): Boolean {
        return lockdownRecoveryActive &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isLockdownEnabled
    }

    private fun enterTruthfulLockdownFailure(
        repository: SettingsRepository,
        reason: String,
        retryDelayMillis: Long = watchdogController.lockdownRetryDelayMillis(),
    ) {
        lockdownRecoveryActive = true
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = reason,
            details = "android_lockdown_blocks_direct_traffic=true",
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        broadcastState(VpnConnectionState.FAILED, reason)
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "VPN недоступен",
                text = "Android lockdown блокирует трафик. Повторное подключение будет выполнено позже.",
                showActions = true,
            ),
        )
        delayedTaskScheduler.schedule(
            owner = lockdownRecoveryOwner,
            delayMillis = retryDelayMillis,
            task = lockdownRecoveryTask,
        )
    }

    private suspend fun measureRuntimeReadiness(recovery: Boolean): Long? {
        val repository = SettingsRepository(this)
        val connectedTargets = VpnProbePlanPolicy.connectedTargets(recovery)
        val deadline = SystemClock.elapsedRealtime() + VpnProbePlanPolicy.TOTAL_TIMEOUT_MILLIS
        for (target in connectedTargets) {
            currentCoroutineContext().ensureActive()
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) break
            val startedAt = SystemClock.elapsedRealtime()
            val result = connectionOrchestrator.measureDelay(
                targetUrl = target.url,
                timeoutMillis = remainingMillis.coerceAtMost(
                    VpnProbePlanPolicy.PER_TARGET_TIMEOUT_MILLIS,
                ),
            )
            currentCoroutineContext().ensureActive()
            recordDiagnostic(
                repository = repository,
                message = "readiness_probe",
                details = readinessDiagnosticDetails(
                    target = target,
                    result = result,
                    recovery = recovery,
                    fallback = false,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                ),
                level = if (VpnReadinessPolicy.accept(result.delayMs)) "info" else "error",
            )
            if (VpnReadinessPolicy.accept(result.delayMs)) return result.delayMs
        }
        return null
    }

    private fun readinessDiagnosticDetails(
        target: VpnProbePlanPolicy.ProbeTarget,
        result: XrayProbeResult,
        recovery: Boolean,
        fallback: Boolean,
        elapsedMs: Long,
    ): String = buildString {
        append("target=").append(target.key)
        append("; recovery=").append(recovery)
        append("; fallback=").append(fallback)
        append("; delay_ms=").append(result.delayMs ?: -1L)
        append("; elapsed_ms=").append(elapsedMs)
        append("; issue=").append(result.issue?.logMessage.orEmpty())
        append("; state=").append(currentState.name.lowercase(Locale.ROOT))
        append("; endpoint=").append(activeSettings?.profile?.endpointCode.orEmpty())
        append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
        append("; underlay=").append(underlyingNetworkSource.currentSnapshot()?.details.orEmpty())
    }

    private fun launchBackgroundEndpointWarmup(
        repository: SettingsRepository,
        session: BackendVpnSession,
        settings: StoredSettings,
        connectedAtSnapshot: Long?,
    ): CancelableTask? {
        if (settings.advancedSettings.endpointSelectionMode != EndpointSelectionMode.AUTO) return null
        val endpointCode = settings.profile.endpointCode
        val job = backgroundScope.launch {
            if (currentState == VpnConnectionState.DISCONNECTED ||
                currentState == VpnConnectionState.FAILED ||
                connectedAtMillis != connectedAtSnapshot ||
                settings.profile.endpointCode != endpointCode
            ) {
                return@launch
            }
            EndpointProbeRunner(this@AppVpnService, repository).probeAutoCandidates(
                session = session,
                settings = settings,
                networkKind = activeEndpointNetworkKind,
            )
        }
        return CancelableTask(job::cancel)
    }

    private fun startNetworkChangeMonitor(initialKind: EndpointRankingPolicy.NetworkKind) {
        stopNetworkChangeMonitor()
        activeEndpointNetworkKind = initialKind
        lastUnderlyingAvailability = null
        networkMonitor.start(::handleUnderlyingNetworkObservation)
    }

    private fun handleUnderlyingNetworkObservation(
        observation: UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>,
    ) {
        val previousAvailability = lastUnderlyingAvailability
        lastUnderlyingAvailability = observation.availability
        if (previousAvailability != observation.availability &&
            observation.availability != UnderlyingNetworkAvailability.Validated
        ) {
            SettingsRepository(this).recordAppLog(
                category = "vpn",
                message = if (observation.availability == UnderlyingNetworkAvailability.None) {
                    "underlay_none"
                } else {
                    "underlay_waiting_validation"
                },
                details = observation.candidate?.details,
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
        }
        if (observation.availability == UnderlyingNetworkAvailability.Validated) {
            observation.candidate?.let(::handleUnderlyingNetworkSnapshot)
        }
    }

    private fun handleUnderlyingNetworkSnapshot(nextUnderlyingSnapshot: UnderlyingNetworkSnapshot) {
        if (currentState != VpnConnectionState.CONNECTED) return
        val previousKind = activeEndpointNetworkKind
        val nextKind = nextUnderlyingSnapshot.kind
        val previousSignature = activeUnderlyingNetworkSignature
        val plan = VpnHandoverPolicy.plan(
            hasTunnel = tunnel != null,
            activeSignature = previousSignature,
            nextSignature = nextUnderlyingSnapshot.signature,
        )
        if (plan.action == VpnHandoverPolicy.Action.NoAction) return
        activeEndpointNetworkKind = nextKind
        SettingsRepository(this).recordAppLog(
            category = "vpn",
            level = "info",
            message = "endpoint_network_changed",
            details = buildString {
                append("from=${previousKind.name.lowercase(Locale.ROOT)}; ")
                append("to=${nextKind.name.lowercase(Locale.ROOT)}")
                append("; underlying_changed=true")
                append("; vpn_metered=${nextUnderlyingSnapshot.vpnShouldBeMetered}")
                append("; underlay=${nextUnderlyingSnapshot.details}")
            },
            serverCountry = notificationServerLabel,
        )
        when (plan.action) {
            VpnHandoverPolicy.Action.NoAction -> Unit
            VpnHandoverPolicy.Action.FreshStart -> {
                if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                    restartTemporaryVpn()
                } else {
                    startVpn(
                        forceRefreshSession = plan.forceRefreshSession,
                        allowCachedFallback = plan.allowCachedFallback,
                    )
                }
            }
            VpnHandoverPolicy.Action.RestartTunnel -> {
                if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
                    if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                        restartTemporaryVpn()
                    } else {
                        restartVpn(
                            forceRefreshSession = plan.forceRefreshSession,
                            allowCachedFallback = plan.allowCachedFallback,
                        )
                    }
                }
            }
        }
    }

    private fun stopNetworkChangeMonitor() {
        networkMonitor.stop()
        lastUnderlyingAvailability = null
    }

    private fun prepareFailureReason(error: Throwable): String {
        return when {
            error is BackendException && error.message.contains("traffic", ignoreCase = true) -> "traffic_limit"
            error is BackendException && error.message.contains("device", ignoreCase = true) -> "device_limit"
            error.message?.contains("auth", ignoreCase = true) == true -> "auth_required"
            else -> "profile_prepare_error"
        }
    }

    private fun incidentDetails(incidentId: String?, details: String?): String? =
        when {
            incidentId.isNullOrBlank() -> details
            details.isNullOrBlank() -> "incident_id=$incidentId"
            else -> "incident_id=$incidentId; $details"
        }

    private fun failStart(
        repository: SettingsRepository,
        reason: String,
        error: Throwable? = null,
        category: String = "vpn",
        endpointRating: String? = null,
    ) {
        cancelDelayedVpnCallbacks()
        recordDiagnostic(
            repository = repository,
            message = "connection_failed",
            details = buildString {
                append("reason=").append(reason)
                append("; error=").append(error?.javaClass?.name.orEmpty())
                append(":").append(error?.message.orEmpty())
                append("; runtime_mode=").append(currentRuntimeMode.name.lowercase(Locale.ROOT))
                append("; state=").append(currentState.name.lowercase(Locale.ROOT))
                append("; endpoint=").append(activeSettings?.profile?.endpointCode.orEmpty())
                append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
                append("; underlay=").append(underlyingNetworkSource.currentSnapshot()?.details.orEmpty())
            },
            level = "error",
        )
        repository.recordAppLog(
            category = category,
            level = "error",
            message = reason,
            details = error?.message,
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
            endpointRating = endpointRating,
        )
        warmupController.clear()
        connectionOrchestrator.releaseResourcesWhileOwned(VpnConnectionState.FAILED)
        connectedAtMillis = null
        repository.clearVpnRuntimeState()
        broadcastState(VpnConnectionState.FAILED, reason)
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            destroyCleanupScope.launch {
                val pending = try {
                    temporaryVpnCoordinator.stageStoredLeaseForRevoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stageError: Exception) {
                    repository.recordAppLog(
                        category = "vpn",
                        level = "error",
                        message = "temporary_vpn_revoke_stage_failed",
                        details = stageError.message,
                        errorType = "temporary_vpn_revoke_stage_failed",
                    )
                    return@launch
                }
                notificationHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                if (pending != null) {
                    try {
                        temporaryVpnCoordinator.retryPendingRevoke()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Unit
                    }
                }
            }
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordDiagnostic(
        repository: SettingsRepository,
        message: String,
        details: String,
        level: String = "info",
    ) {
        repository.recordAppLog(
            category = "diagnostic",
            level = level,
            message = message,
            details = details,
            serverCountry = notificationServerLabel,
        )
    }

    private fun failClosedAfterReplacementFailure(
        repository: SettingsRepository,
        settings: StoredSettings,
        reason: String,
    ) {
        val isLockdown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled
        when (ConnectedWatchdogPolicy.exhaustedRecoveryDecision(isLockdown)) {
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReportLockdownBlockedAndRetry -> {
                connectionOrchestrator.stopXray()
                enterTruthfulLockdownFailure(repository, reason)
            }
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReleaseTunnel -> {
                repository.recordAppLog(
                    category = "xray",
                    level = "error",
                    message = "transient_tunnel_released",
                    details = reason,
                    errorType = reason,
                    serverCountry = notificationServerLabel,
                    connectionSuccess = false,
                    endpointRating = endpointRatingSnapshot(repository, settings),
                )
                connectionOrchestrator.releaseResourcesWhileOwned(VpnConnectionState.FAILED)
                connectedAtMillis = null
                broadcastState(VpnConnectionState.FAILED, reason)
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "VPN переподключается",
                        text = "Прямой интернет восстановлен. VPN ожидает доступную сеть.",
                        showActions = true,
                    ),
                )
                startNetworkChangeMonitor(activeEndpointNetworkKind)
                scheduleTransientRecovery(repository, settings, incidentId = null)
            }
        }
    }

    private fun revokeTemporaryLeaseWithoutStoppingAccount(startId: Int) {
        backgroundScope.launch {
            try {
                temporaryVpnCoordinator.revokeStoredLease()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            notificationHandler.post {
                val transitionActive = connectionOrchestrator.transitionSnapshot().active
                if (tunnel == null && !transitionActive) {
                    stopSelfResult(startId)
                }
            }
        }
    }

    private fun stopVpn(
        startId: Int,
        revokeTemporaryLease: Boolean = currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP,
    ) {
        val repository = SettingsRepository(this)
        val stoppedRuntimeMode = currentRuntimeMode
        repository.recordAppLog("vpn", message = "service_disconnect")
        pendingStartOptions = null
        cancelDelayedVpnCallbacks()
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.STOP,
            awaitPrevious = true,
            onOwnedCompletion = { generationId ->
                if (revokeTemporaryLease) {
                    backgroundScope.launch { temporaryVpnCoordinator.retryPendingRevoke() }
                }
                notificationHandler.post {
                    // START/STOP delivery and pending consumption share the main
                    // thread. A newer command or destroy invalidates this cleanup.
                    if (!connectionOrchestrator.isCurrent(generationId)) return@post
                    val pending = pendingStartOptions.also { pendingStartOptions = null }
                    if (pending != null) {
                        if (pending.runtimeMode == VpnRuntimeMode.AUTH_TEMP) {
                            startTemporaryVpn()
                        } else {
                            startVpn(
                                forceRefreshSession = pending.forceRefreshSession,
                                allowCachedFallback = pending.allowCachedFallback,
                            )
                        }
                    } else {
                        stopSelfResult(startId)
                    }
                }
            },
            onError = { _, error -> throw error },
        ) {
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = VpnConnectionState.DISCONNECTED,
                    stopService = false,
                )
            }
            if (revokeTemporaryLease) {
                temporaryVpnCoordinator.stageStoredLeaseForRevoke()
                activeTemporaryLease = null
            }
            if (stoppedRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                currentRuntimeMode = VpnRuntimeMode.ACCOUNT
            }
        }
    }

    private fun releaseVpnResources(
        repository: SettingsRepository,
        finalState: VpnConnectionState?,
        stopService: Boolean,
        removeForeground: Boolean = true,
    ) {
        cancelDelayedVpnCallbacks()
        warmupController.clear()
        connectionOrchestrator.releaseResourcesWhileOwned(finalState)
        connectedAtMillis = null
        repository.clearVpnRuntimeState()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        finalState?.let(::broadcastState)
        if (stopService) stopSelf()
    }

    private fun manualRestartVpn() {
        SettingsRepository(this).recordAppLog("vpn", message = "service_manual_restart")
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            restartTemporaryVpn()
            return
        }
        restartVpn(forceRefreshSession = true, allowCachedFallback = true)
    }

    private fun reconnectFailedClosedVpn(allowCachedFallback: Boolean) {
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.FAILED) {
            restartVpn(forceRefreshSession = false, allowCachedFallback = allowCachedFallback)
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            broadcastCurrentState()
            return
        }
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "service_failed_closed_reconnect_start")
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "VPN reconnecting",
                text = "Preparing a fresh VPN engine while traffic remains blocked.",
                showActions = true,
            ),
        )
        launchConnectionTransition(repository) { generationId ->
            val prepared = prepareConnectionSettings(
                repository = repository,
                forceRefreshSession = true,
                allowCachedFallback = allowCachedFallback,
                destructiveOnFailure = false,
            ) ?: return@launchConnectionTransition
            val settings = normalizeInstalledPackages(prepared.candidateSettings)
            if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
            startXrayOnFailedClosedTunnel(
                repository = repository,
                settings = settings,
                preparationBaseline = prepared.preparationBaseline,
                activeTunnel = activeTunnel,
                generationId = generationId,
                preparedMetadata = prepared,
            )
        }
    }

    private suspend fun startXrayOnFailedClosedTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        activeTunnel: TunHandle,
        generationId: Long,
        preparedMetadata: PreparedVpnSession,
    ) = connectionOrchestrator.withLifecycleLock {
        if (!connectionOrchestrator.isCurrent(generationId) ||
            tunnel !== activeTunnel ||
            currentState != VpnConnectionState.FAILED
        ) {
            return@withLifecycleLock
        }
        acceptPreparedMetadata(repository, preparedMetadata)
        if (!isXrayRuntimeAvailable()) {
            if (ConnectedWatchdogPolicy.retryFailureDecision(
                    isLockdown = isActiveLockdownRecovery(),
                    stage = ConnectedWatchdogPolicy.RetryFailureStage.XrayOrReadiness,
                ) == ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule
            ) {
                connectionOrchestrator.stopXray()
                enterTruthfulLockdownFailure(repository, "runtime_unavailable")
                return@withLifecycleLock
            }
            handlePrepareFailure(
                repository = repository,
                reason = "runtime_unavailable",
                error = IllegalStateException("runtime_unavailable"),
                destructiveOnFailure = false,
            )
            failClosedAfterReplacementFailure(
                repository = repository,
                settings = settings,
                reason = "runtime_unavailable",
            )
            return@withLifecycleLock
        }

        val config = XrayConfigFactory.build(
            settings.profile,
            settings.advancedSettings,
        )
        val started = connectionOrchestrator.startXray(config)
        val readinessLatencyMs = if (started) measureRuntimeReadiness(recovery = true) else null
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopXray()
            return@withLifecycleLock
        }
        if (started && VpnReadinessPolicy.accept(readinessLatencyMs)) {
            if (!connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopXray()
                return@withLifecycleLock
            }
            finishTunnelConnected(
                repository,
                settings,
                preparationBaseline = preparationBaseline,
                generationId = generationId,
                resetConnectedAt = true,
                readinessLatencyMs = readinessLatencyMs,
                activationKind = ConnectedActivationKind.FAILED_CLOSED_RECOVERY,
            )
            return@withLifecycleLock
        }
        val failureReason = checkNotNull(
            VpnReadinessPolicy.failureReason(started, readinessLatencyMs),
        )

        val failedHealth = repository.recordEndpointResult(
            endpointCode = settings.profile.endpointCode,
            success = false,
            networkKind = activeEndpointNetworkKind,
        )
        recordEndpointHealthEvent(
            repository = repository,
            settings = settings,
            endpointCode = settings.profile.endpointCode,
            networkKind = activeEndpointNetworkKind,
            eventType = EndpointHealthEventType.CONNECT_FAIL,
            success = false,
            slow = false,
            health = failedHealth,
        )
        if (ConnectedWatchdogPolicy.retryFailureDecision(
                isLockdown = isActiveLockdownRecovery(),
                stage = ConnectedWatchdogPolicy.RetryFailureStage.XrayOrReadiness,
            ) == ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule
        ) {
            connectionOrchestrator.stopXray()
            enterTruthfulLockdownFailure(
                repository,
                failureReason,
            )
            return@withLifecycleLock
        }
        failClosedAfterReplacementFailure(
            repository = repository,
            settings = settings,
            reason = failureReason,
        )
    }

    private fun restartVpn(
        forceRefreshSession: Boolean,
        allowCachedFallback: Boolean,
    ) {
        val repository = SettingsRepository(this)
        pendingStartOptions = null
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.RESTART,
            awaitPrevious = true,
            onError = { _, error -> throw error },
        ) { generationId ->
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = null,
                    stopService = false,
                    removeForeground = false,
                )
            }
            if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
                return@launchTransition
            }
            announceConnecting()
            repository.recordAppLog("vpn", message = "service_restart_start")
            performFreshStart(
                repository = repository,
                forceRefreshSession = forceRefreshSession,
                allowCachedFallback = allowCachedFallback,
                generationId = generationId,
            )
        }
    }

    private fun restartTemporaryVpn() {
        val repository = SettingsRepository(this)
        pendingStartOptions = null
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.RESTART,
            awaitPrevious = true,
            onError = { _, error -> throw error },
        ) { generationId ->
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = null,
                    stopService = false,
                    removeForeground = false,
                )
            }
            if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
                return@launchTransition
            }
            announceConnecting(VpnRuntimeMode.AUTH_TEMP)
            repository.recordAppLog("vpn", message = "temporary_vpn_restart_start")
            performTemporaryStart(repository, generationId)
        }
    }

    private fun createActiveNotification(): Notification {
        return notificationFactory.createActive(notificationServerLabel)
    }

    private fun updateActiveNotification() {
        notificationFactory.updateActive(NOTIFICATION_ID, notificationServerLabel)
    }

    private fun startNotificationTicker() {
        notificationController.start()
    }

    private fun stopNotificationTicker() {
        notificationController.stop()
    }

    private fun pollAppNotifications(): CancelableTask? {
        if (currentState != VpnConnectionState.CONNECTED) return null
        val job = backgroundScope.launch {
            val repository = SettingsRepository(this@AppVpnService)
            val settings = repository.load()
            val token = settings.backendAccessToken?.takeIf { it.isNotBlank() }
            if (token == null || !settings.isAuthenticated) return@launch
            try {
                AppNotificationPoller.pollOnce(
                    context = this@AppVpnService,
                    repository = repository,
                    backendApi = backendApi,
                    token = token,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error !is BackendException || error.statusCode != 401) return@launch
                val refreshedToken = try {
                    AuthTokenRefresher(
                        repository,
                        backendApi,
                        onRevocationPending = {
                            PendingLogoutRevocationWorker.enqueue(applicationContext)
                        },
                    ).refreshStoredTokens()?.accessToken
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } ?: return@launch
                try {
                    AppNotificationPoller.pollOnce(
                        context = this@AppVpnService,
                        repository = repository,
                        backendApi = backendApi,
                        token = refreshedToken,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    Unit
                }
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun startConnectedSidecars(owner: RuntimeOwner, settings: StoredSettings) {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            startNotificationTicker()
            startNetworkChangeMonitor(activeEndpointNetworkKind)
            scheduleTemporaryLeaseExpiry()
            return
        }
        val repository = SettingsRepository(this)
        statsCoordinator.start(repository, settings, owner)
        DeviceTrafficMonitor.start()
        startNotificationTicker()
        startNetworkChangeMonitor(activeEndpointNetworkKind)
        endpointHealthController.start(owner, settings)
        startConnectedWatchdog(settings, owner)
        warmupController.start(owner) { session ->
            launchBackgroundEndpointWarmup(
                repository = repository,
                session = session,
                settings = settings,
                connectedAtSnapshot = connectedAtMillis,
            )
        }
    }

    private fun stopConnectedSidecars(owner: RuntimeOwner?) {
        delayedTaskScheduler.cancel(temporaryLeaseExpiryOwner)
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            stopNetworkChangeMonitor()
            stopNotificationTicker()
            return
        }
        if (!statsCoordinator.acceptsStop(owner)) return
        statsCoordinator.stop(owner)
        stopNetworkChangeMonitor()
        endpointHealthController.stop(owner)
        stopConnectedWatchdog()
        stopNotificationTicker()
        DeviceTrafficMonitor.stop()
        warmupController.stop(owner)
    }

    private fun scheduleTemporaryLeaseExpiry() {
        val lease = activeTemporaryLease ?: return
        val delayMillis = (lease.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        delayedTaskScheduler.schedule(
            owner = temporaryLeaseExpiryOwner,
            delayMillis = delayMillis,
            task = {
                if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                    startService(stopIntent(this))
                }
            },
        )
    }

    private fun endpointRatingSnapshot(
        repository: SettingsRepository,
        settings: StoredSettings,
    ): String {
        val codes = settings.endpointOptions.map { it.code }.ifEmpty { listOf(settings.profile.endpointCode) }
        return repository.endpointRatingSnapshot(codes, activeEndpointNetworkKind)
    }

    private fun createNotification(
        title: String,
        text: String,
        showActions: Boolean,
    ): Notification = notificationFactory.create(title, text, showActions)

    private fun broadcastCurrentState() {
        if (tunnel == null && currentState != VpnConnectionState.CONNECTING) {
            connectedAtMillis = null
            statsCoordinator.reset()
            SettingsRepository(this).clearVpnRuntimeState()
            broadcastState(VpnConnectionState.DISCONNECTED)
            stopSelf()
            return
        }
        broadcastState(currentState)
    }

    private fun broadcastState(
        state: VpnConnectionState,
        reason: String? = null,
        latencyLocationCode: String? = null,
        latencyMs: Int? = null,
    ) {
        val publicState = if (state == VpnConnectionState.CONNECTED && tunnel == null) {
            VpnConnectionState.DISCONNECTED
        } else {
            state
        }
        val publicConnectedAtMillis = connectedAtMillis.takeIf { publicState == VpnConnectionState.CONNECTED }
        currentState = publicState
        updateLiveRuntimeState(publicState, publicConnectedAtMillis, currentRuntimeMode)
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, publicState.name)
            .putExtra(EXTRA_REASON, reason.orEmpty())
            .putExtra(EXTRA_CONNECTED_AT, publicConnectedAtMillis ?: 0L)
            .putExtra(EXTRA_RUNTIME_MODE, currentRuntimeMode.name)
        latencyLocationCode?.takeIf { it.isNotBlank() }?.let { code ->
            intent.putExtra(EXTRA_LATENCY_LOCATION_CODE, code)
        }
        latencyMs?.let { value ->
            intent.putExtra(EXTRA_LATENCY_MS, value)
        }
        sendBroadcast(
            intent,
        )
        NokiQuickSettingsTileService.requestTileRefresh(this)
    }

    data class LiveRuntimeState(
        val state: VpnConnectionState,
        val connectedAtMillis: Long?,
        val runtimeMode: VpnRuntimeMode,
    )

    companion object {
        private const val TAG = "NokiVpnService"
        private const val ACTION_START = "com.noki.vpn.START"
        private const val ACTION_START_TEMPORARY = "com.noki.vpn.START_TEMPORARY"
        private const val ACTION_STOP = "com.noki.vpn.STOP"
        private const val ACTION_STOP_AND_REVOKE_TEMPORARY =
            "com.noki.vpn.STOP_AND_REVOKE_AUTH_TEMP"
        private const val ACTION_RESTART = "com.noki.vpn.RESTART"
        private const val ACTION_QUERY_STATE = "com.noki.vpn.QUERY_STATE"
        private const val ACTION_APPLY_SETTINGS = "com.noki.vpn.APPLY_SETTINGS"
        const val ACTION_STATE_CHANGED = "com.noki.vpn.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_REASON = "reason"
        const val EXTRA_CONNECTED_AT = "connected_at"
        const val EXTRA_RUNTIME_MODE = "runtime_mode"
        const val EXTRA_LATENCY_LOCATION_CODE = "latency_location_code"
        const val EXTRA_LATENCY_MS = "latency_ms"
        private const val EXTRA_REFRESH_SESSION = "refresh_session"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_SPEED_UPDATE_INTERVAL_MS = 1_000L
        private const val DATA_PATH_EVIDENCE_FRESH_MS = 5 * 60_000L
        private const val APP_NOTIFICATION_POLL_INTERVAL_MS = 60_000L
        private const val STARTUP_TCP_PRECHECK_TIMEOUT_MS = 800
        private const val BACKGROUND_ENDPOINT_WARMUP_DELAY_MS = 18_000L
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 2_500L
        private const val RUNTIME_SETTINGS_RETRY_DELAY_MS = 500L
        private const val FRESH_SESSION_RETRY_COUNT = 2
        private const val FRESH_SESSION_DEADLINE_MS = 20_000L
        @Volatile
        private var liveRuntimeState = LiveRuntimeState(
            VpnConnectionState.DISCONNECTED,
            null,
            VpnRuntimeMode.ACCOUNT,
        )

        fun liveRuntimeState(): LiveRuntimeState = liveRuntimeState

        private fun updateLiveRuntimeState(
            state: VpnConnectionState,
            connectedAtMillis: Long?,
            runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
        ) {
            liveRuntimeState = LiveRuntimeState(
                state = state,
                connectedAtMillis = connectedAtMillis.takeIf { state == VpnConnectionState.CONNECTED },
                runtimeMode = runtimeMode,
            )
        }

        fun startIntent(
            context: Context,
            refreshSession: Boolean = false,
        ): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_REFRESH_SESSION, refreshSession)
        }

        fun temporaryStartIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_START_TEMPORARY
        }

        fun stopIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_STOP
        }

        fun stopAndRevokeTemporaryIntent(context: Context): Intent =
            Intent(context, AppVpnService::class.java).apply {
                action = ACTION_STOP_AND_REVOKE_TEMPORARY
            }

        fun restartIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_RESTART
        }

        fun queryStateIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_QUERY_STATE
        }

        fun applySettingsIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_APPLY_SETTINGS
        }
    }
}
