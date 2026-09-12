package com.noki.vpn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun SecurityPanelSurface(
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    content: @Composable () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "SecurityPanelButtonScale",
    )
    val shape = RoundedCornerShape(metrics.dp(24f))
    Box(
        modifier = modifier
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = metrics.contentScale,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                surfaceColor = SecurityBgLighter.copy(alpha = 0.80f),
                blurAndLensEnabled = false,
                exportedBackdrop = exportedBackdrop,
                layerBlock = {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            )
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
internal fun SecurityLiquidToggle(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier = Modifier,
    sizeFactor: Float = 1f,
) {
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val trackWidth = 60f
    val trackHeight = 28f
    val thumbPadding = 4f
    val thumbHeight = trackHeight - thumbPadding * 2f
    val thumbWidth = thumbHeight
    val idleDragWidth = with(density) { ((trackWidth - thumbWidth - thumbPadding * 2f) * sizeFactor).dp.toPx() }
    val freeDragWidth = with(density) { ((trackWidth - thumbWidth) * sizeFactor).dp.toPx() }
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.82f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    onSelectedChange(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (selected) 0f else 1f
                    onSelectedChange(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / idleDragWidth
                fraction = if (isLtr) {
                    (fraction + delta).coerceIn(0f, 1f)
                } else {
                    (fraction - delta).coerceIn(0f, 1f)
                }
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    LaunchedEffect(selected) {
        val target = if (selected) 1f else 0f
        if (target != fraction) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val trackShape = RoundedCornerShape(percent = 50)
    val activeTrackColor = SecurityAccent.copy(alpha = 0.68f)
    val inactiveTrackColor = lerpColor(SecurityBgLighter, SecurityBgSoft, 0.65f)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .width((trackWidth * sizeFactor).dp)
            .height((trackHeight * sizeFactor).dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                val next = !selected
                fraction = if (next) 1f else 0f
                onSelectedChange(next)
                dampedDragAnimation.animateToValue(fraction)
            }
            .semantics { role = Role.Switch },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .then(if (liveGlassEnabled) Modifier.layerBackdrop(trackBackdrop) else Modifier)
                .clip(trackShape)
                .drawBehind {
                    drawRect(
                        lerpColor(inactiveTrackColor, activeTrackColor, dampedDragAnimation.value),
                    )
                }
                .size((trackWidth * sizeFactor).dp, (trackHeight * sizeFactor).dp),
        )
        val thumbModifier = Modifier
            .graphicsLayer {
                val padding = (lerpFloat(thumbPadding, 0f, dampedDragAnimation.pressProgress) * sizeFactor).dp.toPx()
                val travel = lerpFloat(idleDragWidth, freeDragWidth, dampedDragAnimation.pressProgress)
                val value = dampedDragAnimation.value
                translationX = if (isLtr) {
                    lerpFloat(padding, padding + travel, value)
                } else {
                    lerpFloat(-padding, -(padding + travel), value)
                }
            }
            .then(dampedDragAnimation.modifier)
            .size((thumbWidth * sizeFactor).dp, (thumbHeight * sizeFactor).dp)
        NokiLiquidThumb(
            modifier = thumbModifier,
            animation = dampedDragAnimation,
            backdrop = backdrop,
            trackBackdrop = trackBackdrop,
            liveGlassEnabled = liveGlassEnabled,
            inactiveColor = SecurityTextMuted,
            activeColor = Color.White,
            sizeFactor = sizeFactor,
        )
    }
}

@Composable
internal fun SecurityText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    letterSpacing: Float,
    fontWeight: FontWeight,
    modifier: Modifier,
    color: Color = SecurityTextPrimary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val contentScale = nokiAdaptiveMetrics(configuration.screenWidthDp.dp).contentScale
    Text(
        text = text,
        color = color,
        fontFamily = ManropeFontFamily,
        fontWeight = fontWeight,
        fontSize = (fontSize * contentScale / density.fontScale).sp,
        lineHeight = (lineHeight * contentScale / density.fontScale).sp,
        letterSpacing = (letterSpacing / density.fontScale).sp,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}

internal fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}
