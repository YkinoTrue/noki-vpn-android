package com.noki.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.getValue

@Composable
internal fun SecurityLoggingRow(
    loggingEnabled: Boolean,
    anonymousLogsEnabled: Boolean,
    isUploadingLogs: Boolean,
    logUploadMessage: String?,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    language: com.noki.vpn.data.AppLanguage,
    onLoggingEnabledChange: (Boolean) -> Unit,
    onAnonymousLogsChange: (Boolean) -> Unit,
    onUploadLocalLogs: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val expandedHeight = if (logUploadMessage.isNullOrBlank()) metrics.dp(190f) else metrics.dp(215f)
    val panelHeight by animateDpAsState(
        targetValue = if (loggingEnabled) expandedHeight else metrics.dp(60f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "SecurityLoggingPanelHeight",
    )
    val panelBackdrop = rememberLayerBackdrop()
    SecurityPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight),
        exportedBackdrop = panelBackdrop,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(60f))
                    .padding(horizontal = metrics.dp(20f)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecurityText(
                    text = tr(language, "Логирование", "Logging"),
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    letterSpacing = 0f,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = metrics.dp(12f)),
                )
                SecurityLiquidToggle(
                    sizeFactor = metrics.contentScale,
                    selected = loggingEnabled,
                    onSelectedChange = onLoggingEnabledChange,
                    backdrop = backdrop,
                    liveGlassEnabled = backdrop != null,
                )
            }

            AnimatedVisibility(
                visible = loggingEnabled,
                enter = fadeIn(tween(durationMillis = 120, delayMillis = 45, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(durationMillis = 90, easing = FastOutSlowInEasing)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = metrics.dp(20f),
                            vertical = metrics.dp(20f),
                        ),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(12f)),
                ) {
                    SecurityLogOption(
                        title = tr(language, "Автоматическая отправка", "Automatic sending"),
                        subtitle = tr(
                            language,
                            "диагностические логи и данные устройства",
                            "diagnostic logs and device details",
                        ),
                        checked = anonymousLogsEnabled,
                        enabled = loggingEnabled,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onCheckedChange = onAnonymousLogsChange,
                    )
                    SecurityLogSendButton(
                        text = if (isUploadingLogs) {
                            tr(language, "Отправка…", "Sending…")
                        } else {
                            tr(language, "Отправить локальные логи", "Send local logs")
                        },
                        enabled = loggingEnabled && !isUploadingLogs,
                        backdrop = panelBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onClick = onUploadLocalLogs,
                    )
                    logUploadMessage?.takeIf { it.isNotBlank() }?.let { message ->
                        SecurityText(
                            text = message,
                            color = SecurityTextSecondary.copy(alpha = 0.78f),
                            fontSize = 10.5f,
                            lineHeight = 12.6f,
                            letterSpacing = 0f,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SecurityLogOption(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(36f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = metrics.dp(12f)),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(2f)),
        ) {
            SecurityText(
                text = title,
                color = SecurityTextPrimary.copy(alpha = if (enabled) 1f else 0.42f),
                fontSize = 14f,
                lineHeight = 16.8f,
                letterSpacing = 0f,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(),
            )
            SecurityText(
                text = subtitle,
                color = SecurityTextSecondary.copy(alpha = if (enabled) 0.72f else 0.32f),
                fontSize = 10.5f,
                lineHeight = 12.6f,
                letterSpacing = 0f,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SecurityLiquidToggle(
            selected = checked && enabled,
            onSelectedChange = { if (enabled) onCheckedChange(it) },
            backdrop = backdrop,
            liveGlassEnabled = backdrop != null && enabled,
            sizeFactor = 0.86f * metrics.contentScale,
            modifier = Modifier.graphicsLayer {
                alpha = if (enabled) 1f else 0.42f
            },
        )
    }
}

@Composable
internal fun SecurityLogSendButton(
    text: String,
    enabled: Boolean,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "SecurityLogSendButtonScale",
    )
    val shape = RoundedCornerShape(metrics.dp(18f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(NokiUiKitPolicy.settingsInlineActionHeightDp))
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.44f
            }
            .nokiSettingsActionGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                surfaceColor = SecurityBgSoft.copy(alpha = 0.75f),
                blurAndLensEnabled = false,
                layerBlock = {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SecurityText(
            text = text,
            color = SecurityTextPrimary,
            fontSize = 13f,
            lineHeight = 15.6f,
            letterSpacing = 0f,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = metrics.dp(12f)),
        )
    }
}
