package com.noki.vpn.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
@Composable
fun PasswordRecoveryScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    liveGlassEnabled: Boolean = true,
) {
    val language = state.personalizationSettings.language
    val form = state.passwordRecoveryForm
    val isPasswordStep = form.passwordStepVisible
    val canSubmitNewPassword = form.password.length >= 8 && form.password == form.passwordRepeat
    val activeStep = when {
        !isPasswordStep -> 1
        canSubmitNewPassword -> 3
        else -> 2
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NokiBgBase),
    ) {
        val authMetrics = nokiAdaptiveMetrics(maxWidth)
        val recoveryLogoTop = if (isPasswordStep) authMetrics.dp(250f) else authMetrics.dp(259f)

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
                .height(maxHeight.coerceAtLeast((if (isPasswordStep) authMetrics.dp(602f) else authMetrics.dp(545f)) + authMetrics.dp(24f))),
        ) scrollForm@ {
            RecoveryStepsIndicator(
                activeStep = activeStep,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = authMetrics.dp(91f))
                    .width(authMetrics.dp(227f))
                    .height(authMetrics.dp(34f)),
            )

            AuthLogo(
                top = recoveryLogoTop,
            )

            if (!isPasswordStep) {
                if (!form.showCodeField) {
                    AuthFieldLabel(
                        text = tr(language, "Введите ваш e-mail", "Enter your email"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(371f))
                            .width(authMetrics.contentWidth),
                    )

                    AuthInputField(
                        value = form.email,
                        onValueChange = viewModel::updatePasswordRecoveryEmail,
                        placeholder = "E-mail",
                        keyboardType = KeyboardType.Email,
                        textFontSize = authSp(18f),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(398f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )
                } else {
                    AuthVerificationCodeField(
                        value = form.verificationCode,
                        onValueChange = viewModel::updatePasswordRecoveryCode,
                        placeholder = tr(language, "Код подтверждения", "Verification code"),
                        buttonText = when {
                            form.codeCooldownSeconds > 0 -> tr(
                                language,
                                "Повторить (${form.codeCooldownSeconds}с)",
                                "Resend (${form.codeCooldownSeconds}s)",
                            )
                            form.codeSent -> tr(language, "Отправить повторно", "Resend")
                            else -> tr(language, "Отправить код", "Send code")
                        },
                        showButton = true,
                        inputEnabled = !form.isSubmitting,
                        buttonEnabled = form.codeCooldownSeconds == 0 && !form.isSubmitting,
                        buttonLoading = form.isCodeSending,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = authMetrics.dp(398f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                        backdrop = authBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onButtonClick = viewModel::requestPasswordRecoveryCode,
                    )
                }

                AuthInlineError(
                    error = form.error,
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(453f))
                        .width(authMetrics.contentWidth),
                )

                AuthPrimaryButton(
                    text = tr(language, "Далее", "Next"),
                    loading = form.isSubmitting,
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(489f))
                        .width(authMetrics.contentWidth)
                        .height(authMetrics.dp(56f)),
                    backdrop = authBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onClick = viewModel::submitPasswordRecovery,
                )
            } else {
                AuthFieldLabel(
                    text = tr(language, "Создайте пароль", "Create a password"),
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(338f))
                        .width(authMetrics.contentWidth),
                )

                AuthInputField(
                    value = form.password,
                    onValueChange = viewModel::updatePasswordRecoveryPassword,
                    placeholder = tr(language, "Введите новый пароль", "Enter a new password"),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    enabled = !form.isSubmitting,
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(365f))
                        .width(authMetrics.contentWidth)
                        .height(authMetrics.dp(48f)),
                )

                AuthInputField(
                    value = form.passwordRepeat,
                    onValueChange = viewModel::updatePasswordRecoveryPasswordRepeat,
                    placeholder = tr(language, "Повторите новый пароль", "Please enter a new password again"),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    enabled = !form.isSubmitting,
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
                    error = form.error,
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(509f))
                        .width(authMetrics.contentWidth),
                )

                AuthPrimaryButton(
                    text = tr(language, "Изменить пароль", "Change password"),
                    loading = form.isSubmitting,
                    enabled = canSubmitNewPassword,
                    disabledContainerColor = NokiTextMuted,
                    disabledContentColor = NokiTextSecondary,
                    modifier = Modifier
                        .offset(x = authMetrics.contentStart, y = authMetrics.dp(546f))
                        .width(authMetrics.contentWidth)
                        .height(authMetrics.dp(56f)),
                    backdrop = authBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onClick = viewModel::submitPasswordRecovery,
                )
            }

        }
    }
}

@Composable
internal fun RecoveryStepsIndicator(
    activeStep: Int,
    modifier: Modifier = Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecoveryStepBadge(step = 1, active = activeStep == 1)
        Spacer(modifier = Modifier.width(metrics.dp(8f)))
        Box(
            modifier = Modifier
                .width(metrics.dp(19f))
                .height(1.dp)
                .background(NokiStroke.copy(alpha = 0.64f)),
        )
        Spacer(modifier = Modifier.width(metrics.dp(8f)))
        RecoveryStepBadge(step = 2, active = activeStep == 2)
        Spacer(modifier = Modifier.width(metrics.dp(8f)))
        Box(
            modifier = Modifier
                .width(metrics.dp(19f))
                .height(1.dp)
                .background(NokiStroke.copy(alpha = 0.64f)),
        )
        Spacer(modifier = Modifier.width(metrics.dp(8f)))
        RecoveryStepBadge(step = 3, active = activeStep == 3)
    }
}

@Composable
internal fun RecoveryStepBadge(
    step: Int,
    active: Boolean,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(8f))
    Box(
        modifier = Modifier
            .width(metrics.dp(34f))
            .height(metrics.dp(34f))
            .shadow(
                elevation = if (active) metrics.dp(12f) else 0.dp,
                shape = shape,
                ambientColor = NokiAccentPrimary.copy(alpha = if (active) 0.30f else 0f),
                spotColor = NokiAccentPrimary.copy(alpha = if (active) 0.30f else 0f),
            )
            .background(if (active) NokiBgSoft else NokiBgLighter, shape)
            .border(1.dp, if (active) NokiAccentPrimary else NokiStroke, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = step.toString(),
            color = NokiTextPrimary,
            fontSize = authSp(11f),
            lineHeight = authSp(11f),
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = authSp(0.28f),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        )
    }
}
