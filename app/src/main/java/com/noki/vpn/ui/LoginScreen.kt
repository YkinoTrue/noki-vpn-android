package com.noki.vpn.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.noki.vpn.AppDestination
import com.noki.vpn.AuthStep
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.noki.vpn.TelegramLoginStateReducer
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.VpnRuntimeMode
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
@Composable
fun LoginScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    liveGlassEnabled: Boolean = true,
    onGoogleClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onTemporaryVpnClick: () -> Unit,
    onSupportClick: () -> Unit,
) {
    val language = state.personalizationSettings.language

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NokiBgBase),
    ) {
        val authMetrics = nokiAdaptiveMetrics(maxWidth)
        val loginButtonWidth = authMetrics.contentWidth
        val showEmailLogin = state.authStep == AuthStep.EMAIL_LOGIN
        val loginContentHeight = if (showEmailLogin) authMetrics.dp(459f) else authMetrics.dp(578f)
        val loginContentTop = ((maxHeight - loginContentHeight) / 2).let { centeredTop ->
            if (centeredTop < authMetrics.dp(32f)) authMetrics.dp(32f) else centeredTop
        }
        val loginYOffset = loginContentTop - authMetrics.dp(200f)

        val forgotPasswordInteractionSource = remember { MutableInteractionSource() }
        val inviteCodeInteractionSource = remember { MutableInteractionSource() }
        val authBackdrop = rememberLayerBackdrop()

        AuthBackground(
            backgroundRes = R.drawable.login_background,
            modifier = Modifier.then(
                if (liveGlassEnabled) Modifier.layerBackdrop(authBackdrop) else Modifier,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .height(maxHeight.coerceAtLeast((loginContentTop + loginContentHeight) + authMetrics.dp(24f))),
        ) scrollForm@ {
            if (showEmailLogin) {
                AuthLogo(top = authMetrics.dp(200f) + loginYOffset)
            }

            if (!showEmailLogin) {
                val temporaryVpnConnected = state.vpnRuntimeMode == VpnRuntimeMode.AUTH_TEMP &&
                    state.connectionState == VpnConnectionState.CONNECTED
                val temporaryVpnConnecting = state.vpnRuntimeMode == VpnRuntimeMode.AUTH_TEMP &&
                    state.connectionState == VpnConnectionState.CONNECTING
                WelcomeLoginScreen(
                    metrics = authMetrics,
                    inlineMessage = welcomeInlineMessageForDisplay(
                        inlineMessage = state.inlineMessage,
                        vpnRuntimeMode = state.vpnRuntimeMode,
                    ),
                    telegramErrorMessage = TelegramLoginStateReducer.errorMessage(
                        state.telegramLoginState,
                        com.noki.vpn.TelegramAuthPurpose.LOGIN,
                    ),
                    language = language,
                    onLoginClick = viewModel::openEmailLogin,
                    onRegistrationClick = viewModel::openRegistrationFlow,
                    onTelegramClick = onTelegramClick,
                    onTelegramErrorDismiss = viewModel::cancelTelegramLoginFlow,
                    telegramLoginInProgress = TelegramLoginStateReducer.isActive(
                        state.telegramLoginState,
                    ),
                    googleLoginInProgress = state.loginForm.isLoading,
                    onGoogleClick = onGoogleClick,
                    onInviteCodeClick = { viewModel.openScreen(AppDestination.INVITE_DEVICE) },
                    temporaryVpnConnected = temporaryVpnConnected,
                    temporaryVpnConnecting = temporaryVpnConnecting,
                    temporaryVpnStatusMessage = state.inlineMessage.takeIf {
                        state.vpnRuntimeMode == VpnRuntimeMode.AUTH_TEMP &&
                            state.connectionState == VpnConnectionState.FAILED
                    },
                    onTemporaryVpnClick = onTemporaryVpnClick,
                    onSupportClick = onSupportClick,
                    backdrop = authBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                )
                return@scrollForm
            }

            AuthInputField(
                value = state.loginForm.email,
                onValueChange = viewModel::updateLoginEmail,
                placeholder = tr(language, "E-mail / Логин", "E-mail / Login"),
                keyboardType = KeyboardType.Text,
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(338f) + loginYOffset)
                    .width(authMetrics.contentWidth)
                    .height(authMetrics.dp(48f)),
            )

            AuthInputField(
                value = state.loginForm.password,
                onValueChange = viewModel::updateLoginPassword,
                placeholder = tr(language, "Пароль", "Password"),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(403f) + loginYOffset)
                    .width(authMetrics.contentWidth)
                    .height(authMetrics.dp(48f)),
            )

            AuthInlineError(
                error = state.loginForm.error,
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(309f) + loginYOffset)
                    .width(authMetrics.contentWidth),
            )

            Box(
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(458f) + loginYOffset)
                    .width(loginButtonWidth),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = tr(language, "Восстановить пароль", "Reset password"),
                    modifier = Modifier.clickable(
                        interactionSource = forgotPasswordInteractionSource,
                        indication = null,
                        onClick = viewModel::openPasswordRecovery,
                    ),
                    color = NokiTextSecondary,
                    fontSize = authSp(11f),
                    lineHeight = authSp(12f),
                    fontFamily = ManropeFontFamily,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = authSp(0.11f),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Right,
                )
            }

            AuthPrimaryButton(
                text = tr(language, "Войти", "Sign in"),
                loading = state.loginForm.isLoading,
                containerColor = NokiAccentPrimary,
                contentColor = NokiBgBase,
                cornerRadius = authMetrics.dp(20f),
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(497f) + loginYOffset)
                    .width(loginButtonWidth)
                    .height(authMetrics.dp(56f)),
                backdrop = authBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = viewModel::submitLogin,
            )

            AuthSecondaryButton(
                text = tr(language, "Назад", "Back"),
                containerColor = NokiBgLighter,
                contentColor = NokiTextPrimary,
                cornerRadius = authMetrics.dp(16f),
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = authMetrics.dp(567f) + loginYOffset)
                    .width(loginButtonWidth)
                    .height(authMetrics.dp(42f)),
                backdrop = authBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = viewModel::goBack,
            )

            Box(
                modifier = Modifier
                    .offset(x = 0.dp, y = authMetrics.dp(623f) + loginYOffset)
                    .fillMaxWidth()
                    .height(authMetrics.dp(36f))
                    .clickable(
                        interactionSource = inviteCodeInteractionSource,
                        indication = null,
                        onClick = { viewModel.openScreen(AppDestination.INVITE_DEVICE) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tr(language, "Войти по коду", "Sign in by code"),
                    color = NokiTextSecondary,
                    fontSize = authSp(14f),
                    lineHeight = authSp(14f),
                    fontFamily = ManropeFontFamily,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = authSp(0.14f),
                    textDecoration = TextDecoration.Underline,
                )
            }

        }
    }
}
