package com.noki.vpn.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.AccountSecurityActionState
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R

@Composable
internal fun AccountCredentialChangeScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    liveGlassEnabled: Boolean = true,
) {
    val language = state.personalizationSettings.language
    val action = state.accountSecurityState.action

    if (action == null) {
        LaunchedEffect(Unit) {
            viewModel.closeAccountSecurityAction()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NokiBgBase),
    ) {
        val authMetrics = nokiAdaptiveMetrics(maxWidth)
        val isPasswordDetailsStep = action is AccountSecurityActionState.Password
        val logoTop = if (isPasswordDetailsStep) authMetrics.dp(250f) else authMetrics.dp(259f)
        val contentBottom = if (isPasswordDetailsStep) authMetrics.dp(602f) else authMetrics.dp(545f)

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
                .height(maxHeight.coerceAtLeast((contentBottom) + authMetrics.dp(24f))),
        ) scrollForm@ {
            val activeStep = when (action) {
                is AccountSecurityActionState.Email -> when {
                    !action.codeSent -> 1
                    action.verificationCode.trim().length in 4..12 -> 3
                    else -> 2
                }

                is AccountSecurityActionState.Password -> when {
                    action.newPassword.length >= 8 && action.newPassword == action.confirmation -> 3
                    else -> 2
                }

                else -> 1
            }
            RecoveryStepsIndicator(
                activeStep = activeStep,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = authMetrics.dp(91f))
                    .width(authMetrics.dp(227f))
                    .height(authMetrics.dp(34f)),
            )

            AuthLogo(top = logoTop)

            when (action) {
                is AccountSecurityActionState.Email -> {
                    if (!action.codeSent) {
                        AuthFieldLabel(
                            text = tr(language, "Введите новый e-mail", "Enter a new email"),
                            modifier = Modifier
                                .offset(x = authMetrics.contentStart, y = authMetrics.dp(371f))
                                .width(authMetrics.contentWidth),
                        )
                        AuthInputField(
                            value = action.email,
                            onValueChange = viewModel::updateAccountEmail,
                            placeholder = "E-mail",
                            keyboardType = KeyboardType.Email,
                            enabled = !action.isLoading,
                            textFontSize = authSp(18f),
                            modifier = Modifier
                                .offset(x = authMetrics.contentStart, y = authMetrics.dp(398f))
                                .width(authMetrics.contentWidth)
                                .height(authMetrics.dp(48f)),
                        )
                    } else {
                        AuthVerificationCodeField(
                            value = action.verificationCode,
                            onValueChange = viewModel::updateAccountEmailCode,
                            placeholder = tr(language, "Код подтверждения", "Verification code"),
                            buttonText = when {
                                action.cooldownSeconds > 0 -> tr(
                                    language,
                                    "Повторить (${action.cooldownSeconds}с)",
                                    "Resend (${action.cooldownSeconds}s)",
                                )

                                else -> tr(language, "Отправить повторно", "Resend")
                            },
                            showButton = true,
                            inputEnabled = !action.isLoading,
                            buttonEnabled = action.cooldownSeconds == 0,
                            buttonLoading = action.isLoading,
                            modifier = Modifier
                                .offset(x = authMetrics.contentStart, y = authMetrics.dp(398f))
                                .width(authMetrics.contentWidth)
                                .height(authMetrics.dp(48f)),
                            backdrop = authBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            onButtonClick = viewModel::requestAccountEmailCode,
                        )
                    }

                    AuthInlineError(
                        error = action.error,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(453f))
                            .width(authMetrics.contentWidth),
                    )
                    AuthPrimaryButton(
                        text = if (action.codeSent) {
                            tr(language, "Сменить e-mail", "Change email")
                        } else {
                            tr(language, "Далее", "Next")
                        },
                        loading = action.isLoading,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(489f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(56f)),
                        backdrop = authBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onClick = if (action.codeSent) {
                            viewModel::submitAccountEmail
                        } else {
                            viewModel::requestAccountEmailCode
                        },
                    )
                }

                is AccountSecurityActionState.Password -> {
                    AuthFieldLabel(
                        text = tr(language, "Создайте пароль", "Create a password"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(338f))
                            .width(authMetrics.contentWidth),
                    )
                    AuthInputField(
                        value = action.newPassword,
                        onValueChange = viewModel::updateAccountNewPassword,
                        placeholder = tr(language, "Введите новый пароль", "Enter a new password"),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        enabled = !action.isLoading,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(365f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )
                    AuthInputField(
                        value = action.confirmation,
                        onValueChange = viewModel::updateAccountPasswordConfirmation,
                        placeholder = tr(language, "Повторите новый пароль", "Please enter a new password again"),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        enabled = !action.isLoading,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(430f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )
                    AuthRequirementText(
                        text = tr(language, "Не менее 8 символов", "At least 8 characters"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(486f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(18f)),
                    )
                    AuthInlineError(
                        error = action.error,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(509f))
                            .width(authMetrics.contentWidth),
                    )
                    val canSubmit = action.newPassword.length >= 8 &&
                        action.newPassword == action.confirmation
                    AuthPrimaryButton(
                        text = tr(language, "Задать пароль", "Set password"),
                        loading = action.isLoading,
                        enabled = canSubmit,
                        disabledContainerColor = NokiTextMuted,
                        disabledContentColor = NokiTextSecondary,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(546f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(56f)),
                        backdrop = authBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onClick = viewModel::submitAccountPassword,
                    )
                }

                else -> Unit
            }
        }
    }
}
