package com.noki.vpn.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnSessionSelection
import com.noki.vpn.data.VpnStartCoordinator
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionPreparerTest {
    @Test
    fun `blocking backend call is bounded by the total preparation deadline`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        var resolveCount = 0
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                resolveCount += 1
                awaitCancellation()
            },
            refreshAccessToken = { error("refresh must not run") },
            retryCount = 2,
            deadlineMillis = 100L,
        )

        val result = withTimeout(1_000L) {
            preparer.prepare(
                strategy = VpnPreparationStrategy.FreshOnly,
            )
        }
        assertTrue(result is VpnConnectionPreparer.Outcome.Failure)
        assertTrue((result as VpnConnectionPreparer.Outcome.Failure).error is SocketTimeoutException)
        assertEquals(1, resolveCount)
    }

    @Test
    fun `cached prepare returns immutable metadata without mutating active owner state`() = runBlocking {
        val settings = cachedSettings()
        val store = PreparerStore(settings)
        var activeNetworkKind = EndpointRankingPolicy.NetworkKind.CELLULAR
        var activeWarmupMarker: String? = "existing-warmup"
        val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ -> error("fresh preparation must not run") },
            refreshAccessToken = { error("refresh must not run") },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.CachedFirst,
        ) as VpnConnectionPreparer.Outcome.Success

        assertEquals(settings, result.session.preparationBaseline)
        assertEquals(settings, result.session.candidateSettings)
        assertEquals(EndpointRankingPolicy.NetworkKind.WIFI, result.session.selectedNetworkKind)
        assertNull(result.session.pendingWarmupSession)
        assertEquals(EndpointRankingPolicy.NetworkKind.CELLULAR, activeNetworkKind)
        assertEquals("existing-warmup", activeWarmupMarker)
    }

    @Test
    fun `401 refresh retries with reloaded token without applying metadata`() = runBlocking {
        val initial = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val refreshed = initial.copy(backendAccessToken = "new-token")
        val store = PreparerStore(initial)
        val observedTokens = mutableListOf<String>()
        val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { token, _, _, _ ->
                observedTokens += token
                if (token == "old-token") {
                    VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
                } else {
                    VpnStartCoordinator.StartDecision.CachedFallback(BackendException("offline", 503))
                }
            },
            refreshAccessToken = {
                store.replace(refreshed)
                "new-token"
            },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshWithCachedFallback,
        ) as VpnConnectionPreparer.Outcome.Success

        assertEquals(listOf("old-token", "new-token"), observedTokens)
        assertEquals(refreshed, result.session.preparationBaseline)
        assertEquals(EndpointRankingPolicy.NetworkKind.WIFI, result.session.selectedNetworkKind)
        assertNull(result.session.pendingWarmupSession)
    }

    @Test
    fun `successful nullable refresh remains distinct from preparation timeout`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { null },
            deadlineMillis = 1_000L,
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        ) as VpnConnectionPreparer.Outcome.Failure

        assertTrue(result.error is BackendException)
        assertEquals(401, (result.error as BackendException).statusCode)
    }

    @Test
    fun `refresh shares the total preparation deadline`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { awaitCancellation() },
            deadlineMillis = 100L,
        )

        val result = withTimeout(1_000L) {
            preparer.prepare(
                strategy = VpnPreparationStrategy.FreshOnly,
            )
        } as VpnConnectionPreparer.Outcome.Failure

        assertTrue(result.error is SocketTimeoutException)
    }

    @Test(expected = CancellationException::class)
    fun `parent cancellation during refresh propagates`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { throw CancellationException("parent cancelled") },
            deadlineMillis = 1_000L,
        )

        preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        )
        Unit
    }

    @Test
    fun `transient fresh failures retry within owned retry budget`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        val attempts = mutableListOf<Int>()
        val sleeps = mutableListOf<Long>()
        var resolveCount = 0
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                resolveCount += 1
                VpnStartCoordinator.StartDecision.Failure(BackendException("temporary", 503))
            },
            refreshAccessToken = { null },
            retryCount = 2,
            deadlineMillis = 60_000L,
            nowMillis = { 1_000L },
            sleep = { sleeps += it },
            retryJitter = { it },
            onRetry = { attempt, _ -> attempts += attempt },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        )

        assertEquals(VpnConnectionPreparer.Outcome.Failure::class, result::class)
        assertEquals(3, resolveCount)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `recovery target is forwarded unchanged`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        val requested = VpnSessionSelection(
            countryCode = "LV",
            excludeLocationCode = "lv-2",
        )
        var observed: VpnSessionSelection? = null
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, selection ->
                observed = selection
                VpnStartCoordinator.StartDecision.Failure(BackendException("rejected", 400))
            },
            refreshAccessToken = { null },
        )

        preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
            sessionSelection = requested,
        )

        assertEquals(requested, observed)
    }

    private fun cachedSettings(): StoredSettings {
        val baseline = DefaultStoredSettingsFactory.create()
        val endpoint = VpnEndpointOption(code = "lv-1", locationCode = "lv")
        return baseline.copy(
            profile = baseline.profile.copy(
                endpointCode = endpoint.code,
                host = "vpn.example",
                uuid = "00000000-0000-4000-8000-000000000001",
                serverName = "vpn.example",
                publicKey = "public-key",
                shortId = "abcd",
            ),
            endpointOptions = listOf(endpoint),
        )
    }
}

private class PreparerStore(
    private var settings: StoredSettings,
) : AtomicStoredSettingsStore {
    override fun load(): StoredSettings = settings

    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        val (updated, result) = transform(settings)
        settings = updated
        return result
    }

    fun replace(next: StoredSettings) {
        settings = next
    }
}
