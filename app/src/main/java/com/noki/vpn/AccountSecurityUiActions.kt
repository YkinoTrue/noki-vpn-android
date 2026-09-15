package com.noki.vpn

import com.noki.vpn.data.BackendUser

internal fun AppUiRuntime.openAccountEmailChange() {
    if (isCurrentDeviceInvited()) {
        showCurrentDeviceAccessDenied()
        return
    }
    accountSecurityUiWorkflow.invalidate()
    val stack = if (uiState.currentDestination == AppDestination.ACCOUNT_CREDENTIAL_CHANGE) {
        uiState.screenStack
    } else {
        uiState.screenStack + AppDestination.ACCOUNT_CREDENTIAL_CHANGE
    }
    uiState = uiState.copy(
        accountSecurityState = AccountSecurityStateReducer.email(uiState.accountSecurityState, "", uiState.userProfile.hasRealEmail),
        screenStack = stack,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.openAccountPasswordChange() {
    if (isCurrentDeviceInvited()) {
        showCurrentDeviceAccessDenied()
        return
    }
    accountSecurityUiWorkflow.invalidate()
    if (uiState.userProfile.hasPassword) {
        val staleCooldown = passwordRecoveryCooldownJob
        passwordRecoveryCooldownJob = null
        staleCooldown?.cancel()
        val staleOperation = passwordRecoveryOperationJob
        passwordRecoveryOperationJob = null
        staleOperation?.cancel()
        accountRecoveryWorkflow.invalidate()
        val stack = if (uiState.currentDestination == AppDestination.PASSWORD_RECOVERY) {
            uiState.screenStack
        } else {
            uiState.screenStack + AppDestination.PASSWORD_RECOVERY
        }
        uiState = uiState.copy(
            passwordRecoveryForm = PasswordRecoveryFormState(),
            passwordRecoveryPurpose = PasswordRecoveryPurpose.ACCOUNT_SECURITY,
            accountSecurityState = AccountSecurityUiState(),
            screenStack = stack,
            inlineMessage = null,
        )
        return
    }
    val stack = if (uiState.currentDestination == AppDestination.ACCOUNT_CREDENTIAL_CHANGE) {
        uiState.screenStack
    } else {
        uiState.screenStack + AppDestination.ACCOUNT_CREDENTIAL_CHANGE
    }
    uiState = uiState.copy(
        accountSecurityState = AccountSecurityStateReducer.password(uiState.accountSecurityState),
        screenStack = stack,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.openAccountUsernameDialog() {
    if (isCurrentDeviceInvited()) {
        showCurrentDeviceAccessDenied()
        return
    }
    accountSecurityUiWorkflow.invalidate()
    uiState = uiState.copy(
        accountSecurityState = AccountSecurityStateReducer.username(
            uiState.accountSecurityState,
            uiState.userProfile.username,
        ),
    )
}

internal fun AppUiRuntime.dismissAccountUsernameDialog() {
    accountSecurityUiWorkflow.invalidate()
    uiState = uiState.copy(
        accountSecurityState = AccountSecurityStateReducer.dismiss(uiState.accountSecurityState),
    )
}

internal fun AppUiRuntime.closeAccountSecurityAction() {
    accountSecurityUiWorkflow.invalidate()
    val stack = if (uiState.currentDestination == AppDestination.ACCOUNT_CREDENTIAL_CHANGE &&
        uiState.screenStack.size > 1
    ) {
        uiState.screenStack.dropLast(1)
    } else {
        uiState.screenStack
    }
    uiState = uiState.copy(
        accountSecurityState = AccountSecurityStateReducer.dismiss(uiState.accountSecurityState),
        screenStack = stack,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.updateAccountEmail(value: String) = accountSecurityUiWorkflow.updateEmail(value)

internal fun AppUiRuntime.updateAccountEmailCode(value: String) = accountSecurityUiWorkflow.updateEmailCode(value)

internal fun AppUiRuntime.requestAccountEmailCode() = accountSecurityUiWorkflow.requestEmailCode()

internal fun AppUiRuntime.submitAccountEmail() = accountSecurityUiWorkflow.submitEmail()

internal fun AppUiRuntime.updateAccountNewPassword(value: String) = accountSecurityUiWorkflow.updateNewPassword(value)

internal fun AppUiRuntime.updateAccountPasswordConfirmation(value: String) =
    accountSecurityUiWorkflow.updatePasswordConfirmation(value)

internal fun AppUiRuntime.submitAccountPassword() = accountSecurityUiWorkflow.submitPassword()

internal fun AppUiRuntime.updateAccountUsername(value: String) = accountSecurityUiWorkflow.updateUsername(value)

internal fun AppUiRuntime.submitAccountUsername() = accountSecurityUiWorkflow.submitUsername()

internal fun AppUiRuntime.applyAccountSecurityUser(
    user: BackendUser,
    telegramState: TelegramLoginState = uiState.telegramLoginState,
    returnToSecurity: Boolean = false,
) {
    accountSecurityUiWorkflow.invalidate()
    val stack = if (returnToSecurity &&
        uiState.currentDestination == AppDestination.ACCOUNT_CREDENTIAL_CHANGE &&
        uiState.screenStack.size > 1
    ) {
        uiState.screenStack.dropLast(1)
    } else {
        uiState.screenStack
    }
    val state = uiState.copy(
        userProfile = uiState.userProfile.copy(
            username = user.username,
            email = user.email,
            avatarUri = user.avatarUrl,
            hasRealEmail = user.hasRealEmail,
            hasPassword = user.hasPassword,
            telegramLinked = user.telegramLinked,
        ),
        telegramLoginState = telegramState,
        accountSecurityState = AccountSecurityUiState(),
        screenStack = stack,
        inlineMessage = null,
    )
    persistBackendState(state)
    applyAndPersist(state)
}

internal fun AppUiRuntime.accountSecurityCoordinator(): AccountSecurityCoordinator {
    return AccountSecurityCoordinator(
        api = backendApi,
        authSessionCoordinator = authSessionCoordinator,
    )
}

internal fun AppUiRuntime.accountSecurityContext(accessToken: String): AccountSecurityContext {
    return AccountSecurityContext(
        accessToken = accessToken,
        currentDeviceId = backendDeviceId.ifBlank { null },
        currentDeviceKey = backendDeviceKey.ifBlank { null },
        isOwner = !isCurrentDeviceInvited(),
    )
}
