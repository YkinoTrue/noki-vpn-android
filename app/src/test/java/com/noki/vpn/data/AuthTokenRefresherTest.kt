package com.noki.vpn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthTokenRefresherTest {
    @Test
    fun refreshPreservesUiMutationCommittedWhileNetworkCallIsInFlight() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())

        val refresh = async { AuthTokenRefresher(store, api).refreshStoredTokens() }
        api.requestStarted.await()
        store.updateSettings { latest ->
            latest.copy(
                personalizationSettings = latest.personalizationSettings.copy(language = AppLanguage.RU),
            )
        }
        api.releaseResponse.complete(Unit)

        val result = refresh.await()

        assertEquals("new-access", result?.accessToken)
        assertEquals("new-refresh", result?.refreshToken)
        assertEquals(AppLanguage.RU, store.load().personalizationSettings.language)
    }

    @Test
    fun concurrentRefreshersPerformExactlyOneRotatedTokenRequest() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())

        val refreshes = listOf(
            async { AuthTokenRefresher(store, api).refreshStoredTokens() },
            async { AuthTokenRefresher(store, api).refreshStoredTokens() },
        )
        api.requestStarted.await()
        api.releaseResponse.complete(Unit)

        val results = refreshes.awaitAll()

        assertEquals(1, api.callCount.get())
        assertEquals(listOf("new-refresh", "new-refresh"), results.map { it?.refreshToken })
    }

    @Test
    fun logoutInvalidatesRefreshCompletingAfterSessionWasCleared() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())

        val refresh = async { AuthTokenRefresher(store, api).refreshStoredTokens() }
        api.requestStarted.await()
        store.updateSettings { latest ->
            latest.copy(
                isAuthenticated = false,
                backendAccessToken = null,
                backendRefreshToken = null,
                backendAccessTokenExpiresInSeconds = null,
                backendRefreshExpiresAt = null,
            )
        }
        api.releaseResponse.complete(Unit)

        val result = refresh.await()

        assertNull(result)
        assertFalse(store.load().isAuthenticated)
        assertNull(store.load().backendAccessToken)
        assertNull(store.load().backendRefreshToken)
        assertEquals(listOf("new-refresh"), store.pendingLogoutRevocations())
    }

    @Test
    fun deviceChangeInvalidatesRefreshStartedForPreviousDevice() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())

        val refresh = async { AuthTokenRefresher(store, api).refreshStoredTokens() }
        api.requestStarted.await()
        store.updateSettings { latest -> latest.copy(backendDeviceId = "device-2") }
        api.releaseResponse.complete(Unit)

        val result = refresh.await()
        val saved = store.load()

        assertNull(result)
        assertEquals(listOf("device-1"), api.deviceIds)
        assertEquals("device-2", saved.backendDeviceId)
        assertEquals("old-access", saved.backendAccessToken)
        assertEquals("old-refresh", saved.backendRefreshToken)
    }

    @Test
    fun failedRefreshKeepsRequestIdAndRetryClearsItAfterAtomicTokenCommit() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = RetryAwareAuthRefreshApi(rotatedTokens())
        val refresher = AuthTokenRefresher(store, api)

        val first = runCatching { refresher.refreshStoredTokens() }
        val pendingAfterFailure = store.load().backendRefreshRequestId

        assertTrue(first.isFailure)
        assertNotNull(pendingAfterFailure)

        val second = refresher.refreshStoredTokens()

        assertEquals("new-refresh", second?.refreshToken)
        assertEquals(listOf(pendingAfterFailure, pendingAfterFailure), api.requestIds)
        assertNull(store.load().backendRefreshRequestId)
    }

    @Test
    fun pendingLogoutRevocationRetriesTransientFailureWithoutDroppingToken() = runBlocking {
        val store = InMemoryPendingLogoutRevocationStore(listOf("refresh-a"))
        val coordinator = PendingLogoutRevocationCoordinator(store) {
            throw BackendException("offline", 503)
        }

        val completed = coordinator.retryAll()

        assertFalse(completed)
        assertEquals(listOf("refresh-a"), store.loadPendingLogoutRevocations())
    }

    @Test
    fun pendingLogoutRevocationDrainsQueueAfterBackendRecovery() = runBlocking {
        val store = InMemoryPendingLogoutRevocationStore(listOf("refresh-a", "refresh-b"))
        val revoked = mutableListOf<String>()
        val coordinator = PendingLogoutRevocationCoordinator(store) { token -> revoked += token }

        val completed = coordinator.retryAll()

        assertTrue(completed)
        assertEquals(listOf("refresh-a", "refresh-b"), revoked)
        assertTrue(store.loadPendingLogoutRevocations().isEmpty())
    }

    @Test
    fun pendingLogoutRevocationDoesNotLoopWhenTombstoneRemovalMakesNoProgress() = runBlocking {
        val store = InMemoryPendingLogoutRevocationStore(
            initial = listOf("refresh-a"),
            ignoreRemovals = true,
        )
        var revokeCalls = 0
        val coordinator = PendingLogoutRevocationCoordinator(store) {
            revokeCalls += 1
            yield()
        }

        val completed = withTimeout(500L) { coordinator.retryAll() }

        assertFalse(completed)
        assertEquals(1, revokeCalls)
        assertEquals(listOf("refresh-a"), store.loadPendingLogoutRevocations())
    }

    @Test
    fun alreadyInvalidRefreshTokenIsTerminalForPendingLogout() = runBlocking {
        val store = InMemoryPendingLogoutRevocationStore(listOf("refresh-a"))
        val coordinator = PendingLogoutRevocationCoordinator(store) {
            throw BackendException("invalid_refresh_token", 401)
        }

        val completed = coordinator.retryAll()

        assertTrue(completed)
        assertTrue(store.loadPendingLogoutRevocations().isEmpty())
    }

    @Test
    fun pendingLogoutRevocationCancellationPropagatesAndKeepsToken() = runBlocking {
        val store = InMemoryPendingLogoutRevocationStore(listOf("refresh-a"))
        val coordinator = PendingLogoutRevocationCoordinator(store) {
            throw CancellationException("cancelled")
        }

        val result = runCatching { coordinator.retryAll() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("refresh-a"), store.loadPendingLogoutRevocations())
    }

    @Test
    fun pendingLogoutRevocationCodecPreservesAndDeduplicatesEveryToken() {
        val encoded = PendingLogoutRevocationCodec.encode(
            (1..20).map { "refresh-$it" } + listOf("refresh-20", ""),
        )

        val decoded = PendingLogoutRevocationCodec.decode(encoded)

        assertEquals(20, decoded.size)
        assertEquals("refresh-1", decoded.first())
        assertEquals("refresh-20", decoded.last())
    }

    private fun authenticatedSettings(): StoredSettings =
        DefaultStoredSettingsFactory.create().copy(
            personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
            isAuthenticated = true,
            backendAccessToken = "old-access",
            backendRefreshToken = "old-refresh",
            backendAccessTokenExpiresInSeconds = 60,
            backendRefreshExpiresAt = "old-expiry",
            backendDeviceId = "device-1",
        )

    private fun rotatedTokens(): BackendAuthTokens =
        BackendAuthTokens(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresInSeconds = 3_600,
            refreshExpiresAt = "new-expiry",
        )
}

private class InMemoryPendingLogoutRevocationStore(
    initial: List<String> = emptyList(),
    private val ignoreRemovals: Boolean = false,
) : PendingLogoutRevocationStore {
    private val lock = Any()
    private var tokens = initial

    override fun loadPendingLogoutRevocations(): List<String> = synchronized(lock) { tokens }

    override fun removePendingLogoutRevocation(refreshToken: String) {
        if (ignoreRemovals) return
        synchronized(lock) { tokens = tokens.filterNot { it == refreshToken } }
    }
}

private class RetryAwareAuthRefreshApi(
    private val response: BackendAuthTokens,
) : AuthRefreshApi {
    val requestIds = mutableListOf<String?>()
    private var calls = 0

    override suspend fun refreshAuthToken(
        refreshToken: String,
        deviceId: String?,
        requestId: String?,
    ): BackendAuthTokens {
        requestIds += requestId
        calls += 1
        if (calls == 1) throw BackendException("network", 503)
        return response
    }
}

internal class InMemoryAtomicStoredSettingsStore(
    initial: StoredSettings,
) : AtomicAuthSettingsStore {
    private val lock = Any()
    private var settings = initial
    private var pendingRevocations = emptyList<String>()

    override fun load(): StoredSettings = synchronized(lock) { settings }

    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R =
        synchronized(lock) {
            val (updated, result) = transform(settings)
            settings = updated
            result
        }

    override fun clearAuthAndStageRefreshToken(): StoredSettings = synchronized(lock) {
        settings.backendRefreshToken
            ?.takeIf { it.isNotBlank() }
            ?.let { pendingRevocations = (pendingRevocations + it).distinct() }
        settings.copy(
            isAuthenticated = false,
            backendAccessToken = null,
            backendRefreshToken = null,
            backendRefreshRequestId = null,
            backendAccessTokenExpiresInSeconds = null,
            backendRefreshExpiresAt = null,
        ).also { settings = it }
    }

    override fun commitRefreshedAuth(
        expectedSession: StoredSettings,
        tokens: BackendAuthTokens,
        refreshToken: String,
    ): AuthRefreshCommitResult = synchronized(lock) {
        val committed = settings.hasSameAuthSessionAs(expectedSession)
        if (committed) {
            settings = settings.copy(
                backendAccessToken = tokens.accessToken,
                backendRefreshToken = refreshToken,
                backendRefreshRequestId = null,
                backendAccessTokenExpiresInSeconds = tokens.expiresInSeconds,
                backendRefreshExpiresAt = tokens.refreshExpiresAt,
            )
        } else {
            pendingRevocations = (pendingRevocations + refreshToken).distinct()
        }
        AuthRefreshCommitResult(settings, committed)
    }

    override fun replaceAuthAndStagePreviousRefreshToken(tokens: BackendAuthTokens): AuthSessionCommitResult =
        synchronized(lock) {
            val refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() }
            val previous = settings.backendRefreshToken?.takeUnless { it == refreshToken }
            if (!previous.isNullOrBlank()) {
                pendingRevocations = (pendingRevocations + previous).distinct()
            }
            settings = settings.copy(
                isAuthenticated = true,
                backendAccessToken = tokens.accessToken,
                backendRefreshToken = refreshToken,
                backendRefreshRequestId = null,
                backendAccessTokenExpiresInSeconds = tokens.expiresInSeconds,
                backendRefreshExpiresAt = tokens.refreshExpiresAt,
            )
            AuthSessionCommitResult(settings, !previous.isNullOrBlank())
        }

    fun pendingLogoutRevocations(): List<String> = synchronized(lock) { pendingRevocations }

    fun replace(next: StoredSettings) {
        synchronized(lock) {
            settings = next
        }
    }
}

internal class ControlledAuthRefreshApi(
    private val response: BackendAuthTokens,
) : AuthRefreshApi {
    val requestStarted = CompletableDeferred<Unit>()
    val releaseResponse = CompletableDeferred<Unit>()
    val callCount = AtomicInteger(0)
    val deviceIds = mutableListOf<String?>()

    override suspend fun refreshAuthToken(
        refreshToken: String,
        deviceId: String?,
        requestId: String?,
    ): BackendAuthTokens {
        callCount.incrementAndGet()
        deviceIds += deviceId
        requestStarted.complete(Unit)
        releaseResponse.await()
        return response
    }
}
