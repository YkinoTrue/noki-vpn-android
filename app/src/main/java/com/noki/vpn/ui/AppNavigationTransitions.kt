package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import com.noki.vpn.AppDestination
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect

internal enum class SharedDestinationVisibility {
    Precomposing,
    Incoming,
    Outgoing,
    FullyVisible,
}

internal val LocalSharedDestinationVisibility = staticCompositionLocalOf {
    SharedDestinationVisibility.FullyVisible
}

@Composable
internal fun SharedDestinationHost(
    destination: AppDestination,
    modifier: Modifier = Modifier,
    useSlideTransition: (AppDestination, AppDestination) -> Boolean,
    useFadeTransition: (AppDestination, AppDestination) -> Boolean,
    content: @Composable (AppDestination) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        var previousDestination by remember { mutableStateOf(destination) }
        val startsFromSide =
            previousDestination != destination &&
                useSlideTransition(previousDestination, destination) &&
                widthPx > 0f
        val fadesIn =
            previousDestination != destination &&
                !startsFromSide &&
                useFadeTransition(previousDestination, destination)
        val direction = if (destination.navigationOrder() > previousDestination.navigationOrder()) 1f else -1f
        val offsetX = remember(destination, widthPx) {
            Animatable(if (startsFromSide) widthPx * direction else 0f)
        }
        val alpha = remember(destination) {
            Animatable(if (fadesIn) 0f else 1f)
        }

        LaunchedEffect(destination, widthPx) {
            if (startsFromSide) {
                alpha.snapTo(1f)
                offsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 138, easing = FastOutSlowInEasing),
                )
            } else {
                offsetX.snapTo(0f)
                if (fadesIn) {
                    alpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 96, easing = FastOutSlowInEasing),
                    )
                } else {
                    alpha.snapTo(1f)
                }
            }
            previousDestination = destination
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                    this.alpha = alpha.value
                },
        ) {
            content(destination)
        }
    }
}

private fun DrawScope.drawProgressiveBottomDim() {
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0f),
                0.30f to Color.Black.copy(alpha = 0.20f),
                1f to Color.Black.copy(alpha = 0.84f),
            ),
            startY = 0f,
            endY = size.height,
        ),
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
    )
}

@Composable
internal fun ProgressiveBottomBlur(
    modifier: Modifier,
    backdrop: com.kyant.backdrop.Backdrop,
    scale: Float,
    liveGlassEnabled: Boolean,
) {
    val effectModifier = if (liveGlassEnabled) {
        modifier.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = {
                blur(NokiUiKitPolicy.bottomProgressiveBlurRadiusDp * scale)
                runtimeShaderEffect(
                    key = "NokiBottomProgressiveBlurAlphaMask",
                    shaderString = """
                        uniform shader content;
                        uniform float2 size;

                        half4 main(float2 coord) {
                            float blurAlpha = smoothstep(0.0, size.y * 0.28, coord.y) * 0.72;
                            return content.eval(coord) * blurAlpha;
                        }
                    """.trimIndent(),
                    uniformShaderName = "content",
                ) {
                    setFloatUniform("size", size.width, size.height)
                }
            },
            onDrawSurface = {
                drawProgressiveBottomDim()
            },
        )
    } else {
        modifier.drawBehind { drawProgressiveBottomDim() }
    }
    Box(
        modifier = effectModifier,
    )
}

internal fun AppDestination.isSharedNavigationDestination(): Boolean {
    return PrimaryNavigationPolicy.showsSharedNavigation(
        isAuthenticated = true,
        destination = this,
    )
}

internal fun AppDestination.isPrimaryNavigationDestination(): Boolean {
    return PrimaryNavigationPolicy.isTopLevelDestination(this)
}

internal fun AppDestination.navigationOrder(): Int {
    return when (this) {
        AppDestination.HOME -> 0
        AppDestination.ACCOUNT -> 1
        AppDestination.SETTINGS -> 2
        AppDestination.STATS -> 3
        AppDestination.PLANS -> 4
        AppDestination.ADVANCED_SETTINGS -> 5
        AppDestination.MULTIHOP -> 6
        AppDestination.APP_FILTER -> 6
        AppDestination.SITE_RULES_ALWAYS_ON -> 7
        AppDestination.SITE_RULES_BYPASS -> 8
        AppDestination.SECURITY -> 9
        AppDestination.SUPPORT -> 10
        AppDestination.PERSONALIZATION -> 11
        AppDestination.DEVICES -> 12
        AppDestination.SPLASH,
        AppDestination.LOGIN,
        AppDestination.REGISTRATION,
        AppDestination.PASSWORD_RECOVERY,
        AppDestination.ACCOUNT_CREDENTIAL_CHANGE,
        AppDestination.INVITE_DEVICE,
        AppDestination.INVITE_QR_SCANNER -> -1
    }
}
