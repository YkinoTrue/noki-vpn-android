package com.noki.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCallbackPolicyTest {
    @Test
    fun staleCallbackIsIgnoredWithoutCancellingCurrentAttempt() {
        assertTrue(
            TelegramCallbackPolicy.shouldIgnore(
                TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            ),
        )
        assertFalse(
            TelegramCallbackPolicy.shouldIgnore(
                TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.ProviderError("invalid_callback")),
            ),
        )
        assertFalse(TelegramCallbackPolicy.shouldIgnore(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.ProviderError("stale_login_callback")),
        ))
    }

    @Test
    fun acceptsOnlyRegisteredNativeOrBrowserCallbacks() {
        assertTrue(
            TelegramCallbackPolicy.accepts(
                "https://app3992881250-login.tg.dev/tglogin?code=authorization-code",
            ),
        )
        assertTrue(
            TelegramCallbackPolicy.accepts(
                "https://APP3992881250-LOGIN.TG.DEV:443/tglogin?error=access_denied",
            ),
        )
        assertTrue(
            TelegramCallbackPolicy.accepts(
                "noki://telegram/browser?state=${"S".repeat(43)}",
            ),
        )

        listOf(
            null,
            "",
            "not a uri",
            "tg:opaque-callback",
            "noki://evil/browser?state=x",
            "noki://telegram/other?state=x",
            "noki://user@telegram/browser?state=x",
            "http://app3992881250-login.tg.dev/tglogin?code=x",
            "https://evil.app3992881250-login.tg.dev/tglogin?code=x",
            "https://app3992881250-login.tg.dev.evil.example/tglogin?code=x",
            "https://user@app3992881250-login.tg.dev/tglogin?code=x",
            "https://app3992881250-login.tg.dev:8443/tglogin?code=x",
            "https://app3992881250-login.tg.dev/%74glogin?code=x",
            "https://app3992881250-login.tg.dev/tglogin/extra?code=x",
            "https://app3992881250-login.tg.dev/other?code=x",
        ).forEach { callback ->
            assertFalse("Unexpectedly accepted: $callback", TelegramCallbackPolicy.accepts(callback))
        }
    }
}
