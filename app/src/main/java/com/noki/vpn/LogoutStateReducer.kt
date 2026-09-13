package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnConnectionState

object LogoutStateReducer {
    fun reduce(current: AppUiState): AppUiState {
        val language = current.personalizationSettings.language
        return current.copy(
            isAuthenticated = false,
            isRefreshingData = false,
            screenStack = listOf(AppDestination.LOGIN),
            authStep = AuthStep.WELCOME,
            loginForm = LoginFormState(),
            registrationForm = RegistrationFormState(),
            passwordRecoveryForm = PasswordRecoveryFormState(),
            passwordRecoveryPurpose = PasswordRecoveryPurpose.LOGIN,
            accountSecurityState = AccountSecurityUiState(),
            telegramLoginState = TelegramLoginState.Idle,
            dialog = null,
            userProfile = UserProfile(
                selectedCountryCode = current.userProfile.selectedCountryCode,
                serverSelectionMode = current.userProfile.serverSelectionMode,
                selectedNodeId = current.userProfile.selectedNodeId,
            ),
            devices = emptyList(),
            endpointOptionsCountryCode = null,
            appNotificationHistory = emptyList(),
            hasUnreadAppNotifications = false,
            currentDeviceAccessRole = "owner",
            isAndroidUpdateAvailable = false,
            androidUpdate = AndroidUpdateUiState(
                currentVersionName = current.androidUpdate.currentVersionName,
            ),
            isUploadingAvatar = false,
            avatarUploadMessage = null,
            pendingAvatarCropUri = null,
            isUploadingLogs = false,
            logUploadMessage = null,
            inviteDeviceForm = InviteDeviceFormState(),
            incyDevices = IncyDevicesUiState(),
            connectionState = VpnConnectionState.DISCONNECTED,
            connectedAtMillis = null,
            connectionReason = "",
            inlineMessage = if (language == AppLanguage.RU) {
                "Вы вышли из аккаунта"
            } else {
                "You are signed out"
            },
            profile = current.profile.copy(uuid = ""),
        )
    }
}
