package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop

@Composable
internal fun AdvancedPanelSurface(
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    exportedBackdrop: LayerBackdrop? = null,
    cardBrightness: Float = -0.01f,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(24f))
    Box(
        modifier = modifier
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                exportedBackdrop = exportedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = metrics.contentScale,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                brightness = cardBrightness,
                surfaceColor = AdvancedBgLighter.copy(alpha = 0.80f),
                blurAndLensEnabled = false,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
internal fun AdvancedSmallButton(
    text: String,
    modifier: Modifier,
    fontSize: Float = 11f,
    cornerRadius: Dp = 16.dp,
    backdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = false,
    glassEnabled: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "advancedButtonPress",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(
                if (glassEnabled) {
                    Modifier.nokiSettingsActionGlassSurface(
                        shape = shape,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        surfaceColor = AdvancedBgSoft.copy(alpha = 0.75f),
                        blurAndLensEnabled = false,
                        layerBlock = {
                            scaleX = pressScale
                            scaleY = pressScale
                        },
                    )
                } else {
                    Modifier
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .shadow(
                            elevation = metrics.dp(8f),
                            shape = shape,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.28f),
                        )
                        .clip(shape)
                        .background(AdvancedBgSoft.copy(alpha = 0.75f), shape)
                        .border(BorderStroke(1.dp, AdvancedStroke.copy(alpha = 0.46f)), shape)
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AdvancedText(
            text = text,
            fontSize = fontSize,
            lineHeight = fontSize * 1.2f,
            color = AdvancedTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun AdvancedFittingText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    minFontSize: Float,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val contentScale = nokiAdaptiveMetrics(configuration.screenWidthDp.dp).contentScale
    val scaledFontSize = fontSize * contentScale / density.fontScale
    val scaledLineHeight = lineHeight * contentScale / density.fontScale
    val minRatio = (minFontSize / fontSize).coerceIn(0.1f, 1f)
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val baseStyle = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            fontFamily = ManropeFontFamily,
            fontWeight = fontWeight,
            fontSize = scaledFontSize.sp,
            lineHeight = scaledLineHeight.sp,
        )
        val measured = textMeasurer.measure(
            text = text,
            style = baseStyle,
            maxLines = 1,
        )
        val fitRatio = if (measured.size.width <= maxWidthPx) {
            1f
        } else {
            (maxWidthPx / measured.size.width).coerceIn(minRatio, 1f)
        }
        Text(
            text = text,
            color = color,
            fontFamily = ManropeFontFamily,
            fontWeight = fontWeight,
            fontSize = (scaledFontSize * fitRatio).sp,
            lineHeight = (scaledLineHeight * fitRatio).sp,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun AdvancedText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
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
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}
