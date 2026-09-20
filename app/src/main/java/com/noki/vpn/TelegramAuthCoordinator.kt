package com.noki.vpn

import com.noki.vpn.data.TelegramAuthApi
import com.noki.vpn.data.TelegramOidcApi
import kotlinx.coroutines.CancellationException

internal class TelegramAuthCoordinator(
    private val authApi: TelegramAuthApi,
    private val oidcApi: TelegramOidcApi,
    private val authenticatedSessionCoordinator: AuthenticatedSessionCoordinator,
) {
    suspend fun authorizationUrl(codeChallenge: String, clientState: String, browser: Boolean): String =
        if (browser) {
            oidcApi.startTelegramBrowserOidc(codeChallenge, clientState)
        } else {
            oidcApi.startTelegramOidc(codeChallenge, clientState)
        }

    suspend fun resolveCallback(result: TelegramLoginCallbackResult): TelegramLoginResult = when (result) {
        TelegramLoginCallbackResult.Cancelled -> TelegramLoginResult.Cancelled
        is TelegramLoginCallbackResult.Failure -> TelegramLoginResult.Failure(result.reason.code)
        is TelegramLoginCallbackResult.AuthorizationCode -> exchange {
            oidcApi.exchangeTelegramOidcCode(result.code, result.codeVerifier)
        }
        is TelegramLoginCallbackResult.BrowserState -> exchange {
            oidcApi.exchangeTelegramBrowserOidc(result.state, result.codeVerifier)
        }
    }

    suspend fun login(
        idToken: String,
        deviceId: String?,
        preparedState: AppUiState,
    ): AuthenticatedSessionCoordinator.Result {
        require(idToken.isNotBlank()) { "telegram_id_token_missing" }
        val tokens = authApi.telegramLogin(
            idToken = idToken,
            deviceId = deviceId?.takeIf { it.isNotBlank() },
        )
        return authenticatedSessionCoordinator.complete(tokens, preparedState)
    }

    private suspend fun exchange(loadIdToken: suspend () -> String): TelegramLoginResult = try {
        TelegramLoginResult.Success(loadIdToken())
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TelegramLoginResult.Failure("telegram_exchange_failed")
    }
}
