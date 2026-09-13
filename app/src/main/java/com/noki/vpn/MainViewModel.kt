package com.noki.vpn

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.noki.vpn.data.AccentPalette
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BillingCycle
import com.noki.vpn.data.GlassMode
import com.noki.vpn.data.HomeLayoutVariant
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.vpn.VpnRuntimeMode

class MainViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val runtime = AppUiRuntime(application, savedStateHandle, viewModelScope)
    internal val telegramLoginGateway: TelegramLoginGateway
        get() = runtime.telegramLoginGateway

    val pendingVpnStartMode: VpnRuntimeMode
        get() = runtime.pendingVpnStartMode

    val uiState: AppUiState
        get() = runtime.uiState

    fun setPendingVpnStartMode(mode: VpnRuntimeMode) = runtime.setPendingVpnStartMode(mode)

    fun goBack() = runtime.goBack()

    fun openScreen(destination: AppDestination) = runtime.openScreen(destination)

    fun openTopLevelScreen(destination: AppDestination) = runtime.openTopLevelScreen(destination)

    fun markAndroidUpdateAvailable() = runtime.markAndroidUpdateAvailable()

    fun refreshAndroidUpdateStatus() = runtime.refreshAndroidUpdateStatus()

    fun preparePaymentCheckout() = runtime.paymentCheckoutWorkflow.prepare()
    fun selectPaymentMethod(code: String) = runtime.paymentCheckoutWorkflow.selectMethod(code)
    fun createPayment(planCode: String) = runtime.paymentCheckoutWorkflow.submit(planCode)
    fun refreshPaymentStatus() = runtime.paymentCheckoutWorkflow.refreshStatus()
    fun stopPaymentStatusChecks() = runtime.paymentCheckoutWorkflow.stopChecking()
    fun reopenPayment() = runtime.paymentCheckoutWorkflow.reopen()
    fun consumePaymentUrl() = runtime.paymentCheckoutWorkflow.consumeLaunchUrl()
    fun paymentBrowserFailed() = runtime.paymentCheckoutWorkflow.browserFailed()
    fun dismissPaymentResult() = runtime.paymentCheckoutWorkflow.dismissResult()

    fun installAndroidUpdate() = runtime.installAndroidUpdate()

    fun showCurrentDeviceAccessDenied() = runtime.showCurrentDeviceAccessDenied()

    fun showVpnConflict() = runtime.showVpnConflict()

    fun updateLoginEmail(value: String) = runtime.updateLoginEmail(value)

    fun updateLoginPassword(value: String) = runtime.updateLoginPassword(value)

    fun openEmailLogin() = runtime.openEmailLogin()

    fun openRegistrationFlow() = runtime.openRegistrationFlow()

    fun beginGoogleLogin(): Boolean = runtime.beginGoogleLogin()

    internal fun handleGoogleLoginResult(result: GoogleLoginResult) = runtime.handleGoogleLoginResult(result)

    fun beginTelegramLogin(): Boolean = runtime.beginTelegramLogin()

    fun beginTelegramLink(): Boolean = runtime.beginTelegramLink()

    internal suspend fun prepareTelegramLogin(codeChallenge: String, clientState: String): String? =
        runtime.prepareTelegramLogin(codeChallenge, clientState)

    internal suspend fun prepareTelegramBrowserLogin(codeChallenge: String, clientState: String): String? =
        runtime.prepareTelegramBrowserLogin(codeChallenge, clientState)

    internal fun handleTelegramLoginCallback(result: TelegramLoginCallbackResult) = runtime.handleTelegramLoginCallback(result)

    internal fun cancelTelegramLoginFlow() = runtime.cancelTelegramLoginFlow()

    internal fun handleTelegramLoginResult(result: TelegramLoginResult) = runtime.handleTelegramLoginResult(result)

    internal fun expireTelegramLoginAfterDelay(lease: TelegramLoginGateway.ExternalFlowTimeoutLease) =
        runtime.expireTelegramLoginAfterDelay(lease)

    fun openAccountEmailChange() = runtime.openAccountEmailChange()

    fun openAccountPasswordChange() = runtime.openAccountPasswordChange()

    fun openAccountUsernameDialog() = runtime.openAccountUsernameDialog()

    fun dismissAccountUsernameDialog() = runtime.dismissAccountUsernameDialog()

    fun closeAccountSecurityAction() = runtime.closeAccountSecurityAction()

    fun updateAccountEmail(value: String) = runtime.updateAccountEmail(value)

    fun updateAccountEmailCode(value: String) = runtime.updateAccountEmailCode(value)

    fun requestAccountEmailCode() = runtime.requestAccountEmailCode()

    fun submitAccountEmail() = runtime.submitAccountEmail()

    fun updateAccountNewPassword(value: String) = runtime.updateAccountNewPassword(value)

    fun updateAccountPasswordConfirmation(value: String) = runtime.updateAccountPasswordConfirmation(value)

    fun submitAccountPassword() = runtime.submitAccountPassword()

    fun updateAccountUsername(value: String) = runtime.updateAccountUsername(value)

    fun submitAccountUsername() = runtime.submitAccountUsername()

    fun updateInviteDeviceCode(value: String) = runtime.updateInviteDeviceCode(value)

    fun dismissGeneratedDeviceInvite() = runtime.dismissGeneratedDeviceInvite()

    fun createDeviceInvite() = runtime.createDeviceInvite()

    fun refreshIncyDevices() = runtime.refreshIncyDevices()

    fun showCreateIncyDevice() = runtime.showCreateIncyDevice()

    fun updateIncyDeviceName(value: String) = runtime.updateIncyDeviceName(value)

    fun createIncyDevice() = runtime.createIncyDevice()

    fun openIncyDevice(deviceId: String) = runtime.openIncyDevice(deviceId)

    fun renameIncyDevice() = runtime.renameIncyDevice()

    fun reissueIncyDevice() = runtime.reissueIncyDevice()

    fun deleteIncyDevice() = runtime.deleteIncyDevice()

    fun dismissIncyDeviceDialog() = runtime.dismissIncyDeviceDialog()

    fun acceptDeviceInvite() = runtime.acceptDeviceInvite()

    fun acceptScannedInviteCode(rawValue: String) = runtime.acceptScannedInviteCode(rawValue)

    fun updateRegistrationUsername(value: String) = runtime.updateRegistrationUsername(value)

    fun updateRegistrationEmail(value: String) = runtime.updateRegistrationEmail(value)

    fun updateRegistrationVerificationCode(value: String) = runtime.updateRegistrationVerificationCode(value)

    fun updateRegistrationPassword(value: String) = runtime.updateRegistrationPassword(value)

    fun requestRegistrationCode() = runtime.requestRegistrationCode()

    fun previousRegistrationStep() = runtime.previousRegistrationStep()

    fun openPasswordRecovery() = runtime.openPasswordRecovery()

    fun updatePasswordRecoveryEmail(value: String) = runtime.updatePasswordRecoveryEmail(value)

    fun updatePasswordRecoveryCode(value: String) = runtime.updatePasswordRecoveryCode(value)

    fun updatePasswordRecoveryPassword(value: String) = runtime.updatePasswordRecoveryPassword(value)

    fun updatePasswordRecoveryPasswordRepeat(value: String) = runtime.updatePasswordRecoveryPasswordRepeat(value)

    fun requestPasswordRecoveryCode() = runtime.requestPasswordRecoveryCode()

    fun submitPasswordRecovery() = runtime.submitPasswordRecovery()

    fun updateRegistrationPasswordRepeat(value: String) = runtime.updateRegistrationPasswordRepeat(value)

    fun submitLogin() = runtime.submitLogin()

    fun submitRegistration() = runtime.submitRegistration()

    fun nextRegistrationStep() = runtime.nextRegistrationStep()

    fun logout() = runtime.logout()

    fun setBillingCycle(cycle: BillingCycle) = runtime.setBillingCycle(cycle)

    fun selectServer(code: String, mode: com.noki.vpn.data.ServerSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY) = runtime.selectServer(code, mode)

    fun refreshServers() = runtime.refreshServers()

    fun refreshServerStats() = runtime.refreshServerStats()

    fun refreshOfflineStats() = runtime.refreshOfflineStats()

    fun refreshAllData(
        showNetworkFailureInline: Boolean = true,
        refreshClientLatency: Boolean = false,
    ) = runtime.refreshAllData(showNetworkFailureInline, refreshClientLatency)

    fun setLanguage(language: AppLanguage) = runtime.setLanguage(language)

    fun setAccentPalette(accentPalette: AccentPalette) = runtime.setAccentPalette(accentPalette)

    fun setHomeLayout(variant: HomeLayoutVariant) = runtime.setHomeLayout(variant)

    fun setGlassMode(mode: GlassMode) = runtime.setGlassMode(mode)

    fun beginAvatarCrop(uri: Uri?) = runtime.beginAvatarCrop(uri)

    fun cancelAvatarCrop() = runtime.cancelAvatarCrop()

    fun uploadCroppedAvatar(
        sourceUri: String,
        previewWidthPx: Float,
        previewHeightPx: Float,
        cropCircleSizePx: Float,
        cropScale: Float,
        cropOffsetX: Float,
        cropOffsetY: Float,
        rotationQuarterTurns: Int,
    ) = runtime.uploadCroppedAvatar(sourceUri, previewWidthPx, previewHeightPx, cropCircleSizePx, cropScale, cropOffsetX, cropOffsetY, rotationQuarterTurns)

    fun deleteAvatar() = runtime.deleteAvatar()

    fun updateFilterMode(mode: AppFilterMode) = runtime.updateFilterMode(mode)

    fun togglePackageSelection(packageName: String) = runtime.togglePackageSelection(packageName)

    fun clearPackageSelection() = runtime.clearPackageSelection()

    fun applyAppRoutingSettings() = runtime.applyAppRoutingSettings()

    fun setYoutubeDirectDpiEnabled(enabled: Boolean) =
        runtime.setYoutubeDirectDpiEnabled(enabled)

    fun toggleBiometric(enabled: Boolean) = runtime.toggleBiometric(enabled)

    fun toggleLoginAlerts(enabled: Boolean) = runtime.toggleLoginAlerts(enabled)

    fun toggleProtectNewDevices(enabled: Boolean) = runtime.toggleProtectNewDevices(enabled)

    fun changeProtocol(protocol: VpnProtocol) = runtime.changeProtocol(protocol)

    fun toggleAutoEndpointSelection(enabled: Boolean) = runtime.toggleAutoEndpointSelection(enabled)

    fun refreshEndpointOptions(
        context: Context,
        force: Boolean = false,
    ) = runtime.refreshEndpointOptions(context, force)

    fun selectManualEndpoint(option: VpnEndpointOption) = runtime.selectManualEndpoint(option)

    fun setLoggingEnabled(enabled: Boolean) = runtime.setLoggingEnabled(enabled)

    fun toggleAnonymousLogs(enabled: Boolean) = runtime.toggleAnonymousLogs(enabled)

    fun updateAlwaysOnInput(value: String) = runtime.updateAlwaysOnInput(value)

    fun updateBypassInput(value: String) = runtime.updateBypassInput(value)

    fun addAlwaysOnDomain() = runtime.addAlwaysOnDomain()

    fun addAlwaysOnDomain(value: String) = runtime.addAlwaysOnDomain(value)

    fun addBypassDomain() = runtime.addBypassDomain()

    fun addBypassDomain(value: String) = runtime.addBypassDomain(value)

    fun updateAlwaysOnDomain(oldDomain: String, newDomain: String) = runtime.updateAlwaysOnDomain(oldDomain, newDomain)

    fun removeAlwaysOnDomain(domain: String) = runtime.removeAlwaysOnDomain(domain)

    fun removeBypassDomain(domain: String) = runtime.removeBypassDomain(domain)

    fun updateBypassDomain(oldDomain: String, newDomain: String) = runtime.updateBypassDomain(oldDomain, newDomain)

    fun requestRemoveDevice(deviceId: String) = runtime.requestRemoveDevice(deviceId)

    fun requestRenameDevice(deviceId: String) = runtime.requestRenameDevice(deviceId)

    fun renameDevice(deviceId: String, name: String) = runtime.renameDevice(deviceId, name)

    fun setDeviceFullAccess(deviceId: String, fullAccess: Boolean) = runtime.setDeviceFullAccess(deviceId, fullAccess)

    fun requestLogoutOthers() = runtime.requestLogoutOthers()

    fun requestLogout() = runtime.requestLogout()

    fun requestServerChange(locationCode: String, mode: com.noki.vpn.data.ServerSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY) = runtime.requestServerChange(locationCode, mode)

    fun requestAppFilterReset() = runtime.requestAppFilterReset()

    fun requestAccountDeletion() = runtime.requestAccountDeletion()

    fun requestTelegramUnlink() = runtime.requestTelegramUnlink()

    fun dismissDialog() = runtime.dismissDialog()

    fun confirmDialog() = runtime.confirmDialog()

    fun clearMessage() = runtime.clearMessage()

    fun uploadLocalLogs() = runtime.uploadLocalLogs()

    fun refreshAppNotificationHistoryState() = runtime.refreshAppNotificationHistoryState()

    fun openAppNotificationHistory() = runtime.openAppNotificationHistory()

    fun deleteAppNotification(notificationId: String) = runtime.deleteAppNotification(notificationId)

    fun updateConnectionState(
        state: VpnConnectionState,
        reason: String = "",
        connectedAtMillis: Long? = null,
        runtimeMode: VpnRuntimeMode = uiState.vpnRuntimeMode,
    ) = runtime.updateConnectionState(state, reason, connectedAtMillis, runtimeMode)

    fun getVpnPermissionIntent(context: Context): Intent? = runtime.getVpnPermissionIntent(context)

    fun startVpn() = runtime.startVpn()

    fun startTemporaryVpn() = runtime.startTemporaryVpn()

    fun disconnect() = runtime.disconnect()
}
