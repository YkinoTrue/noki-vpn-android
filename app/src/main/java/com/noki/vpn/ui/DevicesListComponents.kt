package com.noki.vpn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DeviceSession
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight

@Composable
internal fun OtherDevicesSection(
    devices: List<DeviceSession>,
    inviteIsLoading: Boolean,
    inviteError: String?,
    language: AppLanguage,
    scale: Float,
    onInviteClick: () -> Unit,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onDeviceClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
        horizontalAlignment = Alignment.Start,
    ) {
        DevicesText(
            text = tr(language, "Другие устройства", "Other devices"),
            fontSize = 12f,
            lineHeight = 14.4f,
            color = DevicesTextSecondary,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
        DeviceInviteCard(
            isLoading = inviteIsLoading,
            language = language,
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            onClick = onInviteClick,
        )
        if (!inviteError.isNullOrBlank()) {
            DevicesText(
                text = inviteError,
                fontSize = 11f,
                lineHeight = 13f,
                color = DevicesError,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (devices.isEmpty()) {
            EmptyDevicesCard(
                language = language,
                scale = scale,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    scale = scale,
                    onClick = { onDeviceClick(device.id) },
                )
            }
        }
    }
}

@Composable
internal fun DeviceRow(
    device: DeviceSession,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(24f, scale))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "DeviceRowPressScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(devicesDp(72f, scale))
            .then(
                if (!device.isActive) {
                    Modifier
                        .graphicsLayer {
                            scaleX = rowScale
                            scaleY = rowScale
                        }
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
                        layerBlock = {
                            scaleX = rowScale
                            scaleY = rowScale
                        },
                    )
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(start = devicesDp(20f, scale), end = devicesDp(30f, scale)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceIconTile(
                platformHint = device.subtitle,
                scale = scale,
            )
            DeviceInfo(
                title = device.title,
                subtitle = devicePlatformLabel(device.subtitle),
                titleFontSize = 18f,
                scale = scale,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DeleteDeviceDialog(
    device: DeviceSession?,
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
    onFullAccessChanged: (String, Boolean) -> Unit,
    onRename: () -> Unit,
    onConfirm: () -> Unit,
) {
    val density = LocalDensity.current
    val navigationBottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val bottomPadding = navigationBottomInset + devicesDp(90f, scale)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(devicesDp(24f, scale))
        val hasFullAccess = device?.accessRole?.equals("owner", ignoreCase = true) == true
        var fullAccessChecked by remember(device?.id, hasFullAccess) { mutableStateOf(hasFullAccess) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .padding(start = 21.dp, end = 21.dp, bottom = bottomPadding)
                .fillMaxWidth()
                .widthIn(max = 370.dp)
                .height(devicesDp(350f, scale))
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
                                blur(devicesDp(40f, scale).toPx())
                                lens(
                                    devicesDp(8f, scale).toPx(),
                                    devicesDp(10f, scale).toPx(),
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.22f)
                            },
                            onDrawSurface = {
                                drawRect(DevicesBgSoft.copy(alpha = 0.42f))
                            },
                        )
                    } else {
                        Modifier.background(
                            glassSurfaceColor(DevicesBgSoft.copy(alpha = 0.42f), liveGlassEnabled),
                            shape,
                        )
                    },
                )
                .then(
                    if (liveGlassEnabled) {
                        Modifier.background(DevicesBgSoft.copy(alpha = 0.42f), shape)
                    } else {
                        Modifier
                    },
                )
                .border(BorderStroke(1.dp, DevicesStroke.copy(alpha = 0.28f)), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            DeviceIconTile(
                platformHint = device?.subtitle.orEmpty(),
                scale = scale,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = devicesDp(25f, scale)),
                size = 55f,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = devicesDp(90f, scale))
                    .width(devicesDp(220f, scale)),
                verticalArrangement = Arrangement.spacedBy(devicesDp(1f, scale)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DevicesText(
                    text = device?.title ?: tr(language, "Устройство", "Device"),
                    fontSize = 18f,
                    lineHeight = 20f,
                    color = DevicesTextPrimary,
                    scale = scale,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                DevicesText(
                    text = devicePlatformLabel(device?.subtitle) ?: tr(language, "Android", "Android"),
                    fontSize = 12f,
                    lineHeight = 14f,
                    color = DevicesTextSecondary,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            DeleteDeviceButton(
                language = language,
                scale = scale,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = devicesDp(275f, scale)),
                onClick = onConfirm,
            )
            DevicesDialogButton(
                text = tr(language, "Переименовать", "Rename"),
                scale = scale,
                isPrimary = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = devicesDp(207f, scale))
                    .width(devicesDp(300f, scale))
                    .height(devicesDp(50f, scale)),
                onClick = onRename,
            )
            FullAccessOptionRow(
                checked = fullAccessChecked,
                enabled = device != null,
                language = language,
                scale = scale,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = devicesDp(145f, scale)),
                onClick = {
                    val targetDeviceId = device?.id
                    if (targetDeviceId != null) {
                        val nextValue = !fullAccessChecked
                        fullAccessChecked = nextValue
                        onFullAccessChanged(targetDeviceId, nextValue)
                    }
                },
            )
        }
    }
}

@Composable
internal fun FullAccessOptionRow(
    checked: Boolean,
    enabled: Boolean,
    language: AppLanguage,
    scale: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(18f, scale))
    Row(
        modifier = modifier
            .width(devicesDp(300f, scale))
            .height(devicesDp(44f, scale))
            .clip(shape)
            .background(DevicesBgSoft.copy(alpha = 0.36f), shape)
            .border(BorderStroke(1.dp, DevicesStroke.copy(alpha = 0.52f)), shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = devicesDp(15f, scale)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(devicesDp(12f, scale)),
    ) {
        AccessCheckbox(
            selected = checked,
            scale = scale,
        )
        DevicesText(
            text = tr(language, "Дать полный доступ", "Grant full access"),
            fontSize = 14f,
            lineHeight = 16.8f,
            color = if (enabled || checked) DevicesTextPrimary else DevicesTextMuted,
            scale = scale,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun AccessCheckbox(
    selected: Boolean,
    scale: Float,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(devicesDp(22f, scale))
            .clip(shape)
            .background(DevicesBgLighter, shape)
            .border(BorderStroke(1.dp, DevicesStroke), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(devicesDp(12f, scale))
                    .clip(shape)
                    .background(DevicesAccentPrimary, shape),
            )
        }
    }
}

@Composable
internal fun DeleteDeviceButton(
    language: AppLanguage,
    scale: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(18f, scale))
    val buttonColor = DevicesError
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(devicesDp(300f, scale))
            .height(devicesDp(50f, scale))
            .clip(shape)
            .background(buttonColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DevicesText(
            text = tr(language, "Удалить устройство", "Delete device"),
            fontSize = 18f,
            lineHeight = 19.2f,
            color = Color.White,
            scale = scale,
            fontWeight = FontWeight.Medium,
            modifier = Modifier,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun EmptyDevicesCard(
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
) {
    val shape = RoundedCornerShape(devicesDp(24f, scale))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(devicesDp(72f, scale))
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = scale,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                surfaceColor = DevicesBgLighter.copy(alpha = 0.80f),
            )
            .padding(horizontal = devicesDp(20f, scale)),
        contentAlignment = Alignment.CenterStart,
    ) {
        DevicesText(
            text = tr(language, "Других устройств пока нет", "No other devices yet"),
            fontSize = 14f,
            lineHeight = 16.8f,
            color = DevicesTextSecondary,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
