package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noki.vpn.AppDialog
import com.noki.vpn.AppDestination
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight

private const val SettingsDesignTopInset = 44f

private data class SettingsMenuRowSpec(
    val key: String,
    val iconRes: Int,
    val iconWidth: Float,
    val iconHeight: Float,
    val title: String,
    val showBadge: Boolean = false,
    val onClick: () -> Unit,
)

internal val SettingsBgBase = Color(0xFF07111A)
internal val SettingsBgLighter = Color(0xFF0D1B2A)
internal val SettingsBgSoft = Color(0xFF132635)
internal val SettingsTextPrimary = Color(0xFFF4FBFF)
internal val SettingsTextSecondary = Color(0xFF9FB6C5)
internal val SettingsTextMuted = Color(0xFF6E8797)
internal val SettingsAccentPrimary = Color(0xFF7AE7C7)
internal val SettingsAccentSecondary = Color(0xFF8CC8FF)
internal val SettingsAccentStrong = Color(0xFF42D6A4)
internal val SettingsStroke = Color(0xFF29404E)
internal val SettingsError = Color(0xFFFF6B6B)
internal val SettingsNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun SettingsScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    sharedBackdrop: LayerBackdrop,
    menuRowsBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
    showBottomNavigation: Boolean = true,
    onDevicesClicked: () -> Unit = { viewModel.openScreen(AppDestination.DEVICES) },
    onSecurityClicked: () -> Unit = { viewModel.openScreen(AppDestination.SECURITY) },
    onAdvancedClicked: () -> Unit = { viewModel.openScreen(AppDestination.ADVANCED_SETTINGS) },
    onPersonalizationClicked: () -> Unit = { viewModel.openScreen(AppDestination.PERSONALIZATION) },
    onSupportClicked: () -> Unit = { viewModel.openScreen(AppDestination.SUPPORT) },
    onPlanClicked: () -> Unit = { viewModel.openScreen(AppDestination.PLANS) },
    onStatsClicked: () -> Unit = { viewModel.openScreen(AppDestination.STATS) },
    onLogoutClicked: () -> Unit = viewModel::requestLogout,
    onLogoutConfirmed: () -> Unit = viewModel::confirmDialog,
) {
    CompositionLocalProvider(LocalTextStyle provides SettingsNoFontPaddingTextStyle) {
        val destinationVisibility = LocalSharedDestinationVisibility.current
        val isSharedPrecomposing = destinationVisibility == SharedDestinationVisibility.Precomposing
        val effectiveLiveGlassEnabled = liveGlassEnabled && !isSharedPrecomposing
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(SettingsBgBase) else Modifier),
        ) {
            val backdrop = if (showBackground || effectiveLiveGlassEnabled) sharedBackdrop else null
            val menuBackdrop = menuRowsBackdrop ?: backdrop
            val preparedSettings = state.settingsPreparedState
            val trafficPresentation = remember(state) {
                AccountPresentationPolicy.prepare(state).settingsTraffic
            }
            val hasSettingsModal = state.dialog == AppDialog.Logout || state.dialog == AppDialog.AccessDenied
            val modalBackdrop = if (effectiveLiveGlassEnabled && hasSettingsModal) {
                rememberLayerBackdrop {
                    drawRect(SettingsBgBase)
                    drawContent()
                }
            } else {
                null
            }
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val scale = metrics.contentScale
            val menuScale = scale.coerceAtLeast(1f)
            val density = LocalDensity.current
            val navigationBottomInset = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            val contentBottomReserve = navigationBottomInset +
                metrics.dp(60f) +
                metrics.dp(20f) +
                metrics.dp(NokiUiKitPolicy.primaryNavigationContentGapDp)
            val language = preparedSettings.language
            val isInvitedDevice = preparedSettings.isInvitedDevice
            val denyCurrentDeviceAccess = { viewModel.showCurrentDeviceAccessDenied() }
            val settingsMenuRows = listOf(
                SettingsMenuRowSpec(
                    key = "devices",
                    iconRes = R.drawable.settings_devices_icon,
                    iconWidth = 25f,
                    iconHeight = 25f,
                    title = preparedSettings.devicesTitle,
                    onClick = if (isInvitedDevice) denyCurrentDeviceAccess else onDevicesClicked,
                ),
                SettingsMenuRowSpec(
                    key = "advanced",
                    iconRes = R.drawable.settings_advanced_icon,
                    iconWidth = 24f,
                    iconHeight = 25f,
                    title = preparedSettings.advancedTitle,
                    onClick = onAdvancedClicked,
                ),
                SettingsMenuRowSpec(
                    key = "security",
                    iconRes = R.drawable.settings_security_icon,
                    iconWidth = 22f,
                    iconHeight = 25f,
                    title = preparedSettings.securityTitle,
                    showBadge = preparedSettings.showSecurityUpdateBadge,
                    onClick = onSecurityClicked,
                ),
                SettingsMenuRowSpec(
                    key = "support",
                    iconRes = R.drawable.settings_support_icon,
                    iconWidth = 23f,
                    iconHeight = 25f,
                    title = preparedSettings.supportTitle,
                    onClick = onSupportClicked,
                ),
                SettingsMenuRowSpec(
                    key = "personalization",
                    iconRes = R.drawable.settings_personalization_icon,
                    iconWidth = 23.26f,
                    iconHeight = 25f,
                    title = preparedSettings.personalizationTitle,
                    onClick = onPersonalizationClicked,
                ),
                SettingsMenuRowSpec(
                    key = "logout",
                    iconRes = R.drawable.settings_logout_icon,
                    iconWidth = 23f,
                    iconHeight = 25f,
                    title = preparedSettings.logoutTitle,
                    onClick = onLogoutClicked,
                ),
            )

            if (showBackground) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (backdrop != null) {
                                Modifier.layerBackdrop(backdrop)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (effectiveLiveGlassEnabled && modalBackdrop != null) {
                            Modifier.layerBackdrop(modalBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(metrics.contentWidth)
                        .statusBarsPadding(),
                    contentPadding = PaddingValues(
                        top = metrics.dp(20f),
                        bottom = contentBottomReserve,
                    ),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "settings-summary", contentType = "settings-summary") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SettingsProfileCardAdaptive(
                                preparedState = preparedSettings,
                                liveGlassEnabled = effectiveLiveGlassEnabled,
                                scale = scale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(metrics.dp(128f)),
                                onPlanClicked = onPlanClicked,
                            )

                            SettingsTrafficCardAdaptive(
                                presentation = trafficPresentation,
                                scale = scale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(metrics.dp(130f)),
                                onPlanClicked = onPlanClicked,
                                onStatsClicked = onStatsClicked,
                            )
                        }
                    }

                    items(
                        items = settingsMenuRows,
                        key = { it.key },
                        contentType = { "settings-menu-row" },
                    ) { row ->
                        SettingsMenuRowAdaptive(
                            iconRes = row.iconRes,
                            iconWidth = row.iconWidth,
                            iconHeight = row.iconHeight,
                            title = row.title,
                            backdrop = menuBackdrop,
                            liveGlassEnabled = effectiveLiveGlassEnabled,
                            scale = menuScale,
                            showBadge = row.showBadge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(settingsDp(80f, menuScale)),
                            onClick = row.onClick,
                        )
                    }
                }
            }

            if (showBottomNavigation) {
                HomeBottomNavigation(
                    modifier = Modifier
                        .offset(
                            x = metrics.screenX(21f),
                            y = settingsDp(803f - SettingsDesignTopInset, scale),
                        )
                        .width(metrics.dp(370f))
                        .height(settingsDp(60f, scale)),
                    scale = scale,
                    backdrop = backdrop,
                    liveGlassEnabled = effectiveLiveGlassEnabled,
                    selectedTabIndex = 2,
                    hasUnreadAppNotifications = state.hasUnreadAppNotifications,
                    isAndroidUpdateAvailable = state.isAndroidUpdateAvailable,
                    onTabSelected = { index ->
                        viewModel.openTopLevelScreen(
                            PrimaryNavigationPolicy.destinationForTab(index),
                        )
                    },
                )
            }

            if (state.dialog == AppDialog.Logout) {
                SettingsLogoutConfirmDialog(
                    language = language,
                    scale = scale,
                    backdrop = modalBackdrop,
                    liveGlassEnabled = effectiveLiveGlassEnabled,
                    onDismiss = viewModel::dismissDialog,
                    onConfirm = onLogoutConfirmed,
                )
            }
            if (state.dialog == AppDialog.AccessDenied) {
                SettingsAccessDeniedDialog(
                    language = language,
                    scale = scale,
                    backdrop = modalBackdrop,
                    liveGlassEnabled = effectiveLiveGlassEnabled,
                    onDismiss = viewModel::dismissDialog,
                )
            }
        }
    }
}

@Composable
internal fun SettingsAccessDeniedDialog(
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(settingsDp(20f, scale))
        Column(
            modifier = Modifier
                .width(settingsDp(330f, scale))
                .clip(shape)
                .then(
                    if (liveGlassEnabled && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                colorControls(
                                    saturation = 1.5f,
                                    contrast = 1f,
                                    brightness = -0.1f,
                                )
                                blur(settingsDp(NokiUiKitPolicy.settingsDialogGlassBlurRadiusDp, scale).toPx())
                                lens(
                                    settingsDp(8f, scale).toPx(),
                                    settingsDp(10f, scale).toPx(),
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.22f)
                            },
                            onDrawSurface = {
                                drawRect(SettingsBgLighter.copy(alpha = 0.42f))
                            },
                        )
                    } else {
                        Modifier.background(
                            glassSurfaceColor(SettingsBgLighter.copy(alpha = 0.42f), liveGlassEnabled),
                            shape,
                        )
                    },
                )
                .then(
                    if (liveGlassEnabled) {
                        Modifier.background(SettingsBgLighter.copy(alpha = 0.42f), shape)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (liveGlassEnabled) {
                        Modifier.border(BorderStroke(1.dp, SettingsStroke.copy(alpha = 0.9f)), shape)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(
                    horizontal = settingsDp(20f, scale),
                    vertical = settingsDp(20f, scale),
                ),
            verticalArrangement = Arrangement.spacedBy(settingsDp(18f, scale)),
            horizontalAlignment = Alignment.Start,
        ) {
            SettingsText(
                text = tr(language, "Нет доступа", "No access"),
                fontSize = 18f,
                lineHeight = 21.6f,
                letterSpacing = 0.18f,
                color = SettingsTextPrimary,
                fontWeight = FontWeight.SemiBold,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsText(
                text = tr(
                    language,
                    "На данном устройстве нет доступа к этому разделу.",
                    "This device does not have access to this section.",
                ),
                fontSize = 12f,
                lineHeight = 16f,
                letterSpacing = 0.12f,
                color = SettingsTextSecondary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            SettingsDialogButton(
                text = tr(language, "Понятно", "OK"),
                scale = scale,
                isDanger = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}
