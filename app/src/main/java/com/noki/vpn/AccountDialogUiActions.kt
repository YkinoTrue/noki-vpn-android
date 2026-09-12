package com.noki.vpn

import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import kotlinx.coroutines.launch

internal fun AppUiRuntime.requestLogout() {
    uiState = uiState.copy(dialog = AppDialog.Logout)
}

internal fun AppUiRuntime.requestServerChange(locationCode: String, mode: ServerSelectionMode = ServerSelectionMode.COUNTRY) {
    val dialog = AppDialog.ChangeServer(locationCode.trim(), mode)
    if (isCurrentServerSelection(uiState.userProfile, dialog.locationCode, mode)) return
    if (confirmedServerCode(dialog, uiState.locations) == null) return
    uiState = uiState.copy(dialog = dialog, inlineMessage = null)
}

internal fun AppUiRuntime.requestAppFilterReset() {
    uiState = uiState.copy(dialog = AppDialog.ResetAppFilter, inlineMessage = null)
}

internal fun confirmedServerCode(
    dialog: AppDialog.ChangeServer,
    locations: List<ServerLocation>,
): String? = dialog.locationCode.trim().takeIf { code ->
    when (dialog.mode) {
        ServerSelectionMode.AUTO -> locations.any { it.isOnline }
        ServerSelectionMode.COUNTRY -> code.isNotBlank() && locations.any { it.code.equals(code, true) && it.isOnline }
        ServerSelectionMode.SERVER -> code.isNotBlank() && locations.any { location ->
            location.servers.any { it.id == code && it.isOnline }
        }
    }
}

internal fun AppUiRuntime.requestAccountDeletion() {
    uiState = uiState.copy(dialog = AppDialog.DeleteAccount(), inlineMessage = null)
}

internal fun AppUiRuntime.requestTelegramUnlink() {
    if (!uiState.userProfile.telegramLinked) return
    if (isCurrentDeviceInvited()) {
        showCurrentDeviceAccessDenied()
        return
    }
    uiState = uiState.copy(dialog = AppDialog.UnlinkTelegram(), inlineMessage = null)
}

internal fun AppUiRuntime.dismissDialog() {
    val activeDialog = uiState.dialog
    if (
        (activeDialog is AppDialog.DeleteAccount && activeDialog.isDeleting) ||
        (activeDialog is AppDialog.UnlinkTelegram && activeDialog.isUnlinking)
    ) {
        return
    }
    uiState = uiState.copy(dialog = null)
}

internal fun AppUiRuntime.confirmDialog() {
    when (val dialog = uiState.dialog) {
        AppDialog.Logout -> confirmLogoutDialog()
        AppDialog.LogoutOthers -> confirmLogoutOthersDialog()
        is AppDialog.RemoveDevice -> confirmRemoveDeviceDialog(dialog.deviceId)
        is AppDialog.RenameDevice -> Unit
        AppDialog.AccessDenied -> dismissDialog()
        AppDialog.FreeTrafficLimitReached -> {
            uiState = uiState.copy(dialog = null, inlineMessage = null)
            openScreen(AppDestination.PLANS)
        }
        AppDialog.DeviceLimitReached -> {
            uiState = uiState.copy(dialog = null, inlineMessage = null)
            openScreen(AppDestination.DEVICES)
        }
        AppDialog.EmptySelectedApps -> {
            uiState = uiState.copy(dialog = null, inlineMessage = null)
            openScreen(AppDestination.APP_FILTER)
        }
        AppDialog.VpnConflict -> dismissDialog()
        AppDialog.ResetAppFilter -> {
            uiState = uiState.copy(dialog = null, inlineMessage = null)
            clearPackageSelection()
        }
        is AppDialog.ChangeServer -> {
            val selectedCode = confirmedServerCode(dialog, uiState.locations)
            uiState = uiState.copy(dialog = null, inlineMessage = null)
            selectedCode?.let { selectServer(it, dialog.mode) }
        }
        is AppDialog.DeleteAccount -> confirmAccountDeletion(dialog)
        is AppDialog.UnlinkTelegram -> confirmTelegramUnlink(dialog)
        null -> Unit
    }
}

internal fun AppUiRuntime.confirmTelegramUnlink(dialog: AppDialog.UnlinkTelegram) {
    if (dialog.isUnlinking || telegramAuthPurposeState.currentAttemptId != null) return
    val language = uiState.personalizationSettings.language
    launchAuthenticatedSessionOperation(
        replaceDeviceRead = true,
        onStarted = {
            uiState = uiState.copy(
                dialog = dialog.copy(isUnlinking = true, error = null),
                inlineMessage = null,
            )
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                accountSecurityCoordinator().unlinkTelegram(accountSecurityContext(token))
            }
        },
        onSuccess = { user ->
            uiState = uiState.copy(dialog = null)
            applyAccountSecurityUser(user)
        },
        onFailure = { error ->
            uiState = uiState.copy(
                dialog = AppDialog.UnlinkTelegram(
                    error = AppErrorMapper.readableAccountSecurityError(language, error),
                ),
            )
        },
    )
}

internal fun AppUiRuntime.confirmAccountDeletion(dialog: AppDialog.DeleteAccount) {
    if (dialog.isDeleting || telegramAuthPurposeState.currentAttemptId != null) return
    val language = uiState.personalizationSettings.language
    launchAuthenticatedSessionOperation(
        replaceDeviceRead = true,
        onStarted = { uiState = uiState.copy(dialog = dialog.copy(isDeleting = true, error = null)) },
        operation = { accountDeletionCoordinator.deleteAccount() },
        onSuccess = { result ->
            when (result) {
                AccountDeletionCoordinator.Result.Success,
                AccountDeletionCoordinator.Result.LogoutRequired -> logout()
                is AccountDeletionCoordinator.Result.Failure -> {
                    uiState = uiState.copy(
                        dialog = AppDialog.DeleteAccount(
                            error = AppErrorMapper.readableNetworkError(language, result.error),
                        ),
                    )
                }
            }
        },
        onFailure = { error ->
            uiState = uiState.copy(
                dialog = AppDialog.DeleteAccount(
                    error = AppErrorMapper.readableNetworkError(language, error),
                ),
            )
        },
    )
}

internal fun AppUiRuntime.confirmLogoutDialog() {
    val authSnapshot = authSessionCoordinator.snapshot()
    val token = authSnapshot.accessToken
    val currentDeviceId = backendDeviceId.ifBlank { null }
    val currentDeviceKey = backendDeviceKey.ifBlank { null }
    uiState = uiState.copy(dialog = null, inlineMessage = null)
    if (token.isNullOrBlank() || (currentDeviceId == null && currentDeviceKey == null)) {
        logout()
        return
    }
    val context = deviceActionContext(token)
    scope.launch {
        deviceActionCoordinator().logoutCurrent(context)
    }
    logout()
}

internal fun AppUiRuntime.confirmLogoutOthersDialog() {
    val language = uiState.personalizationSettings.language
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = { uiState = uiState.copy(dialog = null, inlineMessage = null) },
        operation = { attempt ->
            deviceActionCoordinator(attempt).clearOtherDevices(
                deviceActionContext(attempt.accessToken),
            )
        },
        onSuccess = { result ->
            applyDeviceListActionResult(
                result = result,
                language = language,
                successMessage = tr(language, "Остальные устройства удалены", "Other devices removed"),
            )
        },
        onFailure = { error ->
            uiState = uiState.copy(
                dialog = null,
                inlineMessage = AppErrorMapper.readableNetworkError(language, error),
            )
        },
    )
}

internal fun AppUiRuntime.confirmRemoveDeviceDialog(deviceId: String) {
    val language = uiState.personalizationSettings.language
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = { uiState = uiState.copy(dialog = null, inlineMessage = null) },
        operation = { attempt ->
            deviceActionCoordinator(attempt).deleteDevice(
                deviceActionContext(attempt.accessToken),
                deviceId,
            )
        },
        onSuccess = { result ->
            applyDeviceListActionResult(
                result = result,
                language = language,
                successMessage = tr(language, "Устройство удалено", "Device removed"),
            )
        },
        onFailure = { error ->
            uiState = uiState.copy(
                dialog = null,
                inlineMessage = AppErrorMapper.readableNetworkError(language, error),
            )
        },
    )
}
