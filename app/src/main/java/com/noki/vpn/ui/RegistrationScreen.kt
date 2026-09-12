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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noki.vpn.AuthStep
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
@Composable
fun RegistrationScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    liveGlassEnabled: Boolean = true,
) {
    val language = state.personalizationSettings.language

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NokiBgBase),
    ) {
        val authMetrics = nokiAdaptiveMetrics(maxWidth)
        val form = state.registrationForm
        val registrationContentHeight = when (state.authStep) {
            AuthStep.REGISTRATION_PROFILE -> authMetrics.dp(380f)
            AuthStep.REGISTRATION_PASSWORD -> authMetrics.dp(424f)
            AuthStep.REGISTRATION_EMAIL -> authMetrics.dp(385f)
            else -> authMetrics.dp(358f)
        }
        val registrationContentTop = ((maxHeight - registrationContentHeight) / 2).let { centeredTop ->
            if (centeredTop < authMetrics.dp(32f)) authMetrics.dp(32f) else centeredTop
        }
        val registrationLogoTop = registrationContentTop
        val registrationFieldTop = registrationLogoTop + authMetrics.dp(78f) + if (state.authStep == AuthStep.REGISTRATION_PASSWORD) authMetrics.dp(68f) else authMetrics.dp(90f)
        val registrationFieldLabelTop = registrationFieldTop - authMetrics.dp(27f)
        val registrationRequirementHeight = authMetrics.dp(18f)
        val registrationFieldBlockHeight = when (state.authStep) {
            AuthStep.REGISTRATION_PROFILE -> authMetrics.dp(48f) + authMetrics.dp(8f) + registrationRequirementHeight
            AuthStep.REGISTRATION_PASSWORD -> authMetrics.dp(48f) + authMetrics.dp(17f) + authMetrics.dp(48f) + authMetrics.dp(8f) + registrationRequirementHeight
            else -> authMetrics.dp(48f)
        }
        val registrationErrorReserveHeight = if (form.error == null) 0.dp else authMetrics.dp(36f)
        val registrationPrimaryTop = registrationFieldTop + registrationFieldBlockHeight + authMetrics.dp(30f) + registrationErrorReserveHeight
        val registrationBackTop = registrationPrimaryTop + authMetrics.dp(70f)

        val authBackdrop = rememberLayerBackdrop()

        AuthBackground(
            backgroundRes = R.drawable.registration_background,
            modifier = Modifier.then(
                if (liveGlassEnabled) Modifier.layerBackdrop(authBackdrop) else Modifier,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .height(maxHeight.coerceAtLeast((registrationBackTop + authMetrics.dp(42f)) + authMetrics.dp(24f))),
        ) scrollForm@ {
            AuthLogo(top = registrationLogoTop)

            when (state.authStep) {
                AuthStep.REGISTRATION_CODE -> {
                    AuthVerificationCodeField(
                        value = form.verificationCode,
                        onValueChange = viewModel::updateRegistrationVerificationCode,
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
                        buttonEnabled = form.codeCooldownSeconds == 0,
                        buttonLoading = form.isCodeSending,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop)
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                        backdrop = authBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onButtonClick = viewModel::requestRegistrationCode,
                    )
                }
                AuthStep.REGISTRATION_PROFILE -> {
                    AuthFieldLabel(
                        text = tr(language, "Выберите имя пользователя", "Choose a username"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldLabelTop)
                            .width(authMetrics.contentWidth),
                    )

                    AuthInputField(
                        value = form.username,
                        onValueChange = viewModel::updateRegistrationUsername,
                        placeholder = "username",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop)
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )

                    AuthRequirementText(
                        text = tr(language, "Не менее 3 символов, только латиница", "At least 3 characters, Latin letters only"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop + authMetrics.dp(56f))
                            .width(authMetrics.contentWidth)
                            .height(registrationRequirementHeight),
                    )
                }
                AuthStep.REGISTRATION_PASSWORD -> {
                    AuthFieldLabel(
                        text = tr(language, "Создайте пароль", "Create a password"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldLabelTop)
                            .width(authMetrics.contentWidth),
                    )

                    AuthInputField(
                        value = form.password,
                        onValueChange = viewModel::updateRegistrationPassword,
                        placeholder = tr(language, "Пароль", "Password"),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        enabled = !form.isLoading,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop)
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )

                    AuthInputField(
                        value = form.passwordRepeat,
                        onValueChange = viewModel::updateRegistrationPasswordRepeat,
                        placeholder = tr(language, "Повторите пароль", "Repeat password"),
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        enabled = !form.isLoading,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop + authMetrics.dp(65f))
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )

                    AuthRequirementText(
                        text = tr(language, "Не менее 8 символов", "At least 8 characters"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop + authMetrics.dp(121f))
                            .width(authMetrics.contentWidth)
                            .height(registrationRequirementHeight),
                    )
                }
                AuthStep.WELCOME,
                AuthStep.EMAIL_LOGIN,
                AuthStep.REGISTRATION_EMAIL -> {
                    AuthFieldLabel(
                        text = tr(language, "Введите ваш e-mail", "Enter your email"),
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldLabelTop)
                            .width(authMetrics.contentWidth),
                    )

                    AuthInputField(
                        value = form.email,
                        onValueChange = viewModel::updateRegistrationEmail,
                        placeholder = "E-mail",
                        keyboardType = KeyboardType.Email,
                        modifier = Modifier
                            .offset(x = authMetrics.contentStart, y = registrationFieldTop)
                            .width(authMetrics.contentWidth)
                            .height(authMetrics.dp(48f)),
                    )
                }
            }

            AuthInlineError(
                error = form.error,
                modifier = Modifier
                    .offset(
                        x = authMetrics.contentStart,
                        y = registrationFieldTop + registrationFieldBlockHeight + authMetrics.dp(8f),
                    )
                    .width(authMetrics.contentWidth),
            )

            AuthPrimaryButton(
                text = when (state.authStep) {
                    AuthStep.REGISTRATION_EMAIL -> tr(language, "Получить код", "Get code")
                    AuthStep.REGISTRATION_PASSWORD -> tr(language, "Создать аккаунт", "Create account")
                    else -> tr(language, "Продолжить", "Continue")
                },
                loading = form.isLoading || form.isCodeSending,
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = registrationPrimaryTop)
                    .width(authMetrics.contentWidth)
                    .height(authMetrics.dp(56f)),
                backdrop = authBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = if (state.authStep == AuthStep.REGISTRATION_EMAIL) {
                    viewModel::requestRegistrationCode
                } else {
                    viewModel::nextRegistrationStep
                },
            )

            AuthSecondaryButton(
                text = tr(language, "Назад", "Back"),
                containerColor = NokiBgLighter,
                contentColor = NokiTextPrimary,
                cornerRadius = authMetrics.dp(16f),
                modifier = Modifier
                    .offset(x = authMetrics.contentStart, y = registrationBackTop)
                    .width(authMetrics.contentWidth)
                    .height(authMetrics.dp(42f)),
                backdrop = authBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = viewModel::previousRegistrationStep,
            )

        }
    }
}
