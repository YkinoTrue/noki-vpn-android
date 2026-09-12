package com.noki.vpn

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.ui.AppScreen
import com.noki.vpn.ui.NokiTheme
import com.noki.vpn.vpn.AppVpnService
import com.noki.vpn.vpn.NetworkCapabilityPolicy
import com.noki.vpn.vpn.VpnRuntimeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun Int.toStableDensityDp(currentDensityDpi: Int): Int {
    if (this <= 0 || currentDensityDpi <= 0) return this
    return (this * currentDensityDpi.toFloat() / DisplayMetrics.DENSITY_DEVICE_STABLE)
        .roundToInt()
}

internal fun shouldFilterTouchesWhenObscured(apiLevel: Int): Boolean =
    apiLevel <= Build.VERSION_CODES.R

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val telegramLoginGateway: TelegramLoginGateway
        get() = viewModel.telegramLoginGateway

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startPendingVpn()
        } else {
            viewModel.updateConnectionState(
                state = VpnConnectionState.FAILED,
                reason = "permission_denied",
                runtimeMode = viewModel.pendingVpnStartMode,
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        continueVpnFlow()
    }

    private val startupNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // No-op: this launcher only primes app notifications on first app start.
    }

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        viewModel.beginAvatarCrop(uri)
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (intent?.action != AppVpnService.ACTION_STATE_CHANGED) return
            val rawState = intent.getStringExtra(AppVpnService.EXTRA_STATE).orEmpty()
            val reason = intent.getStringExtra(AppVpnService.EXTRA_REASON).orEmpty()
            val connectedAtMillis = intent.getLongExtra(AppVpnService.EXTRA_CONNECTED_AT, 0L)
            val runtimeMode = runCatching {
                VpnRuntimeMode.valueOf(
                    intent.getStringExtra(AppVpnService.EXTRA_RUNTIME_MODE).orEmpty(),
                )
            }.getOrDefault(VpnRuntimeMode.ACCOUNT)
            val latencyLocationCode = intent.getStringExtra(AppVpnService.EXTRA_LATENCY_LOCATION_CODE).orEmpty()
            val latencyMs = intent.takeIf { it.hasExtra(AppVpnService.EXTRA_LATENCY_MS) }
                ?.getIntExtra(AppVpnService.EXTRA_LATENCY_MS, 0)
                ?.takeIf { it >= 0 }
            val state = runCatching { VpnConnectionState.valueOf(rawState) }
                .getOrElse { VpnConnectionState.DISCONNECTED }
            viewModel.updateConnectionState(
                state = state,
                reason = reason,
                connectedAtMillis = connectedAtMillis.takeIf { it > 0L },
                runtimeMode = runtimeMode,
                latencyLocationCode = latencyLocationCode,
                latencyMs = latencyMs,
            )
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val baseConfiguration = newBase.resources.configuration
        val currentDensityDpi = baseConfiguration.densityDpi
            .takeIf { it > 0 }
            ?: newBase.resources.displayMetrics.densityDpi
        val configuration = Configuration(baseConfiguration).apply {
            screenWidthDp = baseConfiguration.screenWidthDp.toStableDensityDp(currentDensityDpi)
            screenHeightDp = baseConfiguration.screenHeightDp.toStableDensityDp(currentDensityDpi)
            smallestScreenWidthDp =
                baseConfiguration.smallestScreenWidthDp.toStableDensityDp(currentDensityDpi)
            densityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE
            fontScale = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                fontWeightAdjustment = 0
            }
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldFilterTouchesWhenObscured(Build.VERSION.SDK_INT)) {
            window.decorView.rootView.filterTouchesWhenObscured = true
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            NokiTheme(accentPalette = viewModel.uiState.personalizationSettings.accentPalette) {
                AppScreen(
                    state = viewModel.uiState,
                    viewModel = viewModel,
                    onGoogleLoginClicked = ::launchGoogleLogin,
                    onTelegramLoginClicked = ::launchTelegramLogin,
                    onTelegramLinkClicked = ::launchTelegramLink,
                    onConnectClicked = { handleConnectClick() },
                    onTemporaryConnectClicked = { handleTemporaryConnectClick() },
                    onDisconnectClicked = viewModel::disconnect,
                    onOpenVpnSettings = ::openVpnSettings,
                    onPickAvatarClicked = {
                        avatarPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
        }
        handleTelegramLoginCallback(intent)
        handleAppNotificationActionIntent(intent)
        maybeRequestStartupPermissions()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshServerStats()
                while (true) {
                    delay(20_000)
                    viewModel.refreshServerStats()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTelegramLoginCallback(intent)
        handleAppNotificationActionIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppVpnService.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startService(AppVpnService.queryStateIntent(this))
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAppNotificationHistoryState()
        val timeoutLease = telegramLoginGateway.resumeTimeoutLease() ?: return
        viewModel.expireTelegramLoginAfterDelay(timeoutLease)
    }

    override fun onStop() {
        telegramLoginGateway.markHostStopped()
        unregisterReceiver(vpnStateReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        if (telegramLoginGateway.cancelPreparationIfPending()) {
            viewModel.handleTelegramLoginResult(TelegramLoginResult.Cancelled)
        }
        super.onDestroy()
    }

    private fun handleConnectClick() {
        beginVpnFlow(VpnRuntimeMode.ACCOUNT)
    }

    private fun handleTemporaryConnectClick() {
        beginVpnFlow(VpnRuntimeMode.AUTH_TEMP)
    }

    private fun launchTelegramLogin() {
        if (!viewModel.beginTelegramLogin()) return
        launchTelegramFlow()
    }

    private fun launchGoogleLogin() {
        if (!viewModel.beginGoogleLogin()) return
        val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (serverClientId.isBlank()) {
            viewModel.handleGoogleLoginResult(GoogleLoginResult.Failure("not_configured"))
            return
        }
        lifecycleScope.launch {
            val result = try {
                credentialManager.getCredential(
                    context = this@MainActivity,
                    request = GetCredentialRequest.Builder()
                        .addCredentialOption(
                            GetSignInWithGoogleOption.Builder(serverClientId).build(),
                        )
                        .build(),
                )
            } catch (_: GetCredentialCancellationException) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Cancelled)
                return@launch
            } catch (_: NoCredentialException) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Failure("credential_unavailable"))
                return@launch
            } catch (cancelled: CancellationException) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Cancelled)
                throw cancelled
            } catch (_: Throwable) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Failure("credential_request_failed"))
                return@launch
            }

            val credential = result.credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Failure("unexpected_credential"))
                return@launch
            }
            val idToken = try {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (_: GoogleIdTokenParsingException) {
                viewModel.handleGoogleLoginResult(GoogleLoginResult.Failure("invalid_credential"))
                return@launch
            }
            viewModel.handleGoogleLoginResult(GoogleLoginResult.Success(idToken))
        }
    }

    private fun launchTelegramLink() {
        if (!viewModel.beginTelegramLink()) return
        launchTelegramFlow()
    }

    private fun launchTelegramFlow() {
        val request = runCatching(telegramLoginGateway::begin).getOrElse {
            viewModel.handleTelegramLoginResult(
                TelegramLoginResult.Failure("sdk_launch_failed"),
            )
            return
        }
        lifecycleScope.launch {
            val telegramUrl = viewModel.prepareTelegramLogin(request.codeChallenge, request.clientState)
            if (telegramUrl == null) {
                telegramLoginGateway.cancel(request.clientState)
                return@launch
            }
            if (!telegramLoginGateway.markExternalFlowStarted(request.clientState)) return@launch
            val openedNatively = runCatching {
                telegramLoginGateway.openNative(this@MainActivity, telegramUrl)
            }.getOrElse {
                failTelegramLaunch()
                return@launch
            }
            if (openedNatively) return@launch

            if (!telegramLoginGateway.completeExternalFlow(request.clientState)) return@launch
            val browserUrl = viewModel.prepareTelegramBrowserLogin(request.codeChallenge, request.clientState)
            if (browserUrl == null) {
                telegramLoginGateway.cancel(request.clientState)
                return@launch
            }
            if (!telegramLoginGateway.markExternalFlowStarted(request.clientState, browser = true)) return@launch
            runCatching {
                telegramLoginGateway.openBrowser(this@MainActivity, browserUrl)
            }.onFailure { failTelegramLaunch() }
        }
    }

    private fun failTelegramLaunch() {
        telegramLoginGateway.cancel()
        viewModel.handleTelegramLoginResult(
            TelegramLoginResult.Failure("sdk_launch_failed"),
        )
    }

    private fun handleTelegramLoginCallback(intent: Intent?) {
        val callbackUri = intent?.data ?: return
        intent.data = null
        val result = telegramLoginGateway.handleLoginResponse(callbackUri)
            ?: TelegramLoginCallbackResult.Failure("callback_not_handled")
        if (TelegramCallbackPolicy.shouldIgnore(result)) {
            if (TelegramCallbackPolicy.accepts(callbackUri.toString())) {
                viewModel.handleTelegramLoginCallback(result)
            }
            return
        }
        if (result !is TelegramLoginCallbackResult.AuthorizationCode) {
            telegramLoginGateway.completeExternalFlow()
        }
        dispatchTelegramCallbackWhenReady(result)
    }

    private fun dispatchTelegramCallbackWhenReady(result: TelegramLoginCallbackResult) {
        lifecycleScope.launch {
            while (!viewModel.uiState.isReady) delay(25)
            viewModel.handleTelegramLoginCallback(result)
        }
    }

    private fun beginVpnFlow(runtimeMode: VpnRuntimeMode) {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetworkUsesVpn = connectivityManager
            .getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (NetworkCapabilityPolicy.hasCompetingVpn(activeNetworkUsesVpn, viewModel.uiState.connectionState)) {
            viewModel.showVpnConflict()
            return
        }
        viewModel.setPendingVpnStartMode(runtimeMode)
        if (shouldRequestNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueVpnFlow()
    }

    private fun openVpnSettings() {
        viewModel.dismissDialog()
        startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    }

    private fun continueVpnFlow() {
        val prepareIntent = viewModel.getVpnPermissionIntent(this)
        if (prepareIntent == null) {
            startPendingVpn()
            return
        }
        vpnPermissionLauncher.launch(prepareIntent)
    }

    private fun startPendingVpn() {
        when (viewModel.pendingVpnStartMode) {
            VpnRuntimeMode.ACCOUNT -> viewModel.startVpn()
            VpnRuntimeMode.AUTH_TEMP -> viewModel.startTemporaryVpn()
        }
    }

    private fun handleAppNotificationActionIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_APP_NOTIFICATION_ACTION)?.trim() ?: return
        val nonce = intent.getStringExtra(EXTRA_APP_NOTIFICATION_ACTION_NONCE)?.trim()
        intent.removeExtra(EXTRA_APP_NOTIFICATION_ACTION)
        intent.removeExtra(EXTRA_APP_NOTIFICATION_ACTION_NONCE)
        if (
            !AppNotificationActionPolicy.shouldAccept(
                action = action,
                nonce = nonce,
                issuedNonces = AppNotificationActionNonceStore.issuedNonces(this),
                allowedActions = setOf(APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE),
            )
        ) {
            return
        }
        AppNotificationActionNonceStore.consume(this, nonce.orEmpty())
        val destination = when (action) {
            APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE -> AppDestination.SECURITY
            else -> return
        }
        lifecycleScope.launch {
            while (!viewModel.uiState.isReady) {
                delay(50)
            }
            if (action == APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE) {
                viewModel.markAndroidUpdateAvailable()
            }
            viewModel.openScreen(destination)
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun maybeRequestStartupPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!StartupPermissionPolicy.shouldRequestNotificationPermissionOnFirstLaunch(
                sdkInt = Build.VERSION.SDK_INT,
                notificationGranted = !shouldRequestNotificationPermission(),
                alreadyRequested = startupPermissionsPreferences()
                    .getBoolean(KEY_STARTUP_NOTIFICATION_PERMISSION_REQUESTED, false),
            )
        ) {
            return
        }
        markStartupNotificationPermissionRequested()
        startupNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun markStartupNotificationPermissionRequested() {
        startupPermissionsPreferences().edit {
            putBoolean(KEY_STARTUP_NOTIFICATION_PERMISSION_REQUESTED, true)
        }
    }

    private fun startupPermissionsPreferences() =
        getSharedPreferences("noki_startup_permissions", Context.MODE_PRIVATE)

    companion object {
        const val EXTRA_APP_NOTIFICATION_ACTION = "noki_app_notification_action"
        const val EXTRA_APP_NOTIFICATION_ACTION_NONCE = "noki_app_notification_action_nonce"
        const val APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE = "open_security_update"
        private const val KEY_STARTUP_NOTIFICATION_PERMISSION_REQUESTED =
            "startup_notification_permission_requested"
    }
}
