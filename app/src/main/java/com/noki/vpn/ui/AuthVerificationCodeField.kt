package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop

@Composable
internal fun AuthVerificationCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    buttonText: String,
    showButton: Boolean,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: Boolean = true,
    buttonEnabled: Boolean = true,
    buttonLoading: Boolean = false,
    backdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = false,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val buttonInteractionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(metrics.dp(20f))
    val buttonShape = RoundedCornerShape(metrics.dp(14f))
    val buttonClickAnimator = rememberAuthButtonClickAnimator()
    val font14 = authSp(14f)
    val font18 = authSp(18f)
    val buttonFontSize = if (buttonText.contains("(") || buttonText.length > 12) authSp(10f) else font14
    val line12 = authSp(12f)

    Box(
        modifier = modifier
            .background(NokiBgLighter, shape)
            .border(1.dp, NokiStroke, shape),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = inputEnabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = metrics.dp(19f), end = if (showButton) metrics.dp(130f) else metrics.dp(19f)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            interactionSource = interactionSource,
            cursorBrush = SolidColor(NokiAccentStrong),
            textStyle = TextStyle(
                color = NokiTextPrimary,
                fontSize = font18,
                lineHeight = line12,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = authSp(0.18f),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = NokiTextMuted,
                            fontSize = font14,
                            lineHeight = line12,
                            fontFamily = ManropeFontFamily,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = authSp(0.14f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (showButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = metrics.dp(6f), end = metrics.dp(6f), bottom = metrics.dp(6f))
                    .width(metrics.dp(105f))
                    .fillMaxHeight()
                    .nokiGlassSurface(
                        shape = buttonShape,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        surfaceColor = authButtonSurfaceColor(NokiBgSoft),
                        layerBlock = {
                            scaleX = buttonClickAnimator.scale.value
                            scaleY = buttonClickAnimator.scale.value
                        },
                    )
                    .clickable(
                        enabled = !buttonLoading && buttonEnabled,
                        interactionSource = buttonInteractionSource,
                        indication = null,
                        onClick = {
                            buttonClickAnimator.runClickAnimation(
                                enabled = !buttonLoading && buttonEnabled,
                                then = onButtonClick,
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (buttonLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(metrics.dp(16f)),
                        strokeWidth = metrics.dp(2f),
                        color = NokiTextPrimary,
                    )
                } else {
                    Text(
                        text = buttonText,
                        modifier = Modifier.padding(horizontal = metrics.dp(4f)),
                        color = if (buttonEnabled) NokiTextPrimary else NokiTextMuted,
                        fontSize = buttonFontSize,
                        lineHeight = line12,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = authSp(0.14f),
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                }
            }
        }
    }
}
