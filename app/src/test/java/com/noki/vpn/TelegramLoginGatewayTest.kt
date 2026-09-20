package com.noki.vpn

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramLoginGatewayTest {
    @Test
    fun `native code without state can be verified using the active PKCE session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)

        val result = gateway.consumeCallback("native-code", null, null)

        assertTrue(result is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `a native candidate must not consume the verifier before server verification`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)

        gateway.consumeCallback("first-code", request.clientState, null)
        val second = gateway.consumeCallback("genuine-code", request.clientState, null)

        assertTrue(second is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `resume timeout cannot expire during native PKCE verification`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val lease = checkNotNull(gateway.resumeTimeoutLease())
        gateway.consumeCallback("native-code", request.clientState, null)

        assertFalse(gateway.expireExternalFlow(lease))
    }

    @Test
    fun `begin creates S256 challenge and callback consumes verifier once`() {
        val entropy = ByteArray(32) { 7 }
        val gateway = TelegramLoginGateway(randomBytes = { entropy.copyOf() })

        val request = gateway.begin()
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val result = gateway.consumeCallback(
            code = "authorization-code",
            state = request.clientState,
            error = null,
        )

        assertEquals(expectedChallenge, request.codeChallenge)
        assertNotEquals(request.codeChallenge, request.clientState)
        assertEquals(
            TelegramLoginCallbackResult.AuthorizationCode(
                code = "authorization-code",
                codeVerifier = verifier,
            ),
            result,
        )
        assertTrue(gateway.finishNativeCallback(result as TelegramLoginCallbackResult.AuthorizationCode, success = true))
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback(code = "second-code", state = request.clientState, error = null),
        )
    }

    @Test
    fun `missing state native callbacks cannot enter a browser or unlaunched flow`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("code-before-launch", null, null))
        gateway.markExternalFlowStarted(request.clientState, browser = true)
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("native-code", null, null))
        assertTrue(gateway.consumeBrowserCallback(request.clientState, null) is TelegramLoginCallbackResult.BrowserState)
    }

    @Test
    fun `rejected candidate leaves genuine native callback usable and duplicate does not exchange`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val stale = gateway.consumeCallback("stale-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("stale-code", null, null))
        val real = gateway.consumeCallback("real-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(stale, success = false))
        assertEquals(null, gateway.failedNativeCallbackTimeoutLease())
        assertTrue(gateway.finishNativeCallback(real, success = true))
        assertFalse(gateway.finishNativeCallback(stale, success = true))
        assertEquals(null, gateway.resumeTimeoutLease())
    }

    @Test
    fun `native candidate concurrency is bounded and rejected candidate releases a slot`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val first = gateway.consumeCallback("first-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        gateway.consumeCallback("second-code", null, null)
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("third-code", null, null))
        assertTrue(gateway.finishNativeCallback(first, success = false))
        assertTrue(gateway.consumeCallback("third-code", null, null) is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `older native exchange cannot finish a newer login`() {
        var seed = 1
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { seed++.toByte() } })
        val first = gateway.begin()
        gateway.markExternalFlowStarted(first.clientState)
        val old = gateway.consumeCallback("old-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        val oldLease = checkNotNull(gateway.resumeTimeoutLease())
        val current = gateway.begin()
        assertFalse(gateway.expireExternalFlow(oldLease))
        gateway.markExternalFlowStarted(current.clientState)
        assertFalse(gateway.finishNativeCallback(old, success = true))
        val real = gateway.consumeCallback("new-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(real, success = true))
    }

    @Test
    fun `failed native timeout waits for foreground and cannot beat another candidate`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        gateway.markHostStopped()
        val candidate = gateway.consumeCallback("bad-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(candidate, success = false))
        assertEquals(null, gateway.failedNativeCallbackTimeoutLease())
        val lease = checkNotNull(gateway.resumeTimeoutLease())
        val real = gateway.consumeCallback("real-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertFalse(gateway.expireExternalFlow(lease))
        assertTrue(gateway.finishNativeCallback(real, success = true))
    }

    @Test
    fun `native blank state error and malformed code cannot consume a session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        for (candidate in listOf(
            gateway.consumeCallback("code", "", null),
            gateway.consumeCallback(null, null, "access_denied"),
            gateway.consumeCallback("", null, null),
            gateway.consumeCallback("x".repeat(2049), null, null),
        )) {
            assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale), candidate)
        }
        assertTrue(gateway.consumeCallback("valid-code", null, null) is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `new begin invalidates previous PKCE session`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )

        val first = gateway.begin()
        val second = gateway.begin()
        val result = gateway.consumeCallback("authorization-code", second.clientState, null)

        assertNotEquals(first.codeChallenge, second.codeChallenge)
        assertTrue(result is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `cancellation and explicit cancel clear active session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 3 } })
        val first = gateway.begin()

        assertEquals(
            TelegramLoginCallbackResult.Cancelled,
            gateway.consumeCallback(code = null, state = first.clientState, error = "access_denied"),
        )
        val second = gateway.begin()
        gateway.cancel()
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback(code = "authorization-code", state = second.clientState, error = null),
        )
    }

    @Test
    fun `browser callback returns opaque state with verifier and consumes session once`() {
        val entropy = ByteArray(32) { 9 }
        val gateway = TelegramLoginGateway(randomBytes = { entropy.copyOf() })
        val request = gateway.begin()

        val result = gateway.consumeBrowserCallback(state = request.clientState, error = null)

        assertEquals(
            TelegramLoginCallbackResult.BrowserState(
                state = request.clientState,
                codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy),
            ),
            result,
        )
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeBrowserCallback(state = request.clientState, error = null),
        )
    }

    @Test
    fun `mismatched callback cannot consume the current session`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )
        val stale = gateway.begin()
        val current = gateway.begin()

        assertFalse(gateway.cancel(stale.clientState))
        assertFalse(gateway.markExternalFlowStarted(stale.clientState))
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("stale-code", stale.clientState, null),
        )
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `activity destruction cancels only pre external preparation`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 6 } })
        val preparing = gateway.begin()

        assertTrue(gateway.cancelPreparationIfPending())
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("late-code", preparing.clientState, null),
        )

        val external = gateway.begin()
        assertTrue(gateway.markExternalFlowStarted(external.clientState))
        assertFalse(gateway.cancelPreparationIfPending())
        assertTrue(
            gateway.consumeCallback("current-code", external.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `stale callback cannot reset external flow timeout`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 5 } })
        val current = gateway.begin()
        gateway.markExternalFlowStarted(current.clientState)
        gateway.markHostStopped()

        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("foreign-code", "F".repeat(43), null),
        )
        val staleTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        val currentTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        assertFalse(gateway.expireExternalFlow(staleTimeoutLease))
        assertTrue(currentTimeoutLease !== staleTimeoutLease)
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `old resume timeout cannot cancel a restarted login`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )
        val stale = gateway.begin()
        gateway.markExternalFlowStarted(stale.clientState)
        gateway.markHostStopped()
        val staleTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())

        gateway.cancel()
        val current = gateway.begin()
        gateway.markExternalFlowStarted(current.clientState)

        assertFalse(gateway.expireExternalFlow(staleTimeoutLease))
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `external flow lifecycle survives host recreation on retained gateway`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 5 } })
        val request = gateway.begin()
        assertTrue(gateway.markExternalFlowStarted(request.clientState))
        gateway.markHostStopped()

        val timeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        assertTrue(gateway.completeExternalFlow(request.clientState))
        assertFalse(gateway.expireExternalFlow(timeoutLease))

        assertTrue(
            gateway.consumeCallback("current-code", request.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }
}
