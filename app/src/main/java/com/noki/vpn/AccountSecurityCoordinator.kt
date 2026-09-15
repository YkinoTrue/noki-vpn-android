package com.noki.vpn

import com.noki.vpn.data.AccountSecurityApi
import com.noki.vpn.data.BackendAccountPasswordChange
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendUser
import kotlinx.coroutines.CancellationException

internal data class AccountSecurityContext(
    val accessToken: String,
    val currentDeviceId: String?,
    val currentDeviceKey: String?,
    val isOwner: Boolean,
)

internal class AccountSecurityCoordinator(
    private val api: AccountSecurityApi,
    private val authSessionCoordinator: AuthSessionCoordinator,
) {
    suspend fun sendEmailCode(
        context: AccountSecurityContext, email: String,
        currentEmail: String?, currentVerificationCode: String?,
    ): Int {
        context.requireOwner()
        return api.sendAccountEmailCode(
            token = context.accessToken,
            email = email,
            currentEmail = currentEmail,
            currentVerificationCode = currentVerificationCode,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
    }

    suspend fun changeEmail(
        context: AccountSecurityContext,
        email: String,
        verificationCode: String,
        currentEmail: String?,
        currentVerificationCode: String?,
    ): BackendUser {
        context.requireOwner()
        return api.changeAccountEmail(
            token = context.accessToken,
            email = email,
            currentEmail = currentEmail,
            currentVerificationCode = currentVerificationCode,
            verificationCode = verificationCode,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
    }

    suspend fun changePassword(
        context: AccountSecurityContext,
        authAttempt: AuthSessionAttempt,
        currentPassword: String?,
        newPassword: String,
    ): BackendAccountPasswordChange {
        context.requireOwner()
        val result = api.changeAccountPassword(
            token = context.accessToken,
            currentPassword = currentPassword,
            newPassword = newPassword,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
        result.tokens?.let { tokens ->
            if (!authSessionCoordinator.commitIfCurrent(authAttempt, tokens)) {
                throw CancellationException("Account session changed during password update")
            }
        }
        return result
    }

    suspend fun changeUsername(
        context: AccountSecurityContext,
        username: String,
    ): BackendUser {
        context.requireOwner()
        return api.changeAccountUsername(
            token = context.accessToken,
            username = username,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
    }

    suspend fun linkTelegram(
        context: AccountSecurityContext,
        idToken: String,
    ): BackendUser {
        context.requireOwner()
        return api.linkTelegramAccount(
            token = context.accessToken,
            idToken = idToken,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
    }

    suspend fun unlinkTelegram(context: AccountSecurityContext): BackendUser {
        context.requireOwner()
        return api.unlinkTelegramAccount(
            token = context.accessToken,
            currentDeviceId = context.currentDeviceId,
            currentDeviceKey = context.currentDeviceKey,
        )
    }

    private fun AccountSecurityContext.requireOwner() {
        if (!isOwner) throw BackendException("owner_device_required", 403)
    }
}
