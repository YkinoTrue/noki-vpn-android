package com.noki.vpn

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun AppUiRuntime.updateLoginEmail(value: String) {
    uiState = uiState.copy(loginForm = uiState.loginForm.copy(email = value, error = null))
}

internal fun AppUiRuntime.updateLoginPassword(value: String) {
    uiState = uiState.copy(loginForm = uiState.loginForm.copy(password = value, error = null))
}

internal fun AppUiRuntime.openEmailLogin() {
    if (sessionOperationJob != null) return
    uiState = AuthStepReducer.showEmailLogin(
        uiState.copy(screenStack = listOf(AppDestination.LOGIN)),
    )
}

internal fun AppUiRuntime.beginTelegramLogin(): Boolean {
    if (uiState.isAuthenticated || sessionOperationJob != null || uiState.loginForm.isLoading) return false
    val launching = TelegramLoginStateReducer.begin(
        current = uiState.telegramLoginState,
        purpose = TelegramAuthPurpose.LOGIN,
    ) ?: return false
    uiState = uiState.copy(
        authStep = AuthStep.WELCOME,
        screenStack = listOf(AppDestination.LOGIN),
        telegramLoginState = launching,
        inlineMessage = null,
    )
    telegramAuthPurposeState.begin(TelegramAuthPurpose.LOGIN)
    recordAppLog("auth", message = "telegram_login_begin")
    return true
}

internal fun AppUiRuntime.beginTelegramLink(): Boolean {
    if (
        !uiState.isAuthenticated ||
        sessionOperationJob != null ||
        isCurrentDeviceInvited() ||
        uiState.userProfile.telegramLinked
    ) {
        return false
    }
    val launching = TelegramLoginStateReducer.begin(
        current = uiState.telegramLoginState,
        purpose = TelegramAuthPurpose.LINK,
    ) ?: return false
    uiState = uiState.copy(
        telegramLoginState = launching,
    )
    telegramAuthPurposeState.begin(TelegramAuthPurpose.LINK)
    recordAppLog("auth", message = "telegram_link_begin")
    return true
}

internal suspend fun AppUiRuntime.prepareTelegramLogin(codeChallenge: String, clientState: String): String? =
    prepareTelegramLaunch {
        telegramAuthCoordinator().authorizationUrl(codeChallenge, clientState, browser = false)
    }

internal suspend fun AppUiRuntime.prepareTelegramBrowserLogin(codeChallenge: String, clientState: String): String? =
    prepareTelegramLaunch {
        telegramAuthCoordinator().authorizationUrl(codeChallenge, clientState, browser = true)
    }

internal suspend fun AppUiRuntime.prepareTelegramLaunch(loadUrl: suspend () -> String): String? {
    val state = uiState.telegramLoginState as? TelegramLoginState.LaunchingSdk ?: return null
    val attemptId = telegramAuthPurposeState.currentAttemptId ?: return null
    val purpose = state.purpose.name.lowercase()
    return try {
        val url = loadUrl()
        if (!telegramAuthPurposeState.isCurrent(attemptId) ||
            uiState.telegramLoginState !is TelegramLoginState.LaunchingSdk
        ) {
            recordAppLog("auth", message = "telegram_authorization_url_stale", details = "purpose=$purpose")
            return null
        }
        url.also {
            recordAppLog(
                category = "auth",
                message = "telegram_authorization_url_ready",
                details = "purpose=$purpose",
            )
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        recordAppLog(
            category = "auth",
            level = "error",
            message = "telegram_authorization_url_fail",
            details = "purpose=$purpose",
            errorType = AppErrorMapper.readableErrorType(error),
        )
        if (telegramAuthPurposeState.isCurrent(attemptId)) {
            handleTelegramLoginResult(TelegramLoginResult.Failure("sdk_launch_failed"))
        }
        null
    }
}

internal fun AppUiRuntime.handleTelegramLoginCallback(result: TelegramLoginCallbackResult) {
    if (TelegramCallbackPolicy.shouldIgnore(result)) {
        recordAppLog("auth", message = "telegram_callback_stale")
        return
    }
    val callbackType = when (result) {
        TelegramLoginCallbackResult.Cancelled -> "cancelled"
        is TelegramLoginCallbackResult.Failure -> "failure"
        is TelegramLoginCallbackResult.AuthorizationCode -> "authorization_code"
        is TelegramLoginCallbackResult.BrowserState -> "browser_state"
    }
    recordAppLog(
        category = "auth",
        message = "telegram_callback_received",
        details = "type=$callbackType",
    )
    when (result) {
        TelegramLoginCallbackResult.Cancelled -> {
            handleTelegramLoginResult(TelegramLoginResult.Cancelled)
        }

        is TelegramLoginCallbackResult.Failure -> {
            handleTelegramLoginResult(TelegramLoginResult.Failure(result.reason.code))
        }

        is TelegramLoginCallbackResult.AuthorizationCode -> {
            exchangeTelegramAuthorization(result)
        }

        is TelegramLoginCallbackResult.BrowserState -> {
            exchangeTelegramAuthorization(result)
        }
    }
}

internal fun AppUiRuntime.exchangeTelegramAuthorization(result: TelegramLoginCallbackResult) {
    if (uiState.telegramLoginState !is TelegramLoginState.LaunchingSdk) return
    val attemptId = telegramAuthPurposeState.currentAttemptId ?: return
    scope.launch {
        val resolved = telegramAuthCoordinator().resolveCallback(result)
        if (
            !telegramAuthPurposeState.isCurrent(attemptId) ||
            uiState.telegramLoginState !is TelegramLoginState.LaunchingSdk
        ) {
            recordAppLog("auth", message = "telegram_callback_stale")
            return@launch
        }
        if (result is TelegramLoginCallbackResult.AuthorizationCode) {
            if (!telegramLoginGateway.finishNativeCallback(result, resolved is TelegramLoginResult.Success)) {
                recordAppLog("auth", message = "telegram_callback_stale")
                return@launch
            }
            if (resolved !is TelegramLoginResult.Success) {
                recordAppLog("auth", level = "error", message = "telegram_native_code_rejected")
                telegramLoginGateway.failedNativeCallbackTimeoutLease()?.let { lease ->
                    expireTelegramLoginAfterDelay(lease, "telegram_exchange_failed")
                }
                return@launch
            }
        }
        handleTelegramLoginResult(resolved)
    }
}

internal fun AppUiRuntime.expireTelegramLoginAfterDelay(
    lease: TelegramLoginGateway.ExternalFlowTimeoutLease,
    errorCode: String = "callback_not_received",
) {
    val attemptId = telegramAuthPurposeState.currentAttemptId ?: return
    scope.launch {
        delay(750)
        if (!telegramAuthPurposeState.isCurrent(attemptId)) return@launch
        if (telegramLoginGateway.expireExternalFlow(lease)) {
            handleTelegramLoginResult(TelegramLoginResult.Failure(errorCode))
        }
    }
}

internal fun AppUiRuntime.cancelTelegramLoginFlow() {
    telegramLoginGateway.cancel()
    when (uiState.telegramLoginState) {
        is TelegramLoginState.LaunchingSdk -> handleTelegramLoginResult(TelegramLoginResult.Cancelled)
        is TelegramLoginState.Error -> {
            telegramAuthPurposeState.invalidate()
            uiState = uiState.copy(
                telegramLoginState = TelegramLoginStateReducer.cancel(uiState.telegramLoginState),
                inlineMessage = null,
            )
        }
        else -> Unit
    }
}

internal fun AppUiRuntime.handleTelegramLoginResult(result: TelegramLoginResult) {
    val purpose = (uiState.telegramLoginState as? TelegramLoginState.LaunchingSdk)?.purpose
        ?: telegramAuthPurposeState.purpose
        ?: return
    if (uiState.isAuthenticated && purpose != TelegramAuthPurpose.LINK) return
    when (result) {
        TelegramLoginResult.Cancelled -> {
            recordAppLog("auth", message = "telegram_flow_cancelled", details = "purpose=${purpose.name.lowercase()}")
            telegramAuthPurposeState.invalidate()
            uiState = uiState.copy(
                telegramLoginState = TelegramLoginStateReducer.cancel(uiState.telegramLoginState),
                inlineMessage = null,
            )
        }

        is TelegramLoginResult.Failure -> {
            recordAppLog(
                category = "auth",
                level = "error",
                message = "telegram_flow_fail",
                details = "purpose=${purpose.name.lowercase()}",
                errorType = result.code,
            )
            val message = AppErrorMapper.readableTelegramSdkError(
                language = uiState.personalizationSettings.language,
                code = result.code,
            )
            telegramAuthPurposeState.invalidate()
            uiState = if (purpose == TelegramAuthPurpose.LINK) {
                uiState.copy(
                    telegramLoginState = TelegramLoginStateReducer.error(message, purpose),
                )
            } else {
                uiState.copy(
                    telegramLoginState = TelegramLoginStateReducer.error(message, purpose),
                    inlineMessage = null,
                )
            }
        }

        is TelegramLoginResult.Success -> {
            recordAppLog("auth", message = "telegram_token_received", details = "purpose=${purpose.name.lowercase()}")
            when (purpose) {
                TelegramAuthPurpose.LOGIN -> exchangeTelegramLogin(result.idToken)
                TelegramAuthPurpose.LINK -> exchangeTelegramLink(result.idToken)
            }
        }
    }
}

internal fun AppUiRuntime.exchangeTelegramLogin(idToken: String) {
    if (uiState.isAuthenticated || sessionOperationJob != null) return
    val exchanging = TelegramLoginStateReducer.beginExchange(
        current = uiState.telegramLoginState,
    ) ?: return
    val language = uiState.personalizationSettings.language
    val exchangingState = uiState.copy(
        telegramLoginState = exchanging,
        inlineMessage = null,
    )
    val preparedState = exchangingState.copy(
        isAuthenticated = true,
        authStep = AuthStep.WELCOME,
        screenStack = listOf(AppDestination.HOME),
        telegramLoginState = TelegramLoginState.Authenticated,
        inlineMessage = tr(language, "Вы вошли в Noki Vpn", "You are signed in to Noki Vpn"),
    )
    recordAppLog("auth", message = "telegram_login_exchange_start")
    launchSessionOperation(
        onStarted = { uiState = exchangingState },
        operation = {
            telegramAuthCoordinator().login(
                idToken = idToken,
                deviceId = backendDeviceId.ifBlank { null },
                preparedState = preparedState,
            )
        },
        onSuccess = {
            telegramAuthPurposeState.invalidate()
            recordAppLog("auth", message = "telegram_login_success")
            startAppNotificationPolling()
            syncFcmTokenIfAvailable()
            maybeUploadLogsAutomatically()
        },
        onFailure = { error ->
            telegramAuthPurposeState.invalidate()
            val message = AppErrorMapper.readableTelegramAuthError(language, error)
            recordAppLog(
                category = "auth",
                level = "error",
                message = "telegram_login_fail",
                errorType = AppErrorMapper.readableErrorType(error),
            )
            uiState = uiState.copy(
                telegramLoginState = TelegramLoginStateReducer.error(message, TelegramAuthPurpose.LOGIN),
                inlineMessage = null,
            )
        },
    )
}

internal fun AppUiRuntime.exchangeTelegramLink(idToken: String) {
    if (!uiState.isAuthenticated || sessionOperationJob != null || isCurrentDeviceInvited()) return
    val exchanging = TelegramLoginStateReducer.beginExchange(
        current = uiState.telegramLoginState,
    ) ?: return
    val language = uiState.personalizationSettings.language
    launchAuthenticatedSessionOperation(
        replaceDeviceRead = false,
        onStarted = {
            uiState = uiState.copy(
                telegramLoginState = exchanging,
                accountSecurityState = AccountSecurityUiState(),
            )
            recordAppLog("auth", message = "telegram_link_exchange_start")
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().linkTelegram(accountSecurityContext(token), idToken)
            }
        },
        onSuccess = { user ->
            telegramAuthPurposeState.invalidate()
            recordAppLog("auth", message = "telegram_link_success")
            applyAccountSecurityUser(
                user = user,
                telegramState = TelegramLoginState.Authenticated,
            )
        },
        onFailure = { error ->
            telegramAuthPurposeState.invalidate()
            recordAppLog(
                category = "auth",
                level = "error",
                message = "telegram_link_fail",
                errorType = AppErrorMapper.readableErrorType(error),
            )
            val message = AppErrorMapper.readableAccountSecurityError(language, error)
            uiState = uiState.copy(
                telegramLoginState = TelegramLoginStateReducer.error(message, TelegramAuthPurpose.LINK),
            )
        },
    )
}

internal fun AppUiRuntime.submitLogin() {
    val form = uiState.loginForm
    if (
        form.isLoading ||
        sessionOperationJob != null ||
        telegramAuthPurposeState.currentAttemptId != null
    ) {
        return
    }
    val language = uiState.personalizationSettings.language
    val error = AuthStepReducer.validateLogin(form, language)
    if (error != null) {
        uiState = uiState.copy(loginForm = form.copy(error = error))
        return
    }

    val loginStartedAt = SystemClock.elapsedRealtime()
    var loginResponseTimeMs: Long? = null
    launchSessionOperation(
        onStarted = {
            uiState = uiState.copy(
                loginForm = form.copy(isLoading = true, error = null),
                inlineMessage = null,
            )
            recordAppLog("auth", message = "login_start")
        },
        operation = {
            val result = authFlowCoordinator().login(
                form = form,
                baseState = uiState,
                language = language,
                deviceId = backendDeviceId.ifBlank { null },
            )
            loginResponseTimeMs = elapsedSince(loginStartedAt)
            result
        },
        onSuccess = {
            recordAppLog("auth", message = "login_success", apiResponseTimeMs = loginResponseTimeMs)
            startAppNotificationPolling()
            syncFcmTokenIfAvailable()
            maybeUploadLogsAutomatically()
        },
        onFailure = { error ->
            recordAppLog(
                category = "auth",
                level = "error",
                message = "login_fail",
                details = error.message,
                errorType = AppErrorMapper.readableErrorType(error),
                apiResponseTimeMs = loginResponseTimeMs ?: elapsedSince(loginStartedAt),
            )
            uiState = uiState.copy(
                loginForm = uiState.loginForm.copy(
                    isLoading = false,
                    error = AppErrorMapper.readableAuthError(language, error),
                ),
            )
        },
    )
}
