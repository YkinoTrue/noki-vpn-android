package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.noki.vpn.AppDestination
import com.noki.vpn.AppUiState
import com.noki.vpn.AuthStep
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.VpnRuntimeMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private data class SettingsDestinationArgs(
    val state: AppUiState,
    val viewModel: MainViewModel,
    val sharedBackdrop: LayerBackdrop,
    val menuRowsBackdrop: LayerBackdrop,
    val liveGlassEnabled: Boolean,
    val onLogoutClicked: () -> Unit,
    val onLogoutConfirmed: () -> Unit,
    val onDevicesClicked: () -> Unit,
    val onSecurityClicked: () -> Unit,
    val onSupportClicked: () -> Unit,
    val onPersonalizationClicked: () -> Unit,
    val onAdvancedClicked: () -> Unit,
    val onPlanClicked: () -> Unit,
    val onStatsClicked: () -> Unit,
)

private data class HomeDestinationArgs(
    val state: AppUiState,
    val viewModel: MainViewModel,
    val sharedBackdrop: LayerBackdrop,
    val liveGlassEnabled: Boolean,
    val onConnectClicked: () -> Unit,
    val onDisconnectClicked: () -> Unit,
)

internal fun shouldShowConnectedAurora(
    liveGlassEnabled: Boolean,
    connected: Boolean,
): Boolean = liveGlassEnabled && connected

internal fun refreshVisibleScreenData(
    destination: AppDestination,
    refreshAllData: (refreshClientLatency: Boolean) -> Unit,
    refreshIncyDevices: () -> Unit,
) {
    refreshAllData(destination == AppDestination.HOME)
    if (destination == AppDestination.DEVICES) refreshIncyDevices()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onGoogleLoginClicked: () -> Unit,
    onTelegramLoginClicked: () -> Unit,
    onTelegramLinkClicked: () -> Unit,
    onConnectClicked: () -> Unit,
    onTemporaryConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onPickAvatarClicked: () -> Unit,
) {
    var plansCheckoutVisible by remember { mutableStateOf(false) }
    val sharedBackdrop = rememberLayerBackdrop {
        drawRect(Color(0xFF07111A))
        drawContent()
    }
    val sharedBackgroundBackdrop = rememberLayerBackdrop {
        drawRect(Color(0xFF07111A))
        drawContent()
    }
    val glassMode = state.personalizationSettings.glassMode
    val liveGlassEnabled = glassMode.liveGlassEnabled
    val simpleTransitions = glassMode.simpleTransitions
    val showSharedNavigation = PrimaryNavigationPolicy.showsSharedNavigation(
        isAuthenticated = state.isAuthenticated,
        destination = state.currentDestination,
    )
    val showSharedBottomNavigation = PrimaryNavigationPolicy.showsBottomNavigation(
        isAuthenticated = state.isAuthenticated,
        destination = state.currentDestination,
        dialogVisible = state.dialog != null,
        plansCheckoutVisible = plansCheckoutVisible,
    )
    val selectedBottomTabIndex = PrimaryNavigationPolicy.selectedTabIndex(state.currentDestination)
    val hasAppBackStack = state.screenStack.size > 1
    val hasGeneratedDeviceInvite =
        state.currentDestination == AppDestination.DEVICES &&
            !state.inviteDeviceForm.generatedInviteCode.isNullOrBlank()
    val hasAuthBackStack =
        !state.isAuthenticated &&
            (
                (state.currentDestination == AppDestination.LOGIN && state.authStep != AuthStep.WELCOME) ||
                    state.currentDestination == AppDestination.REGISTRATION
            )

    BackHandler(
        enabled = state.pendingAvatarCropUri != null ||
            state.dialog != null ||
            hasGeneratedDeviceInvite ||
            hasAppBackStack ||
            hasAuthBackStack,
    ) {
        if (state.pendingAvatarCropUri != null) {
            viewModel.cancelAvatarCrop()
        } else if (state.dialog != null) {
            viewModel.dismissDialog()
        } else if (hasGeneratedDeviceInvite) {
            viewModel.dismissGeneratedDeviceInvite()
        } else {
            viewModel.goBack()
        }
    }

    fun openSharedDestination(destination: AppDestination) {
        if (state.currentDestination != destination) {
            viewModel.openScreen(destination)
        }
    }

    val settingsDestinationContent = remember {
        movableContentOf<SettingsDestinationArgs> { args ->
            SettingsScreen(
                state = args.state,
                viewModel = args.viewModel,
                sharedBackdrop = args.sharedBackdrop,
                menuRowsBackdrop = args.menuRowsBackdrop,
                liveGlassEnabled = args.liveGlassEnabled,
                showBackground = false,
                showBottomNavigation = false,
                onLogoutClicked = args.onLogoutClicked,
                onLogoutConfirmed = args.onLogoutConfirmed,
                onDevicesClicked = args.onDevicesClicked,
                onSecurityClicked = args.onSecurityClicked,
                onSupportClicked = args.onSupportClicked,
                onPersonalizationClicked = args.onPersonalizationClicked,
                onAdvancedClicked = args.onAdvancedClicked,
                onPlanClicked = args.onPlanClicked,
                onStatsClicked = args.onStatsClicked,
            )
        }
    }
    val homeDestinationContent = remember {
        movableContentOf<HomeDestinationArgs> { args ->
            HomeScreen(
                state = args.state,
                viewModel = args.viewModel,
                onConnectClicked = args.onConnectClicked,
                onDisconnectClicked = args.onDisconnectClicked,
                sharedBackdrop = args.sharedBackdrop,
                liveGlassEnabled = args.liveGlassEnabled,
                showBackground = false,
                showBottomNavigation = false,
            )
        }
    }
    val homeArgs = HomeDestinationArgs(
        state = state,
        viewModel = viewModel,
        sharedBackdrop = sharedBackgroundBackdrop,
        liveGlassEnabled = liveGlassEnabled,
        onConnectClicked = onConnectClicked,
        onDisconnectClicked = onDisconnectClicked,
    )
    val settingsArgs = SettingsDestinationArgs(
        state = state,
        viewModel = viewModel,
        sharedBackdrop = sharedBackgroundBackdrop,
        menuRowsBackdrop = sharedBackgroundBackdrop,
        liveGlassEnabled = liveGlassEnabled,
        onLogoutClicked = viewModel::requestLogout,
        onLogoutConfirmed = {
            onDisconnectClicked()
            viewModel.confirmDialog()
        },
        onDevicesClicked = {
            openSharedDestination(AppDestination.DEVICES)
        },
        onSecurityClicked = {
            openSharedDestination(AppDestination.SECURITY)
        },
        onSupportClicked = {
            openSharedDestination(AppDestination.SUPPORT)
        },
        onPersonalizationClicked = {
            openSharedDestination(AppDestination.PERSONALIZATION)
        },
        onAdvancedClicked = {
            openSharedDestination(AppDestination.ADVANCED_SETTINGS)
        },
        onPlanClicked = {
            openSharedDestination(AppDestination.PLANS)
        },
        onStatsClicked = {
            openSharedDestination(AppDestination.STATS)
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07111A)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showSharedNavigation && liveGlassEnabled) {
                        Modifier.layerBackdrop(sharedBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {
                if (showSharedNavigation) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (liveGlassEnabled) {
                                    Modifier.layerBackdrop(sharedBackgroundBackdrop)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        val scale = nokiAdaptiveMetrics(maxWidth).contentScale
                    HomeBackground(
                        liveGlassEnabled = liveGlassEnabled,
                        showCenterShade = false,
                    )
                    AuroraOverlay(
                        scale = scale,
                        visible = shouldShowConnectedAurora(
                            liveGlassEnabled = liveGlassEnabled,
                            connected = state.connectionState == VpnConnectionState.CONNECTED,
                        ),
                        showTopLayer = state.currentDestination != AppDestination.SETTINGS,
                    )
                }
            }

            val destinationContent: @Composable (AppDestination) -> Unit = { destination ->
                    when (destination) {
                        AppDestination.SPLASH -> SplashScreen()
                        AppDestination.LOGIN -> LoginScreen(
                            state = state,
                            viewModel = viewModel,
                            liveGlassEnabled = liveGlassEnabled,
                            onGoogleClick = onGoogleLoginClicked,
                            onTelegramClick = onTelegramLoginClicked,
                            onTemporaryVpnClick = {
                                if (state.vpnRuntimeMode == VpnRuntimeMode.AUTH_TEMP &&
                                    (state.connectionState == VpnConnectionState.CONNECTED ||
                                        state.connectionState == VpnConnectionState.CONNECTING)
                                ) {
                                    onDisconnectClicked()
                                } else {
                                    onTemporaryConnectClicked()
                                }
                            },
                            onSupportClick = { viewModel.openScreen(AppDestination.SUPPORT) },
                        )
                        AppDestination.REGISTRATION -> RegistrationScreen(
                            state = state,
                            viewModel = viewModel,
                            liveGlassEnabled = liveGlassEnabled,
                        )
                        AppDestination.PASSWORD_RECOVERY -> PasswordRecoveryScreen(
                            state = state,
                            viewModel = viewModel,
                            liveGlassEnabled = liveGlassEnabled,
                        )
                        AppDestination.ACCOUNT_CREDENTIAL_CHANGE -> AccountCredentialChangeScreen(
                            state = state,
                            viewModel = viewModel,
                            liveGlassEnabled = liveGlassEnabled,
                        )
                        AppDestination.INVITE_DEVICE -> InviteDeviceScreen(
                            state = state,
                            viewModel = viewModel,
                            liveGlassEnabled = liveGlassEnabled,
                        )
                        AppDestination.INVITE_QR_SCANNER -> InviteQrScannerScreen(state, viewModel)
                        AppDestination.SETTINGS -> settingsDestinationContent(settingsArgs)
                        AppDestination.PLANS -> PlansScreen(
                            state = state,
                            viewModel = viewModel,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                            onCheckoutVisibilityChanged = { plansCheckoutVisible = it },
                        )
                        AppDestination.ADVANCED_SETTINGS -> AdvancedSettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            cardBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                            onAppFilterClicked = {
                                openSharedDestination(AppDestination.APP_FILTER)
                            },
                            onAlwaysOnRulesClicked = {
                                openSharedDestination(AppDestination.SITE_RULES_ALWAYS_ON)
                            },
                            onBypassRulesClicked = {
                                openSharedDestination(AppDestination.SITE_RULES_BYPASS)
                            },
                            onMultiHopClicked = {
                                openSharedDestination(AppDestination.MULTIHOP)
                            },
                        )
                        AppDestination.MULTIHOP -> MultiHopScreen(state, viewModel)
                        AppDestination.APP_FILTER -> AppFilterScreen(
                            state = state,
                            viewModel = viewModel,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                        )
                        AppDestination.SITE_RULES_ALWAYS_ON -> SiteRulesScreen(
                            state = state,
                            viewModel = viewModel,
                            mode = SiteRulesMode.ALWAYS_ON,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                        )
                        AppDestination.SITE_RULES_BYPASS -> SiteRulesScreen(
                            state = state,
                            viewModel = viewModel,
                            mode = SiteRulesMode.BYPASS,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                        )
                        AppDestination.SECURITY -> SecurityScreen(
                            state = state,
                            viewModel = viewModel,
                            onTelegramLinkClick = onTelegramLinkClicked,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            menuRowsBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                        )
                        AppDestination.SUPPORT -> SupportScreen(
                            state = state,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = !showSharedNavigation,
                        )
                        AppDestination.PERSONALIZATION -> PersonalizationScreen(
                            state = state,
                            onLanguageChanged = viewModel::setLanguage,
                            onGlassModeChanged = viewModel::setGlassMode,
                            onPickAvatarClicked = onPickAvatarClicked,
                            onDeleteAvatarClicked = viewModel::deleteAvatar,
                            onAvatarEditDenied = viewModel::showCurrentDeviceAccessDenied,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                        )
                        AppDestination.DEVICES -> DevicesScreen(
                            state = state,
                            viewModel = viewModel,
                            showBackground = false,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            deviceRowsBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                        )
                        AppDestination.ACCOUNT -> AccountScreen(
                            state = state,
                            sharedBackdrop = sharedBackgroundBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onPersonalizationClicked = {
                                openSharedDestination(AppDestination.PERSONALIZATION)
                            },
                            onPlansClicked = { openSharedDestination(AppDestination.PLANS) },
                            onDevicesClicked = { openSharedDestination(AppDestination.DEVICES) },
                            onSupportClicked = { openSharedDestination(AppDestination.SUPPORT) },
                            onSecurityClicked = { openSharedDestination(AppDestination.SECURITY) },
                            onNotificationsClicked = viewModel::openAppNotificationHistory,
                            onPaymentHistoryClicked = viewModel::loadPaymentHistory,
                            onApplyPromoCode = { viewModel.applyPromoCode(it) },
                            onClearPromoCode = viewModel::clearPromoCode,
                            onNotificationDeleted = viewModel::deleteAppNotification,
                            onDeleteAccountClicked = viewModel::requestAccountDeletion,
                            onAccessDenied = viewModel::showCurrentDeviceAccessDenied,
                            onDismissDialog = viewModel::dismissDialog,
                            onConfirmDialog = viewModel::confirmDialog,
                        )
                        AppDestination.STATS -> StatsScreen(
                            state = state,
                            liveGlassEnabled = liveGlassEnabled,
                            showBackground = false,
                            onRefreshStats = viewModel::refreshOfflineStats,
                        )
                        AppDestination.HOME -> homeDestinationContent(homeArgs)
                    }
            }

            val appContent: @Composable () -> Unit = {
                if (showSharedNavigation) {
                    SharedDestinationHost(
                        destination = state.currentDestination,
                        modifier = Modifier.fillMaxSize(),
                        useSlideTransition = { previousDestination, destination ->
                            !simpleTransitions &&
                                previousDestination.isPrimaryNavigationDestination() &&
                                destination.isPrimaryNavigationDestination()
                        },
                        useFadeTransition = { previousDestination, destination ->
                            simpleTransitions &&
                                previousDestination.isPrimaryNavigationDestination() &&
                                destination.isPrimaryNavigationDestination()
                        },
                    ) { destination ->
                        destinationContent(destination)
                    }
                } else {
                    AnimatedContent(
                        targetState = state.currentDestination,
                        transitionSpec = {
                            fadeIn(
                                tween(durationMillis = 96, easing = FastOutSlowInEasing),
                            ).togetherWith(
                                fadeOut(tween(durationMillis = 72, easing = FastOutSlowInEasing)),
                            )
                        },
                        label = "appDestinationTransition",
                    ) { destination ->
                        destinationContent(destination)
                    }
                }
            }

            if (showSharedNavigation && state.isAuthenticated) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshingData,
                    onRefresh = {
                        refreshVisibleScreenData(
                            destination = state.currentDestination,
                            refreshAllData = { latency -> viewModel.refreshAllData(refreshClientLatency = latency) },
                            refreshIncyDevices = viewModel::refreshIncyDevices,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    appContent()
                }
            } else {
                appContent()
            }
        }

        if (showSharedBottomNavigation) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                val metrics = nokiAdaptiveMetrics(maxWidth)
                val scale = metrics.contentScale
                val density = LocalDensity.current
                val navBarBottomInset = WindowInsets.navigationBars.getBottom(density)
                val bottomNavYPx = with(density) {
                    maxHeight.toPx() - navBarBottomInset - metrics.dp(60f).toPx() - metrics.dp(20f).toPx()
                }
                ProgressiveBottomBlur(
                    modifier = Modifier
                        .offset(y = with(density) { bottomNavYPx.toDp() })
                        .fillMaxWidth()
                        .height(metrics.dp(220f)),
                    backdrop = sharedBackdrop,
                    scale = scale,
                    liveGlassEnabled = liveGlassEnabled,
                )
                HomeBottomNavigation(
                    modifier = Modifier
                        .offset(x = metrics.screenX(21f), y = with(density) { bottomNavYPx.toDp() })
                        .width(metrics.dp(370f))
                        .height(metrics.dp(60f)),
                    scale = scale,
                    backdrop = sharedBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    selectedTabIndex = selectedBottomTabIndex,
                    hasUnreadAppNotifications = state.hasUnreadAppNotifications,
                    isAndroidUpdateAvailable = state.isAndroidUpdateAvailable,
                    onTabSelected = { index ->
                        viewModel.openTopLevelScreen(
                            PrimaryNavigationPolicy.destinationForTab(index),
                        )
                    },
                )
            }
        }

        state.pendingAvatarCropUri?.let { sourceUri ->
            AvatarCropScreen(
                sourceUri = sourceUri,
                language = state.personalizationSettings.language,
                isUploading = state.isUploadingAvatar,
                message = state.avatarUploadMessage,
                onCancel = viewModel::cancelAvatarCrop,
                onConfirm = { previewWidthPx, previewHeightPx, cropCircleSizePx, cropScale, cropOffsetX, cropOffsetY, rotationQuarterTurns ->
                    viewModel.uploadCroppedAvatar(
                        sourceUri = sourceUri,
                        previewWidthPx = previewWidthPx,
                        previewHeightPx = previewHeightPx,
                        cropCircleSizePx = cropCircleSizePx,
                        cropScale = cropScale,
                        cropOffsetX = cropOffsetX,
                        cropOffsetY = cropOffsetY,
                        rotationQuarterTurns = rotationQuarterTurns,
                    )
                },
            )
        }

        AppDialogHost(state, viewModel, onOpenVpnSettings)
    }
}
