package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage
import com.kyant.backdrop.backdrops.LayerBackdrop

internal fun handleTelegramErrorCancel(
    temporaryVpnActive: Boolean,
    onDismiss: () -> Unit,
    onDisconnectTemporaryVpn: () -> Unit,
) {
    onDismiss()
    if (temporaryVpnActive) {
        onDisconnectTemporaryVpn()
    }
}

internal fun retainTelegramRetryError(
    retainedMessage: String?,
    incomingMessage: String?,
): String? = incomingMessage?.takeIf { it.isNotBlank() } ?: retainedMessage

internal fun temporaryVpnModalButtonSurfaceColor(liveGlassEnabled: Boolean): Color =
    NokiBgSoft.copy(
        alpha = if (liveGlassEnabled) AuthBgLighterButtonSurfaceAlpha else 1f,
    )

@Composable
internal fun WelcomeLoginScreen(
    metrics: NokiAdaptiveMetrics,
    inlineMessage: String?,
    telegramErrorMessage: String?,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onLoginClick: () -> Unit,
    onRegistrationClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onTelegramErrorDismiss: () -> Unit,
    telegramLoginInProgress: Boolean,
    googleLoginInProgress: Boolean,
    onGoogleClick: () -> Unit,
    onInviteCodeClick: () -> Unit,
    temporaryVpnConnected: Boolean,
    temporaryVpnConnecting: Boolean,
    temporaryVpnStatusMessage: String?,
    onTemporaryVpnClick: () -> Unit,
    onSupportClick: () -> Unit,
) {
    val inviteCodeInteractionSource = remember { MutableInteractionSource() }
    var showTemporaryVpnInfo by rememberSaveable { mutableStateOf(false) }
    var waitingForTemporaryVpn by rememberSaveable { mutableStateOf(false) }
    var temporaryVpnConsentGranted by remember { mutableStateOf(false) }
    val requestTelegramLogin = {
        if (!temporaryVpnConsentGranted) {
            showTemporaryVpnInfo = true
        } else if (temporaryVpnConnected) {
            onTelegramClick()
        } else {
            waitingForTemporaryVpn = true
            if (!temporaryVpnConnecting) {
                onTemporaryVpnClick()
            }
        }
    }
    val incomingRetryErrorMessage = telegramErrorMessage ?: temporaryVpnStatusMessage.takeIf {
        temporaryVpnConsentGranted &&
            waitingForTemporaryVpn &&
            !showTemporaryVpnInfo &&
            !temporaryVpnConnecting &&
            !it.isNullOrBlank()
    }
    var retainedRetryErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect {
        val nextMessage = retainTelegramRetryError(
            retainedMessage = retainedRetryErrorMessage,
            incomingMessage = incomingRetryErrorMessage,
        )
        if (nextMessage != retainedRetryErrorMessage) {
            retainedRetryErrorMessage = nextMessage
        }
    }
    val retryErrorMessage = incomingRetryErrorMessage ?: retainedRetryErrorMessage
    val showBlockingProgress = telegramLoginInProgress ||
        (waitingForTemporaryVpn && !showTemporaryVpnInfo && retryErrorMessage == null)

    LaunchedEffect(waitingForTemporaryVpn, temporaryVpnConnected) {
        if (waitingForTemporaryVpn && temporaryVpnConnected) {
            waitingForTemporaryVpn = false
            showTemporaryVpnInfo = false
            onTelegramClick()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WelcomeTopActions(
            metrics = metrics,
            language = language,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            onSupportClick = onSupportClick,
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(metrics.contentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.dp(70f)),
        ) {
            Image(
                painter = painterResource(R.drawable.login_logo_mark_vector),
                contentDescription = null,
                modifier = Modifier
                    .width(metrics.dp(78f))
                    .height(metrics.dp(89f)),
                contentScale = ContentScale.Fit,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(metrics.dp(70f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(30f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
                    ) {
                        AuthPrimaryButton(
                            text = tr(language, "Войти", "Sign in"),
                            loading = false,
                            containerColor = NokiAccentPrimary,
                            contentColor = NokiBgBase,
                            cornerRadius = metrics.dp(20f),
                            textFontSize = metrics.sp(20f),
                            textFontWeight = FontWeight.Medium,
                            useCommonButtonTextStyle = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.dp(60f)),
                            backdrop = backdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onClick = onLoginClick,
                        )

                        AuthSecondaryButton(
                            text = tr(language, "Создать аккаунт", "Create account"),
                            containerColor = NokiBgLighter,
                            contentColor = NokiTextPrimary,
                            cornerRadius = metrics.dp(20f),
                            textFontSize = metrics.sp(14f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.dp(45f)),
                            backdrop = backdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onClick = onRegistrationClick,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WelcomeSocialButton(
                            text = tr(language, "Войти с Telegram", "Telegram"),
                            iconRes = R.drawable.login_telegram_icon,
                            iconWidth = metrics.dp(29f),
                            iconHeight = metrics.dp(24f),
                            textFontSize = metrics.sp(12f),
                            textLetterSpacing = metrics.sp(0.12f),
                            modifier = Modifier
                                .weight(1f)
                                .height(metrics.dp(55f)),
                            backdrop = backdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            enabled = !telegramLoginInProgress && !googleLoginInProgress,
                            onClick = requestTelegramLogin,
                        )
                        WelcomeSocialButton(
                            text = tr(language, "Войти с Google", "Google"),
                            iconRes = R.drawable.login_google_icon,
                            iconWidth = metrics.dp(29f),
                            iconHeight = metrics.dp(29f),
                            textFontSize = metrics.sp(12f),
                            textLetterSpacing = metrics.sp(0.12f),
                            modifier = Modifier
                                .weight(1f)
                                .height(metrics.dp(55f)),
                            backdrop = backdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            enabled = !googleLoginInProgress && !telegramLoginInProgress,
                            onClick = onGoogleClick,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
                ) {
                    Text(
                        text = tr(language, "Войти по коду", "Sign in by code"),
                        color = NokiTextSecondary,
                        fontSize = metrics.sp(14f),
                        lineHeight = metrics.sp(14f),
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = metrics.sp(0.14f),
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = inviteCodeInteractionSource,
                                indication = null,
                                onClick = onInviteCodeClick,
                            ),
                    )

                    AuthInlineInfo(
                        message = inlineMessage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (showTemporaryVpnInfo) {
            TemporaryVpnInfoModal(
                metrics = metrics,
                language = language,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                connecting = temporaryVpnConnecting,
                errorMessage = temporaryVpnStatusMessage.takeUnless {
                    temporaryVpnConnecting || temporaryVpnConnected
                },
                onDismiss = {
                    if (waitingForTemporaryVpn && temporaryVpnConnecting) {
                        onTemporaryVpnClick()
                    }
                    waitingForTemporaryVpn = false
                    showTemporaryVpnInfo = false
                },
                onConnect = {
                    temporaryVpnConsentGranted = true
                    waitingForTemporaryVpn = true
                    if (!temporaryVpnConnected && !temporaryVpnConnecting) {
                        onTemporaryVpnClick()
                    }
                },
            )
        }

        retryErrorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            SettingsConfirmDialog(
                title = tr(language, "Не удалось войти", "Sign-in failed"),
                message = message,
                dismissText = tr(language, "Отмена", "Cancel"),
                confirmText = tr(language, "Повторить", "Retry"),
                confirmIsDanger = false,
                scale = metrics.contentScale,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                onDismiss = {
                    handleTelegramErrorCancel(
                        temporaryVpnActive = temporaryVpnConnected || temporaryVpnConnecting,
                        onDismiss = {
                            retainedRetryErrorMessage = null
                            waitingForTemporaryVpn = false
                            onTelegramErrorDismiss()
                        },
                        onDisconnectTemporaryVpn = onTemporaryVpnClick,
                    )
                },
                onConfirm = {
                    retainedRetryErrorMessage = null
                    waitingForTemporaryVpn = false
                    onTelegramErrorDismiss()
                    requestTelegramLogin()
                },
            )
        }

        if (showBlockingProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = NokiAccentPrimary)
            }
        }
    }
}

@Composable
internal fun WelcomeTopActions(
    metrics: NokiAdaptiveMetrics,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onSupportClick: () -> Unit,
) {
    val shape = RoundedCornerShape(metrics.dp(15f))

    WelcomeGlassButton(
        modifier = Modifier
            .offset(x = metrics.screenX(256f), y = metrics.dp(68f))
            .width(metrics.dp(140f))
            .height(metrics.dp(46f)),
        shape = shape,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onClick = onSupportClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.settings_support_icon),
                contentDescription = null,
                tint = NokiTextPrimary,
                modifier = Modifier.size(
                    width = metrics.dp(22f),
                    height = metrics.dp(24f),
                ),
            )
            Text(
                text = tr(language, "Поддержка", "Support"),
                color = NokiTextPrimary,
                fontSize = metrics.sp(12f),
                lineHeight = metrics.sp(14f),
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
internal fun TemporaryVpnInfoModal(
    metrics: NokiAdaptiveMetrics,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    connecting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(metrics.dp(28f))
    var retainedErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect {
        val nextMessage = retainTelegramRetryError(
            retainedMessage = retainedErrorMessage,
            incomingMessage = errorMessage,
        )
        if (nextMessage != retainedErrorMessage) {
            retainedErrorMessage = nextMessage
        }
    }
    val visibleErrorMessage = errorMessage?.takeIf { it.isNotBlank() } ?: retainedErrorMessage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.20f))
            .clickable(
                interactionSource = scrimInteractionSource,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(metrics.contentWidth)
                .nokiGlassSurface(
                    shape = shape,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    surfaceColor = authButtonSurfaceColor(NokiBgLighter),
                )
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null,
                    onClick = {},
                )
                .padding(
                    horizontal = metrics.dp(22f),
                    vertical = metrics.dp(24f),
                ),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(metrics.dp(18f)),
        ) {
            Text(
                text = tr(language, "Временное подключение", "Temporary connection"),
                color = NokiTextPrimary,
                fontSize = metrics.sp(18f),
                lineHeight = metrics.sp(22f),
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
            Text(
                text = tr(
                    language,
                    "Для входа через Telegram Noki временно подключит VPN. Лимит: 10 минут или 100 МБ, не более 7 подключений за 24 часа.",
                    "Noki will temporarily connect VPN for Telegram sign-in. Limit: 10 minutes or 100 MB, up to 7 connections per 24 hours.",
                ),
                color = NokiTextSecondary,
                fontSize = metrics.sp(13f),
                lineHeight = metrics.sp(18f),
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
            visibleErrorMessage?.let { message ->
                Text(
                    text = message,
                    color = NokiError,
                    fontSize = metrics.sp(12f),
                    lineHeight = metrics.sp(16f),
                    fontFamily = ManropeFontFamily,
                    fontWeight = FontWeight.Normal,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WelcomeGlassButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.dp(46f)),
                    shape = RoundedCornerShape(metrics.dp(38f)),
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    surfaceColor = temporaryVpnModalButtonSurfaceColor(liveGlassEnabled),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = tr(language, "Отмена", "Cancel"),
                        color = NokiTextSecondary,
                        fontSize = metrics.sp(14f),
                        lineHeight = metrics.sp(14f),
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                }
                WelcomeGlassButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.dp(46f)),
                    shape = RoundedCornerShape(metrics.dp(38f)),
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    surfaceColor = temporaryVpnModalButtonSurfaceColor(liveGlassEnabled),
                    enabled = !connecting,
                    onClick = onConnect,
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(metrics.dp(18f)),
                            strokeWidth = metrics.dp(2f),
                            color = NokiTextPrimary,
                        )
                    } else {
                        Text(
                            text = tr(language, "Подключиться", "Connect"),
                            color = NokiTextPrimary,
                            fontSize = metrics.sp(14f),
                            lineHeight = metrics.sp(14f),
                            fontFamily = ManropeFontFamily,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WelcomeSocialButton(
    text: String,
    @DrawableRes iconRes: Int,
    iconWidth: Dp,
    iconHeight: Dp,
    textFontSize: TextUnit,
    textLetterSpacing: TextUnit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(38f))
    WelcomeGlassButton(
        modifier = modifier,
        shape = shape,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier
                    .width(iconWidth)
                    .height(iconHeight),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = text,
                color = NokiTextPrimary,
                fontSize = textFontSize,
                lineHeight = textFontSize,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = textLetterSpacing,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
internal fun WelcomeGlassButton(
    modifier: Modifier,
    shape: Shape,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    enabled: Boolean = true,
    surfaceColor: Color = authButtonSurfaceColor(NokiBgLighter),
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickAnimator = rememberAuthButtonClickAnimator()

    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                surfaceColor = surfaceColor,
                layerBlock = {
                    scaleX = clickAnimator.scale.value
                    scaleY = clickAnimator.scale.value
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    clickAnimator.runClickAnimation(enabled = enabled, then = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
