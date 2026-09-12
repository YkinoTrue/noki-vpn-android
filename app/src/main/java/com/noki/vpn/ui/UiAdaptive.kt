package com.noki.vpn.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class NokiAdaptiveMetrics(
    val contentStart: Dp,
    val contentWidth: Dp,
    val contentScale: Float,
)

internal fun nokiAdaptiveMetrics(
    screenWidth: Dp,
    sidePadding: Dp = 21.dp,
    maxContentWidth: Dp = 480.dp,
    designContentWidth: Dp = 370.dp,
): NokiAdaptiveMetrics {
    val available = (screenWidth - sidePadding * 2f).coerceAtLeast(0.dp)
    val contentWidth = available.coerceAtMost(maxContentWidth)
    return NokiAdaptiveMetrics(
        contentStart = (screenWidth - contentWidth) / 2f,
        contentWidth = contentWidth,
        contentScale = if (designContentWidth.value > 0f) {
            contentWidth.value / designContentWidth.value
        } else {
            1f
        },
    )
}

internal fun NokiAdaptiveMetrics.dp(value: Float): Dp = (value * contentScale).dp

internal fun NokiAdaptiveMetrics.sp(value: Float): TextUnit = (value * contentScale).sp

internal fun NokiAdaptiveMetrics.screenX(
    designX: Float,
    designSidePadding: Float = 21f,
): Dp = contentStart + ((designX - designSidePadding) * contentScale).dp
