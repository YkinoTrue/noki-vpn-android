package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

private val GlassSegmentStroke = Color(0xFF29404E)

@Composable
internal fun GlassSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelectedIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color(0xFF0D1B2A).copy(alpha = 0.5f),
    activeSurfaceColor: Color = Color(0xFF132635).copy(alpha = 0.05f),
    strokeColor: Color = GlassSegmentStroke,
    selectedTextColor: Color = Color(0xFFF4FBFF),
    inactiveTextColor: Color = Color(0xFF9FB6C5),
    containerHeight: Dp = 60.dp,
    capsulePadding: Dp = 5.dp,
    activeBlur: Dp = 1.5.dp,
    lensRefractionHeight: Dp = 12.dp,
    lensRefractionAmount: Dp = 32.dp,
    chromaticAberration: Boolean = true,
    maxPressedScaleX: Float = 1.18f,
    liveGlassEnabled: Boolean = true,
    depthEffectEnabled: Boolean = true,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    labelLineHeight: TextUnit = TextUnit.Unspecified,
) {
    require(labels.size >= 2) { "GlassSegmentedControl requires at least two labels." }

    val clampedSelectedIndex = selectedIndex.coerceIn(labels.indices)
    val currentOnSelectedIndexChanged by rememberUpdatedState(onSelectedIndexChanged)
    val interactionSource = remember { MutableInteractionSource() }
    val textBackdrop = if (liveGlassEnabled) rememberLayerBackdrop() else null
    val animationScope = rememberCoroutineScope()
    val capsuleShape = RoundedCornerShape(percent = 50)

    BoxWithConstraints(
        modifier = modifier.height(containerHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabsCount = labels.size
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()
        val capsulePaddingPx = with(density) { capsulePadding.toPx() }
        val activeCapsuleWidthPx =
            (containerWidthPx - capsulePaddingPx * 2f) / tabsCount
        val activeCapsuleHeightPx =
            (containerHeightPx - capsulePaddingPx * 2f).coerceAtLeast(0f)
        val buttonTravelPx =
            containerWidthPx - capsulePaddingPx * 2f - activeCapsuleWidthPx
        val buttonStepPx = buttonTravelPx / (tabsCount - 1)
        val segmentCenterXs = List(tabsCount) { index ->
            capsulePaddingPx + activeCapsuleWidthPx / 2f + buttonStepPx * index
        }

        val dampedDragAnimation = remember(animationScope, buttonStepPx, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = clampedSelectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(labels.indices)
                    animateToValue(targetIndex.toFloat())
                    currentOnSelectedIndexChanged(targetIndex)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / buttonStepPx)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                },
            )
        }

        LaunchedEffect(clampedSelectedIndex, dampedDragAnimation) {
            if (dampedDragAnimation.targetValue.roundToInt() != clampedSelectedIndex) {
                dampedDragAnimation.animateToValue(clampedSelectedIndex.toFloat())
            }
        }

        fun selectIndex(targetIndex: Int) {
            if (!enabled) return
            val clampedIndex = targetIndex.coerceIn(labels.indices)
            dampedDragAnimation.animateToValue(clampedIndex.toFloat())
            currentOnSelectedIndexChanged(clampedIndex)
        }

        val activeCapsuleOffsetPx = dampedDragAnimation.value * buttonStepPx
        val activeCapsuleWidth = with(density) { activeCapsuleWidthPx.toDp() }
        val activeCapsuleHeight = with(density) { activeCapsuleHeightPx.toDp() }
        val segmentWidth = with(density) { activeCapsuleWidthPx.toDp() }
        val velocity = dampedDragAnimation.velocity / 10f
        val movingScaleX = 1f + (dampedDragAnimation.scaleX - 1f) * 0.34f
        val velocityScaleX =
            1f / (1f - (velocity * 0.18f).coerceIn(-0.05f, 0.05f))
        val desiredScaleX = (movingScaleX * velocityScaleX).coerceIn(1f, maxPressedScaleX)
        val scaledWidthPx = activeCapsuleWidthPx * desiredScaleX
        val scaledLeftBeforeClampPx = capsulePaddingPx +
            activeCapsuleOffsetPx +
            (activeCapsuleWidthPx - scaledWidthPx) / 2f
        val scaledRightBeforeClampPx = scaledLeftBeforeClampPx + scaledWidthPx
        val scaledTranslationXPx = when {
            scaledLeftBeforeClampPx < 0f -> -scaledLeftBeforeClampPx
            scaledRightBeforeClampPx > containerWidthPx -> containerWidthPx - scaledRightBeforeClampPx
            else -> 0f
        }
        val scaledLeftPx = scaledLeftBeforeClampPx + scaledTranslationXPx
        val scaledHeightPx = activeCapsuleHeightPx
        val scaledTopPx = ((containerHeightPx - scaledHeightPx) / 2f).coerceAtLeast(0f)
        val maxScaleY = containerHeightPx / activeCapsuleHeightPx
        val desiredScaleY = (1f + (dampedDragAnimation.scaleY - 1f) * 0.72f)
            .coerceAtMost(maxScaleY)
            .let { it * (1f - (velocity * 0.18f).coerceIn(-0.12f, 0.12f)) }
            .coerceAtMost(maxScaleY)

        @Composable
        fun LabelsContent(
            clickable: Boolean,
            overrideTextColor: Color? = null,
        ) {
            labels.forEachIndexed { index, label ->
                SegmentLabel(
                    label = label,
                    selected = index == clampedSelectedIndex,
                    textColor = overrideTextColor ?: if (index == clampedSelectedIndex) {
                        selectedTextColor
                    } else {
                        inactiveTextColor
                    },
                    fontSize = labelFontSize,
                    lineHeight = labelLineHeight,
                    modifier = Modifier
                        .width(segmentWidth)
                        .height(containerHeight)
                        .offset {
                            IntOffset(
                                x = (segmentCenterXs[index] - activeCapsuleWidthPx / 2f)
                                    .roundToInt(),
                                y = 0,
                            )
                        }
                        .then(
                            if (clickable && enabled) {
                                Modifier.clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { selectIndex(index) },
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(capsuleShape)
                .background(glassSurfaceColor(containerColor, liveGlassEnabled), capsuleShape),
        ) {
            if (liveGlassEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRoundRect(
                                color = Color.Transparent,
                                topLeft = Offset(scaledLeftPx, scaledTopPx),
                                size = Size(scaledWidthPx, scaledHeightPx),
                                cornerRadius = CornerRadius(scaledHeightPx / 2f, scaledHeightPx / 2f),
                                blendMode = BlendMode.Clear,
                            )
                        },
                ) {
                    LabelsContent(clickable = true)
                }
            }
        }

        if (liveGlassEnabled && textBackdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(capsuleShape)
                    .alpha(0f)
                    .layerBackdrop(textBackdrop),
            ) {
                LabelsContent(clickable = false)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(BorderStroke(1.dp, strokeColor), capsuleShape),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = capsulePadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = activeCapsuleOffsetPx.roundToInt(),
                            y = 0,
                        )
                    }
                    .width(activeCapsuleWidth)
                    .height(activeCapsuleHeight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liveGlassEnabled && textBackdrop != null) {
                                Modifier.drawBackdrop(
                                    backdrop = textBackdrop,
                                    shape = { capsuleShape },
                                    effects = {
                                        val progress = dampedDragAnimation.pressProgress
                                        blur(activeBlur.toPx() * progress)
                                        lens(
                                            refractionHeight = lensRefractionHeight.toPx() * progress,
                                            refractionAmount = lensRefractionAmount.toPx() * progress,
                                            depthEffect = depthEffectEnabled,
                                            chromaticAberration = chromaticAberration,
                                        )
                                    },
                                    highlight = {
                                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress * 0.55f)
                                    },
                                    shadow = {
                                        Shadow(alpha = dampedDragAnimation.pressProgress * 0.45f)
                                    },
                                    innerShadow = {
                                        val progress = dampedDragAnimation.pressProgress
                                        InnerShadow(
                                            radius = 6.dp * progress,
                                            alpha = progress * 0.75f,
                                        )
                                    },
                                    layerBlock = {
                                        translationX = scaledTranslationXPx
                                        scaleX = desiredScaleX
                                        scaleY = desiredScaleY
                                    },
                                    onDrawSurface = {
                                        drawRect(activeSurfaceColor)
                                    },
                                )
                            } else {
                                Modifier
                                    .graphicsLayer {
                                        translationX = scaledTranslationXPx
                                        scaleX = desiredScaleX
                                        scaleY = desiredScaleY
                                    }
                                    .background(
                                        glassSurfaceColor(
                                            activeSurfaceColor.copy(alpha = 0.34f),
                                            liveGlassEnabled,
                                        ),
                                        capsuleShape,
                                    )
                            },
                        )
                        .border(BorderStroke(1.dp, strokeColor), capsuleShape),
                )
            }
        }

        if (!liveGlassEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(capsuleShape),
            ) {
                LabelsContent(clickable = true)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = capsulePadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = activeCapsuleOffsetPx.roundToInt(),
                            y = 0,
                        )
                    }
                    .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                    .width(activeCapsuleWidth)
                    .height(activeCapsuleHeight),
            )
        }
    }
}

@Composable
private fun SegmentLabel(
    label: String,
    selected: Boolean,
    textColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}
