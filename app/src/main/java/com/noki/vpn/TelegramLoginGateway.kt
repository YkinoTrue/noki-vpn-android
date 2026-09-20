package com.noki.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal sealed interface TelegramLoginResult {
    class Success(val idToken: String) : TelegramLoginResult {
        override fun toString(): String = "Success(idToken=<redacted>)"
    }

    data object Cancelled : TelegramLoginResult

    data class Failure(val code: String) : TelegramLoginResult
}

internal data class TelegramLoginStartRequest(
    val codeChallenge: String,
    val clientState: String,
)

internal sealed interface TelegramCallbackFailure {
    val code: String
    data object Stale : TelegramCallbackFailure { override val code = "stale_login_callback" }
    data object InvalidBrowserState : TelegramCallbackFailure { override val code = "invalid_browser_state" }
    data object NotHandled : TelegramCallbackFailure { override val code = "callback_not_handled" }
    data class ProviderError(override val code: String) : TelegramCallbackFailure
}

internal sealed interface TelegramLoginCallbackResult {
    data class AuthorizationCode(
        val code: String,
        val codeVerifier: String,
    ) : TelegramLoginCallbackResult {
        override fun toString(): String = "AuthorizationCode(code=<redacted>, codeVerifier=<redacted>)"
    }

    data class BrowserState(
        val state: String,
        val codeVerifier: String,
    ) : TelegramLoginCallbackResult {
        override fun toString(): String = "BrowserState(state=<redacted>, codeVerifier=<redacted>)"
    }

    data object Cancelled : TelegramLoginCallbackResult

    data class Failure(val reason: TelegramCallbackFailure) : TelegramLoginCallbackResult
}

internal class TelegramLoginGateway(
    private val randomBytes: () -> ByteArray = {
        ByteArray(PKCE_ENTROPY_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    class ExternalFlowTimeoutLease internal constructor()

    private data class ActiveSession(
        val verifier: String,
        val clientState: String,
        var browser: Boolean = false,
        val pendingNativeCodes: MutableSet<String> = mutableSetOf(),
    )

    private var activeSession: ActiveSession? = null
    private var externalFlowPending = false
    private var externalFlowStopped = false
    private var activeTimeoutLease: ExternalFlowTimeoutLease? = null

    @Synchronized
    fun begin(): TelegramLoginStartRequest {
        completeExternalFlow()
        val entropy = randomBytes()
        require(entropy.size >= PKCE_ENTROPY_BYTES) { "telegram_pkce_entropy_too_short" }
        val verifier = entropy.base64Url()
        require(verifier.length in PKCE_LENGTH_RANGE) { "telegram_pkce_verifier_invalid" }
        val challenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .base64Url()
        val clientState = MessageDigest.getInstance("SHA-256")
            .digest("telegram-state\u0000$verifier".toByteArray(Charsets.US_ASCII))
            .base64Url()
        activeSession = ActiveSession(verifier = verifier, clientState = clientState)
        return TelegramLoginStartRequest(
            codeChallenge = challenge,
            clientState = clientState,
        )
    }

    fun openNative(
        activity: Activity,
        telegramUrl: String,
    ): Boolean {
        val uri = telegramUrl.toUri()
        require(isAllowedTelegramUrl(uri)) { "telegram_launch_url_invalid" }
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openBrowser(
        activity: Activity,
        authorizationUrl: String,
    ) {
        val uri = authorizationUrl.toUri()
        require(isAllowedBrowserUrl(uri)) { "telegram_browser_url_invalid" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun handleLoginResponse(callbackUri: Uri?): TelegramLoginCallbackResult? {
        callbackUri ?: return null
        if (!TelegramCallbackPolicy.accepts(callbackUri.toString())) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
        }
        return if (TelegramCallbackPolicy.isBrowser(callbackUri.toString())) {
            consumeBrowserCallback(
                state = callbackUri.getQueryParameter("state"),
                error = callbackUri.getQueryParameter("error"),
            )
        } else {
            consumeCallback(
                code = callbackUri.getQueryParameter("code"),
                state = callbackUri.getQueryParameter("state"),
                error = callbackUri.getQueryParameter("error"),
            )
        }
    }

    @Synchronized
    internal fun consumeCallback(
        code: String?,
        state: String?,
        error: String?,
    ): TelegramLoginCallbackResult {
        val session = activeSession
        if (session == null ||
            session.browser ||
            (state != null && state.trim() != session.clientState) ||
            (state == null && !externalFlowPending)
        ) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
        }
        if (error != null) {
            if (state == null) return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
            activeSession = null
            return error.toCallbackResult()
        }
        val authorizationCode = code?.trim().orEmpty()
        if (authorizationCode.isBlank() || authorizationCode.length > 2048) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
        }
        // Native Telegram may return only code. PKCE, not an unverified callback,
        // decides whether this candidate belongs to our current authorization.
        if (authorizationCode in session.pendingNativeCodes || session.pendingNativeCodes.size >= 2) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
        }
        session.pendingNativeCodes.add(authorizationCode)
        return TelegramLoginCallbackResult.AuthorizationCode(
            code = authorizationCode,
            codeVerifier = session.verifier,
        )
    }

    @Synchronized
    fun finishNativeCallback(result: TelegramLoginCallbackResult.AuthorizationCode, success: Boolean): Boolean {
        val session = activeSession ?: return false
        if (session.verifier != result.codeVerifier || !session.pendingNativeCodes.remove(result.code)) return false
        if (success) {
            activeSession = null
            completeExternalFlow()
        }
        return true
    }

    @Synchronized
    fun failedNativeCallbackTimeoutLease(): ExternalFlowTimeoutLease? {
        if (externalFlowStopped || activeSession?.pendingNativeCodes?.isNotEmpty() == true) return null
        return resumeTimeoutLease()
    }

    @Synchronized
    internal fun consumeBrowserCallback(
        state: String?,
        error: String?,
    ): TelegramLoginCallbackResult {
        val session = activeSession
        if (session == null || state?.trim() != session.clientState) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale)
        }
        activeSession = null
        error?.let { return it.toCallbackResult() }
        val safeState = state.trim()
        if (!SAFE_BROWSER_STATE.matches(safeState)) {
            return TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.InvalidBrowserState)
        }
        return TelegramLoginCallbackResult.BrowserState(
            state = safeState,
            codeVerifier = session.verifier,
        )
    }

    @Synchronized
    fun cancel() {
        activeSession = null
        completeExternalFlow()
    }

    @Synchronized
    fun cancel(expectedClientState: String): Boolean {
        if (activeSession?.clientState != expectedClientState) return false
        cancel()
        return true
    }

    @Synchronized
    fun cancelPreparationIfPending(): Boolean {
        if (activeSession == null || externalFlowPending) return false
        cancel()
        return true
    }

    @Synchronized
    fun markExternalFlowStarted(expectedClientState: String, browser: Boolean = false): Boolean {
        if (activeSession?.clientState != expectedClientState) return false
        activeSession?.browser = browser
        activeTimeoutLease = null
        externalFlowPending = true
        externalFlowStopped = false
        return true
    }

    @Synchronized
    fun markHostStopped() {
        if (externalFlowPending) externalFlowStopped = true
    }

    @Synchronized
    fun resumeTimeoutLease(): ExternalFlowTimeoutLease? {
        if (!externalFlowPending) return null
        val lease = ExternalFlowTimeoutLease()
        activeTimeoutLease = lease
        externalFlowStopped = false
        return lease
    }

    @Synchronized
    fun expireExternalFlow(timeoutLease: ExternalFlowTimeoutLease): Boolean {
        if (
            timeoutLease !== activeTimeoutLease ||
            !externalFlowPending ||
            externalFlowStopped ||
            activeSession?.pendingNativeCodes?.isNotEmpty() == true
        ) {
            return false
        }
        activeSession = null
        completeExternalFlow()
        return true
    }

    @Synchronized
    fun completeExternalFlow() {
        activeTimeoutLease = null
        externalFlowPending = false
        externalFlowStopped = false
    }

    @Synchronized
    fun completeExternalFlow(expectedClientState: String): Boolean {
        if (activeSession?.clientState != expectedClientState) return false
        completeExternalFlow()
        return true
    }

    private fun String.toCallbackResult(): TelegramLoginCallbackResult {
        val safeCode = trim().lowercase()
        return if (safeCode in CANCELLATION_CODES) {
            TelegramLoginCallbackResult.Cancelled
        } else {
            TelegramLoginCallbackResult.Failure(
                TelegramCallbackFailure.ProviderError(safeCode.takeIf(SAFE_ERROR_CODE::matches) ?: "telegram_callback_error"),
            )
        }
    }

    private fun isAllowedTelegramUrl(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() != "tg" || uri.userInfo != null || uri.fragment != null) {
            return false
        }
        return when (uri.host?.lowercase()) {
            "oauth" -> !uri.getQueryParameter("token").isNullOrBlank()
            "resolve" -> {
                uri.getQueryParameter("domain") == "oauth" &&
                    !uri.getQueryParameter("startapp").isNullOrBlank()
            }
            else -> false
        }
    }

    private fun isAllowedBrowserUrl(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("oauth.telegram.org", ignoreCase = true) &&
            uri.path == "/auth" &&
            uri.userInfo == null &&
            uri.fragment == null &&
            (uri.port == -1 || uri.port == 443)

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private companion object {
        const val PKCE_ENTROPY_BYTES = 32
        val PKCE_LENGTH_RANGE = 43..128
        val CANCELLATION_CODES = setOf(
            "access_denied",
            "cancelled",
            "canceled",
            "user_cancelled",
            "user_canceled",
        )
        val SAFE_ERROR_CODE = Regex("[a-z0-9_.-]{1,64}")
        val SAFE_BROWSER_STATE = Regex("[A-Za-z0-9_-]{32,128}")
    }
}
