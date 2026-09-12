package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.noki.vpn.AppDestination
import com.noki.vpn.AppDialog
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.isCurrentServerSelection
import com.noki.vpn.data.appRoutingModeLabel
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import java.util.Locale
import kotlinx.coroutines.delay
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit,
    sharedBackdrop: LayerBackdrop,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
    showBottomNavigation: Boolean = true,
) {
    CompositionLocalProvider(LocalTextStyle provides HomeNoFontPaddingTextStyle) {
        val destinationVisibility = LocalSharedDestinationVisibility.current
        val isSharedPrecomposing = destinationVisibility == SharedDestinationVisibility.Precomposing
        val liveWorkEnabled = destinationVisibility == SharedDestinationVisibility.FullyVisible
        val effectiveLiveGlassEnabled = liveGlassEnabled && !isSharedPrecomposing
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(HomeBgBase) else Modifier)
                .statusBarsPadding(),
        ) {
            val backdrop = if (showBackground || effectiveLiveGlassEnabled) sharedBackdrop else null
            val adaptiveMetrics = nokiAdaptiveMetrics(maxWidth)
            val scale = adaptiveMetrics.contentScale
            val screenMaxHeight = maxHeight
            val language = state.personalizationSettings.language
            val displayedCountryCode = if (state.userProfile.serverSelectionMode == ServerSelectionMode.AUTO) {
                state.userProfile.actualCountryCode
            } else state.userProfile.selectedCountryCode
            val selectedLocation = homeSelectedLocation(state.locations, state.userProfile)
            val selectedServer = if (state.userProfile.serverSelectionMode == ServerSelectionMode.SERVER) {
                selectedLocation?.servers?.firstOrNull { server ->
                    server.id == state.userProfile.selectedNodeId ||
                        server.locationCode.equals(state.userProfile.selectedServerCode, ignoreCase = true)
                }
            } else {
                null
            }
            val isConnected = state.connectionState == VpnConnectionState.CONNECTED ||
                state.connectionState == VpnConnectionState.CONNECTING
            val deviceTraffic = rememberDeviceTrafficSnapshot(
                connectionState = state.connectionState,
                enabled = liveWorkEnabled,
            )
            val metrics = currentMetrics(selectedLocation, state.connectionState, deviceTraffic, state.activeLatencyMs)
            val autoEndpointSelection =
                state.advancedSettings.endpointSelectionMode == EndpointSelectionMode.AUTO
            val connectionTimeLabel = rememberConnectionTimeLabel(
                connectionState = state.connectionState,
                connectedAtMillis = state.connectedAtMillis,
                enabled = liveWorkEnabled,
            )
            var isServerMenuExpanded by rememberSaveable { mutableStateOf(false) }
            var keepServerSheetBackdrop by remember { mutableStateOf(false) }
            LaunchedEffect(isServerMenuExpanded) {
                if (isServerMenuExpanded) {
                    keepServerSheetBackdrop = true
                } else {
                    delay(HOME_SERVER_DROPDOWN_BACKDROP_KEEP_ALIVE_MS)
                    keepServerSheetBackdrop = false
                }
            }
            val serverSheetBackdropActive = isServerMenuExpanded || keepServerSheetBackdrop
            val homeContentBackdrop = if (effectiveLiveGlassEnabled && serverSheetBackdropActive) {
                rememberLayerBackdrop()
            } else {
                null
            }
            val serverSheetBackdrop = if (
                effectiveLiveGlassEnabled &&
                serverSheetBackdropActive &&
                backdrop != null &&
                homeContentBackdrop != null
            ) {
                rememberCombinedBackdrop(
                    backdrop,
                    rememberBackdrop(homeContentBackdrop) { drawBackdrop ->
                        drawBackdrop()
                    },
                )
            } else {
                backdrop
            }
            val serverConfirmationBackdrop = if (
                effectiveLiveGlassEnabled &&
                isServerMenuExpanded &&
                state.dialog is AppDialog.ChangeServer
            ) {
                rememberLayerBackdrop()
            } else {
                null
            }
            val density = LocalDensity.current
            val navBarBottomInset = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            val statusBarTopInset = with(density) {
                WindowInsets.statusBars.getTop(this).toDp()
            }
            val contentWidth = adaptiveMetrics.dp(370f)
            val topGap = designDp(20f, scale)
            val homeTopShift = designDp(10f, scale)
            val locationHeight = designDp(NokiUiKitPolicy.homeLocationHeightDp, scale)
            val sectionGap = designDp(22f, scale)
            val metricsHeight = designDp(NokiUiKitPolicy.homeMetricsHeightDp, scale)
            val actionHeight = designDp(90f, scale)
            val actionGap = designDp(8f, scale)
            val navigationHeight = designDp(60f, scale)
            val navigationBottomGap = 20.dp
            val actionToNavigationGap = designDp(40f, scale)
            val bottomContentReserve = navBarBottomInset +
                navigationBottomGap +
                navigationHeight +
                actionToNavigationGap
            val minimumContentHeight = topGap + homeTopShift + locationHeight + sectionGap +
                metricsHeight + designDp(24f, scale) +
                designDp(NokiUiKitPolicy.homeQuickPillHeightDp * 2f + 8f + 17f, scale) +
                actionHeight + bottomContentReserve
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .height(screenMaxHeight.coerceAtLeast(minimumContentHeight))
                    .then(
                        if (showBackground && backdrop != null) {
                            Modifier.layerBackdrop(backdrop)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (homeContentBackdrop != null) {
                            Modifier.layerBackdrop(homeContentBackdrop)
                        } else {
                            Modifier
                        },
                    )
            ) {
                if (showBackground) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                    AuroraOverlay(
                        scale = scale,
                        visible = shouldShowConnectedAurora(
                            liveGlassEnabled = liveGlassEnabled,
                            connected = state.connectionState == VpnConnectionState.CONNECTED,
                        ),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .width(contentWidth)
                        .padding(
                            top = topGap + homeTopShift + locationHeight + sectionGap,
                            bottom = bottomContentReserve,
                        ),
                ) {
                    HomeMetricsPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metricsHeight),
                        scale = scale,
                        language = language,
                        metrics = metrics,
                        backdrop = backdrop,
                        liveGlassEnabled = effectiveLiveGlassEnabled,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    HomeQuickSettingsPill(
                        text = protocolCardLabel(state, autoEndpointSelection),
                        iconResId = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(designDp(NokiUiKitPolicy.homeQuickPillHeightDp, scale)),
                        scale = scale,
                        backdrop = backdrop,
                        liveGlassEnabled = effectiveLiveGlassEnabled,
                        onClick = { viewModel.openScreen(AppDestination.ADVANCED_SETTINGS) },
                    )
                    Spacer(modifier = Modifier.height(designDp(8f, scale)))
                    HomeQuickSettingsPill(
                        text = appRoutingModeLabel(state.filterMode, language),
                        iconResId = R.drawable.home_routing_mode_icon,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(designDp(NokiUiKitPolicy.homeQuickPillHeightDp, scale)),
                        scale = scale,
                        backdrop = backdrop,
                        liveGlassEnabled = effectiveLiveGlassEnabled,
                        onClick = { viewModel.openScreen(AppDestination.ADVANCED_SETTINGS) },
                    )
                    Spacer(modifier = Modifier.height(designDp(17f, scale)))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(actionHeight),
                        horizontalArrangement = Arrangement.spacedBy(actionGap),
                    ) {
                        HomePowerButton(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            scale = scale,
                            connectionState = state.connectionState,
                            backdrop = backdrop,
                            liveGlassEnabled = effectiveLiveGlassEnabled,
                            onClick = if (isConnected) onDisconnectClicked else onConnectClicked,
                        )

                        HomeStatusCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            scale = scale,
                            backdrop = backdrop,
                            liveGlassEnabled = effectiveLiveGlassEnabled,
                            timeLabel = connectionTimeLabel,
                            statusLabel = homeConnectionStatusLabel(
                                language = language,
                                state = state.connectionState,
                                failureReason = state.connectionReason,
                            ),
                        )
                    }
                }

                HomeLocationCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topGap + homeTopShift)
                        .width(contentWidth)
                        .height(locationHeight)
                        .zIndex(3f),
                    scale = scale,
                    language = language,
                    locations = state.locations,
                    selectedServerCode = selectedLocation?.code ?: displayedCountryCode,
                    expanded = isServerMenuExpanded,
                    onToggle = {
                        isServerMenuExpanded = !isServerMenuExpanded
                        if (isServerMenuExpanded) viewModel.refreshServerStats()
                    },
                    backdrop = backdrop,
                    liveGlassEnabled = effectiveLiveGlassEnabled,
                    country = if (state.userProfile.serverSelectionMode == ServerSelectionMode.AUTO) {
                        tr(language, "Автовыбор", "Automatic")
                    } else selectedLocation?.let { localizedServerCountry(it, language) }
                        ?: tr(language, "Нет сервера", "No server"),
                    subtitle = if (state.userProfile.serverSelectionMode == ServerSelectionMode.AUTO) {
                        selectedLocation?.let { localizedServerCountry(it, language) }
                    } else if (state.userProfile.serverSelectionMode == ServerSelectionMode.SERVER) {
                        selectedServer?.name
                    } else null,
                )
            }

            if (showBottomNavigation) {
                HomeBottomNavigation(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBarBottomInset + navigationBottomGap)
                        .width(contentWidth)
                        .height(navigationHeight),
                    scale = scale,
                    backdrop = backdrop,
                    selectedTabIndex = 0,
                    hasUnreadAppNotifications = state.hasUnreadAppNotifications,
                    isAndroidUpdateAvailable = state.isAndroidUpdateAvailable,
                    onTabSelected = { index ->
                        viewModel.openTopLevelScreen(
                            PrimaryNavigationPolicy.destinationForTab(index),
                        )
                    },
                )
            }

            val serverOverlayBottomReserve = navBarBottomInset +
                navigationBottomGap +
                navigationHeight +
                designDp(20f, scale)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -statusBarTopInset)
                    .fillMaxWidth()
                    .height(
                        (screenMaxHeight + statusBarTopInset - serverOverlayBottomReserve)
                            .coerceAtLeast(locationHeight + statusBarTopInset),
                    )
                    .zIndex(20f)
                    .then(
                        if (serverConfirmationBackdrop != null) {
                            Modifier.layerBackdrop(serverConfirmationBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HomeServerDropdownOverlay(
                    modifier = Modifier.fillMaxSize(),
                    visible = isServerMenuExpanded,
                    scale = scale,
                    language = language,
                    locations = state.locations,
                    backdrop = serverSheetBackdrop,
                    serverRowsBackdrop = backdrop,
                    liveGlassEnabled = effectiveLiveGlassEnabled,
                    animateRows = liveWorkEnabled,
                    onCollapse = {
                        isServerMenuExpanded = false
                    },
                    userProfile = state.userProfile,
                    onLocationSelected = { code, mode ->
                        val selectedCode = code.trim()
                        if (isCurrentServerSelection(state.userProfile, selectedCode, mode)) {
                            isServerMenuExpanded = false
                        } else {
                            viewModel.requestServerChange(selectedCode, mode)
                        }
                    },
                )
            }

            (state.dialog as? AppDialog.ChangeServer)?.let { dialog ->
                val code = dialog.locationCode
                val serverName = when (dialog.mode) {
                    ServerSelectionMode.AUTO -> tr(language, "Автовыбор", "Automatic")
                    ServerSelectionMode.COUNTRY -> state.locations.firstOrNull { it.code.equals(code, true) }
                        ?.let { localizedServerCountry(it, language) } ?: code
                    ServerSelectionMode.SERVER -> state.locations.flatMap { it.servers }
                        .firstOrNull { it.id == code }?.name ?: tr(language, "Сервер недоступен", "Server unavailable")
                }
                val message = if (state.connectionState == VpnConnectionState.DISCONNECTED) {
                    tr(
                        language,
                        "Выбрать сервер «%s» для следующего подключения?",
                        "Select “%s” for the next connection?",
                    )
                } else {
                    tr(
                        language,
                        "VPN переподключится к серверу «%s». Продолжить?",
                        "VPN will reconnect to “%s”. Continue?",
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -statusBarTopInset)
                        .fillMaxWidth()
                        .height(screenMaxHeight + statusBarTopInset)
                        .zIndex(30f),
                ) {
                    SettingsConfirmDialog(
                        title = tr(language, "Сменить сервер?", "Change server?"),
                        message = String.format(Locale.ROOT, message, serverName),
                        dismissText = tr(language, "Отмена", "Cancel"),
                        confirmText = tr(language, "Сменить", "Change"),
                        confirmIsDanger = false,
                        scale = scale,
                        backdrop = serverConfirmationBackdrop,
                        liveGlassEnabled = effectiveLiveGlassEnabled,
                        onDismiss = viewModel::dismissDialog,
                        onConfirm = {
                            viewModel.confirmDialog()
                            isServerMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}
