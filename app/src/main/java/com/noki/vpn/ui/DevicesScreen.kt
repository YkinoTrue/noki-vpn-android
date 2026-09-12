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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.noki.vpn.AppDialog
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DeviceSession
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val DevicesBgBase = Color(0xFF07111A)
internal val DevicesBgLighter = Color(0xFF0D1B2A)
internal val DevicesBgSoft = Color(0xFF132635)
internal val DevicesTextPrimary = Color(0xFFF4FBFF)
internal val DevicesTextSecondary = Color(0xFF9FB6C5)
internal val DevicesTextMuted = Color(0xFF6E8797)
internal val DevicesAccentPrimary = Color(0xFF7AE7C7)
internal val DevicesAccentSecondary = Color(0xFF8CC8FF)
internal val DevicesStroke = Color(0xFF29404E)
internal val DevicesError = Color(0xFFFF6B6B)
internal val DevicesNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun DevicesScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    showBackground: Boolean = true,
    sharedBackdrop: LayerBackdrop,
    deviceRowsBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = true,
) {
    CompositionLocalProvider(LocalTextStyle provides DevicesNoFontPaddingTextStyle) {
        val hasDeviceModal = state.dialog == AppDialog.LogoutOthers ||
            state.dialog is AppDialog.RemoveDevice ||
            state.dialog is AppDialog.RenameDevice ||
            !state.inviteDeviceForm.generatedInviteCode.isNullOrBlank() ||
            state.incyDevices.isCreateDialogVisible ||
            state.incyDevices.isManageDialogVisible
        val modalBackdrop = if (liveGlassEnabled && hasDeviceModal) {
            rememberLayerBackdrop {
                drawRect(DevicesBgBase)
                drawContent()
            }
        } else {
            null
        }
        val backdrop = if (showBackground || liveGlassEnabled) sharedBackdrop else null
        val cardBackdrop = deviceRowsBackdrop ?: backdrop
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(DevicesBgBase) else Modifier)
                .then(
                    if (showBackground && backdrop != null) {
                        Modifier.layerBackdrop(backdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val scale = metrics.contentScale
            val language = state.personalizationSettings.language
            val deviceLimit = deviceLimit(state)
            val devices = state.devices
            val currentDevice = devices.firstOrNull { it.isCurrent } ?: devices.firstOrNull()
            val otherDevices = devices.filterNot { currentDevice != null && it.id == currentDevice.id }
            val connectedCount = devices.count { it.isActive } +
                state.incyDevices.devices.count { it.isSlotActive && !it.status.equals("revoked", true) }

            LaunchedEffect(state.isAuthenticated) {
                if (state.isAuthenticated) viewModel.refreshIncyDevices()
            }

            if (showBackground) {
                HomeBackground(liveGlassEnabled = liveGlassEnabled)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .then(
                        if (liveGlassEnabled && modalBackdrop != null) {
                            Modifier.layerBackdrop(modalBackdrop)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 21.dp),
                contentPadding = PaddingValues(top = 58.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 370.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(15.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DevicesHeader(
                                    connectedCount = connectedCount,
                                    deviceLimit = deviceLimit,
                                    language = language,
                                    scale = scale,
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
                                ) {
                                    DevicesText(
                                        text = tr(language, "Текущее устройство", "Current device"),
                                        fontSize = 12f,
                                        lineHeight = 14.4f,
                                        color = DevicesTextSecondary,
                                        scale = scale,
                                        modifier = Modifier.padding(horizontal = devicesDp(18f, scale)),
                                    )
                                    CurrentDeviceCard(
                                        device = currentDevice,
                                        language = language,
                                        backdrop = cardBackdrop,
                                        liveGlassEnabled = liveGlassEnabled,
                                        scale = scale,
                                        onRenameClick = { currentDevice?.id?.let(viewModel::requestRenameDevice) },
                                    )
                                }
                            }
                            RemoveAllDevicesButton(
                                language = language,
                                scale = scale,
                                backdrop = cardBackdrop,
                                liveGlassEnabled = liveGlassEnabled,
                                onClick = viewModel::requestLogoutOthers,
                            )
                        }

                        OtherDevicesSection(
                            devices = otherDevices,
                            inviteIsLoading = state.inviteDeviceForm.isLoading,
                            inviteError = state.inviteDeviceForm.error,
                            language = language,
                            scale = scale,
                            onInviteClick = viewModel::createDeviceInvite,
                            backdrop = cardBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onDeviceClick = viewModel::requestRemoveDevice,
                        )

                        IncyDevicesSection(
                            state = state.incyDevices,
                            language = language,
                            scale = scale,
                            backdrop = cardBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onAddClick = viewModel::showCreateIncyDevice,
                            onDeviceClick = viewModel::openIncyDevice,
                            onRetryClick = viewModel::refreshIncyDevices,
                        )

                        AdditionalDeviceInfo(
                            remainingDevices = (deviceLimit - connectedCount).coerceAtLeast(0),
                            language = language,
                            scale = scale,
                        )
                    }
                }
            }

            if (state.dialog == AppDialog.LogoutOthers) {
                DevicesConfirmDialog(
                    title = tr(language, "Удалить остальные устройства?", "Remove other devices?"),
                    message = tr(
                        language,
                        "Текущее устройство останется в аккаунте. Все остальные будут отвязаны и выйдут из профиля после следующей проверки сессии.",
                        "The current device will remain signed in. All other devices will be unlinked and signed out after their next session check.",
                    ),
                    confirmText = tr(language, "Удалить", "Remove"),
                    dismissText = tr(language, "Отмена", "Cancel"),
                    backdrop = modalBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    scale = scale,
                    onDismiss = viewModel::dismissDialog,
                    onConfirm = viewModel::confirmDialog,
                )
            }

            (state.dialog as? AppDialog.RemoveDevice)?.let { dialog ->
                val device = state.devices.firstOrNull { it.id == dialog.deviceId }
                DeleteDeviceDialog(
                    device = device,
                    language = language,
                    scale = scale,
                    backdrop = modalBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = viewModel::dismissDialog,
                    onFullAccessChanged = viewModel::setDeviceFullAccess,
                    onRename = { viewModel.requestRenameDevice(dialog.deviceId) },
                    onConfirm = viewModel::confirmDialog,
                )
            }

            (state.dialog as? AppDialog.RenameDevice)?.let { dialog ->
                val device = state.devices.firstOrNull { it.id == dialog.deviceId }
                var name by remember(dialog.deviceId) { mutableStateOf(device?.title.orEmpty()) }
                SettingsCompactInputDialog(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = tr(language, "Название устройства", "Device name"),
                    dismissText = tr(language, "Отменить", "Cancel"),
                    confirmText = tr(language, "Сохранить", "Save"),
                    scale = scale,
                    backdrop = modalBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    maxLength = 80,
                    onDismiss = viewModel::dismissDialog,
                    onConfirm = { viewModel.renameDevice(dialog.deviceId, name) },
                )
            }

            state.inviteDeviceForm.generatedInviteCode?.takeIf { it.isNotBlank() }?.let { inviteCode ->
                DeviceInviteDialog(
                    inviteCode = inviteCode,
                    expiresAt = state.inviteDeviceForm.generatedInviteExpiresAt,
                    language = language,
                    scale = scale,
                    backdrop = modalBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = viewModel::dismissGeneratedDeviceInvite,
                )
            }

            IncyDeviceDialogs(
                state = state.incyDevices,
                language = language,
                scale = scale,
                backdrop = modalBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onNameChanged = viewModel::updateIncyDeviceName,
                onCreate = viewModel::createIncyDevice,
                onRename = viewModel::renameIncyDevice,
                onReissue = viewModel::reissueIncyDevice,
                onDelete = viewModel::deleteIncyDevice,
                onDismiss = viewModel::dismissIncyDeviceDialog,
            )
        }
    }
}

@Composable
private fun DevicesHeader(
    connectedCount: Int,
    deviceLimit: Int,
    language: AppLanguage,
    scale: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(devicesDp(3f, scale), Alignment.CenterVertically),
            horizontalAlignment = Alignment.Start,
        ) {
            DevicesText(
                text = tr(language, "Устройства", "Devices"),
                fontSize = 24f,
                lineHeight = 28.8f,
                color = DevicesTextPrimary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
            DevicesText(
                text = tr(
                    language,
                    "Подключено $connectedCount из $deviceLimit устройств",
                    "$connectedCount of $deviceLimit devices connected",
                ),
                fontSize = 12f,
                lineHeight = 14.4f,
                color = DevicesTextSecondary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(devicesDp(81f, scale))
                .height(devicesDp(28f, scale))
                .clip(RoundedCornerShape(devicesDp(14f, scale)))
                .background(DevicesBgSoft)
                .border(
                    BorderStroke(1.dp, DevicesStroke.copy(alpha = 0.65f)),
                    RoundedCornerShape(devicesDp(14f, scale)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            DevicesText(
                text = tr(language, "Лимит $deviceLimit", "Limit $deviceLimit"),
                fontSize = 11f,
                lineHeight = 13.2f,
                color = DevicesAccentSecondary,
                scale = scale,
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun CurrentDeviceCard(
    device: DeviceSession?,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
    onRenameClick: () -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(24f, scale))
    val isActive = device?.isActive ?: true
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(devicesDp(80f, scale))
            .then(
                if (!isActive) {
                    Modifier
                        .clip(shape)
                        .background(DevicesTextMuted.copy(alpha = 0.5f), shape)
                } else {
                    Modifier.nokiSettingsPanelGlassSurface(
                        shape = shape,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = scale,
                        elevationDp = 8f,
                        shadowAlpha = 0.25f,
                        surfaceColor = DevicesBgLighter.copy(alpha = 0.80f),
                    )
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = device != null,
                onClick = onRenameClick,
            )
            .padding(horizontal = devicesDp(20f, scale)),
        contentAlignment = Alignment.CenterStart,
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceIconTile(
                    platformHint = device?.subtitle.orEmpty(),
                    scale = scale,
                )
                DeviceInfo(
                    title = device?.title ?: tr(language, "Android устройство", "Android device"),
                    subtitle = devicePlatformLabel(device?.subtitle) ?: tr(language, "Android", "Android"),
                    titleFontSize = 18f,
                    scale = scale,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.personalization_avatar_edit_icon),
                    contentDescription = tr(language, "Переименовать", "Rename"),
                    tint = DevicesTextPrimary,
                    modifier = Modifier.size(devicesDp(16f, scale)),
                )
            }
    }
}
