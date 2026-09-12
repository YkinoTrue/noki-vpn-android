package com.noki.vpn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noki.vpn.SettingsPreparedState
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

@Composable
internal fun SettingsProfileCardAdaptive(
    preparedState: SettingsPreparedState,
    liveGlassEnabled: Boolean,
    scale: Float,
    modifier: Modifier,
    onPlanClicked: () -> Unit,
) {
    val shape = RoundedCornerShape(settingsDp(20f, scale))
    val planBadgeBackdrop = rememberLayerBackdrop()
    val profileGradientColor = SettingsTextMuted

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .then(
                    if (liveGlassEnabled) Modifier.layerBackdrop(planBadgeBackdrop) else Modifier,
                )
                .background(SettingsBgLighter),
        ) {
            SettingsProfileGradientLayer(
                profileGradientColor = profileGradientColor,
                scale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = settingsDp(20f, scale), vertical = settingsDp(20f, scale)),
            horizontalArrangement = Arrangement.spacedBy(settingsDp(16f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                avatarUri = preparedState.avatarUri,
                size = settingsDp(80f, scale),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(settingsDp(1f, scale), Alignment.CenterVertically),
                horizontalAlignment = Alignment.Start,
            ) {
                SettingsText(
                    text = preparedState.username,
                    fontSize = 18f,
                    lineHeight = 20f,
                    letterSpacing = 0.18f,
                    color = SettingsTextPrimary,
                    scale = scale,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsText(
                    text = preparedState.email,
                    fontSize = 11f,
                    lineHeight = 14f,
                    letterSpacing = 0.11f,
                    color = SettingsTextSecondary,
                    scale = scale,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsPlanBadgeAdaptive(
                title = preparedState.planTitle,
                liveGlassEnabled = liveGlassEnabled,
                backdrop = planBadgeBackdrop,
                scale = scale,
                onClick = onPlanClicked,
            )
        }
    }
}

@Composable
internal fun SettingsPlanBadgeAdaptive(
    title: String,
    liveGlassEnabled: Boolean,
    backdrop: LayerBackdrop?,
    scale: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(settingsDp(12f, scale))
    val shadowAlpha = if (liveGlassEnabled) 0.28f else NokiUiKitPolicy.simpleSurfaceShadowAlpha
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val badgeScale by animateFloatAsState(
        targetValue = if (pressed) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "SettingsPlanBadgeAdaptiveScale",
    )

    Box(
        modifier = Modifier
            .width(settingsDp(85f, scale))
            .height(settingsDp(50f, scale))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!liveGlassEnabled || backdrop == null) {
                        Modifier.graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                        }
                    } else {
                        Modifier
                    },
                )
                .shadow(
                    elevation = settingsDp(10f, scale),
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha),
                )
                .then(
                    if (liveGlassEnabled && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                colorControls(
                                    saturation = 1.5f,
                                    contrast = 1f,
                                    brightness = 0.1f,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.35f)
                            },
                            shadow = {
                                Shadow(alpha = 0.25f)
                            },
                            innerShadow = {
                                InnerShadow(
                                    radius = settingsDp(8f, scale),
                                    alpha = 0.42f,
                                )
                            },
                            layerBlock = {
                                scaleX = badgeScale
                                scaleY = badgeScale
                            },
                        )
                    } else {
                        Modifier
                            .clip(shape)
                            .background(
                                if (liveGlassEnabled) {
                                    SettingsBgSoft.copy(alpha = 0.2f)
                                } else {
                                    SettingsBgSoft.copy(alpha = 0.3f)
                                },
                                shape,
                            )
                            .then(
                                if (liveGlassEnabled) {
                                    Modifier.border(BorderStroke(1.dp, SettingsStroke), shape)
                                } else {
                                    Modifier
                                },
                            )
                    },
                )
                .padding(settingsDp(5f, scale)),
        )
        SettingsText(
            text = title,
            fontSize = settingsPlanBadgeFontSize(title),
            lineHeight = 14f,
            letterSpacing = 0.18f,
            color = SettingsTextPrimary,
            scale = scale,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(settingsDp(75f, scale)),
        )
    }
}

@Composable
internal fun SettingsTrafficCardAdaptive(
    presentation: SettingsTrafficPresentation,
    scale: Float,
    modifier: Modifier,
    onPlanClicked: () -> Unit,
    onStatsClicked: () -> Unit,
) {
    val shape = RoundedCornerShape(settingsDp(20f, scale))
    when (presentation) {
        is SettingsTrafficPresentation.FreeUpgrade -> Column(
            modifier = modifier
                .clip(shape)
                .background(SettingsBgLighter)
                .padding(settingsDp(20f, scale)),
            verticalArrangement = Arrangement.spacedBy(settingsDp(28f, scale)),
        ) {
            SettingsText(
                text = presentation.usageLabel,
                fontSize = 16f,
                lineHeight = 18f,
                letterSpacing = 0.16f,
                color = SettingsTextPrimary,
                scale = scale,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(settingsDp(50f, scale))
                    .clip(RoundedCornerShape(settingsDp(12f, scale)))
                    .background(SettingsError)
                    .clickable(onClick = onPlanClicked),
                contentAlignment = Alignment.Center,
            ) {
                SettingsText(
                    text = presentation.actionLabel,
                    fontSize = 18f,
                    lineHeight = 20f,
                    letterSpacing = 0.18f,
                    color = SettingsTextPrimary,
                    scale = scale,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is SettingsTrafficPresentation.PaidStats -> Column(
            modifier = modifier
                .clip(shape)
                .background(SettingsBgLighter)
                .clickable(onClick = onStatsClicked)
                .padding(
                    horizontal = settingsDp(20f, scale),
                    vertical = settingsDp(17f, scale),
                ),
            verticalArrangement = Arrangement.spacedBy(settingsDp(8f, scale)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                SettingsText(
                    text = presentation.title,
                    fontSize = 14f,
                    lineHeight = 16f,
                    letterSpacing = 0.14f,
                    color = SettingsTextPrimary,
                    scale = scale,
                    modifier = Modifier,
                )
                SettingsText(
                    text = presentation.detailsLabel,
                    fontSize = 10f,
                    lineHeight = 12f,
                    letterSpacing = 0.1f,
                    color = SettingsTextSecondary,
                    scale = scale,
                    modifier = Modifier,
                )
            }
            SettingsTrafficMetricRow(
                value = presentation.todayLabel,
                caption = presentation.todayCaption,
                valueFontSize = 18f,
                valueLineHeight = 20f,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SettingsStroke),
            )
            SettingsTrafficMetricRow(
                value = presentation.lastThirtyDaysLabel,
                caption = presentation.lastThirtyDaysCaption,
                valueFontSize = 16f,
                valueLineHeight = 18f,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsTrafficMetricRow(
    value: String,
    caption: String,
    valueFontSize: Float,
    valueLineHeight: Float,
    scale: Float,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsText(
            text = value,
            fontSize = valueFontSize,
            lineHeight = valueLineHeight,
            letterSpacing = valueFontSize * 0.01f,
            color = SettingsTextPrimary,
            scale = scale,
            modifier = Modifier,
            maxLines = 1,
        )
        SettingsText(
            text = caption,
            fontSize = 10f,
            lineHeight = 12f,
            letterSpacing = 0.1f,
            color = SettingsTextSecondary,
            scale = scale,
            modifier = Modifier,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SettingsMenuRowAdaptive(
    @DrawableRes iconRes: Int,
    iconWidth: Float,
    iconHeight: Float,
    title: String,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
    modifier: Modifier,
    showBadge: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val animationScope = rememberCoroutineScope()
    val rowScale = remember { Animatable(1f) }
    val shape = RoundedCornerShape(settingsDp(20f, scale))
    val shadowAlpha = if (liveGlassEnabled) 0.05f else NokiUiKitPolicy.simpleSurfaceShadowAlpha
    fun runClickAnimation(then: () -> Unit) {
        animationScope.launch {
            rowScale.stop()
            rowScale.snapTo(1f)
            rowScale.animateTo(
                targetValue = 1.08f,
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 900f,
                ),
            )
            then()
            rowScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }
    Row(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = scale,
                shadowAlpha = shadowAlpha,
                chromaticAberration = false,
                blurAndLensEnabled = false,
                backdropShadowsEnabled = false,
                dropShadowRadiusDp = 4f,
                layerBlock = {
                    scaleX = rowScale.value
                    scaleY = rowScale.value
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { runClickAnimation(onClick) },
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = settingsDp(20f, scale)),
        horizontalArrangement = Arrangement.spacedBy(settingsDp(15f, scale)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .width(settingsDp(iconWidth, scale))
                .height(settingsDp(iconHeight, scale)),
        )
        SettingsText(
            text = title,
            fontSize = 16f,
            lineHeight = 19.2f,
            letterSpacing = 0.16f,
            color = SettingsTextPrimary,
            scale = scale,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        if (showBadge) {
            Box(
                modifier = Modifier
                    .padding(end = settingsDp(10f, scale))
                    .size(settingsDp(24f, scale)),
                contentAlignment = Alignment.Center,
            ) {
                SettingsUpdateBadgeIndicator()
            }
        }
    }
}

@Composable
private fun SettingsUpdateBadgeIndicator() {
    val transition = rememberInfiniteTransition(label = "settingsUpdateBadgePulse")
    val pulseProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "settingsUpdateBadgePulseProgress",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val baseRadius = size.minDimension * 0.29f
        val maxRadius = size.minDimension * 0.5f

        fun drawPulse(phase: Float) {
            val radius = baseRadius + (maxRadius - baseRadius) * phase
            val alpha = (1f - phase).coerceIn(0f, 1f) * 0.22f
            if (alpha > 0.01f) {
                drawCircle(
                    color = SettingsError.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                )
            }
        }

        drawPulse(pulseProgress)
        drawPulse((pulseProgress + 0.5f) % 1f)
        drawCircle(
            color = SettingsError.copy(alpha = 0.18f),
            radius = size.minDimension * 0.38f,
            center = center,
        )
        drawCircle(
            color = SettingsError,
            radius = baseRadius,
            center = center,
        )
    }
}

@Composable
internal fun SettingsProfileGradientLayer(
    profileGradientColor: Color,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to SettingsBgLighter.copy(alpha = 0f),
                            0.18f to profileGradientColor.copy(alpha = 0.012f),
                            0.35f to profileGradientColor.copy(alpha = 0.042f),
                            0.50f to profileGradientColor.copy(alpha = 0.096f),
                            0.66f to profileGradientColor.copy(alpha = 0.192f),
                            0.82f to profileGradientColor.copy(alpha = 0.36f),
                            1f to profileGradientColor.copy(alpha = 0.6f),
                        ),
                    ),
                ),
        )
    }
}
