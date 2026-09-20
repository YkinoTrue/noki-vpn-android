package com.noki.vpn

import java.net.URI

internal object TelegramCallbackPolicy {
    private const val NATIVE_CALLBACK_SCHEME = "https"
    private const val NATIVE_CALLBACK_HOST = "app3992881250-login.tg.dev"
    private const val NATIVE_CALLBACK_PATH = "/tglogin"
    private const val BROWSER_CALLBACK_SCHEME = "noki"
    private const val BROWSER_CALLBACK_HOST = "telegram"
    private const val BROWSER_CALLBACK_PATH = "/browser"
    private const val DEFAULT_HTTPS_PORT = 443

    fun accepts(rawUri: String?): Boolean {
        if (rawUri.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return false
        return acceptsNative(uri) || acceptsBrowser(uri)
    }

    fun isBrowser(rawUri: String?): Boolean {
        if (rawUri.isNullOrBlank()) return false
        return runCatching { URI(rawUri) }.getOrNull()?.let(::acceptsBrowser) == true
    }

    fun shouldIgnore(result: TelegramLoginCallbackResult): Boolean =
        result is TelegramLoginCallbackResult.Failure &&
            result.reason == TelegramCallbackFailure.Stale

    private fun acceptsNative(uri: URI): Boolean =
        !uri.isOpaque &&
            uri.scheme.equals(NATIVE_CALLBACK_SCHEME, ignoreCase = true) &&
            uri.host.equals(NATIVE_CALLBACK_HOST, ignoreCase = true) &&
            uri.rawPath == NATIVE_CALLBACK_PATH &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == DEFAULT_HTTPS_PORT)

    private fun acceptsBrowser(uri: URI): Boolean =
        !uri.isOpaque &&
            uri.scheme.equals(BROWSER_CALLBACK_SCHEME, ignoreCase = true) &&
            uri.host.equals(BROWSER_CALLBACK_HOST, ignoreCase = true) &&
            uri.rawPath == BROWSER_CALLBACK_PATH &&
            uri.userInfo == null &&
            uri.port == -1
}
