package com.noki.vpn.vpn

import android.os.Looper
import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.VpnStartCoordinator
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.hysteriaCandidate
import com.noki.vpn.data.tcpCandidate
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppVpnServiceCommandTest {
    @Test
    fun candidateDeadlineRetainsCompletedAlternativeAndCancelsStalledProbe() = runBlocking {
        val started = mutableListOf<String>()
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stalledCancelled = false
        val began = System.nanoTime()
        val result = EndpointProbeRunner.collectCandidateProbes(
            listOf(hysteriaCandidate("stalled"), hysteriaCandidate("healthy"), hysteriaCandidate("excluded")),
            deadlineMillis = 500L,
            staggerMillis = 50L,
        ) { candidate ->
            started += candidate.code
            if (candidate.code == "stalled") {
                firstStarted.complete(Unit)
                try { awaitCancellation() } finally { stalledCancelled = true }
            } else {
                assertEquals(true, firstStarted.isCompleted)
                org.junit.Assert.assertTrue((System.nanoTime() - began) / 1_000_000 >= 40L)
                EndpointProbeOutcome(candidate.code, false, null, 20L, null, 1, 1, true, false)
            }
        }
        assertEquals(listOf("stalled", "healthy"), started)
        assertEquals(listOf("healthy"), result.map { it.endpointCode })
        assertEquals(true, stalledCancelled)
        try {
            EndpointProbeRunner.collectCandidateProbes(listOf(hysteriaCandidate("cancelled")), 500L, 0L) {
                throw kotlinx.coroutines.CancellationException("session changed")
            }
            org.junit.Assert.fail("Context cancellation must not become a completed or failed candidate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test
    fun nativeProbeCancellationKeepsWorkerSlotsUntilCallsActuallyReturn() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(2)
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val queuedStarted = java.util.concurrent.CountDownLatch(1)
        var delivered = 0
        val workers = (1..2).map {
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                EndpointProbeRunner.awaitNativeProbe(
                    measure = {
                        started.countDown()
                        try {
                            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            20L
                        } finally {
                            exited.countDown()
                        }
                    },
                    cancel = { cancelled.incrementAndGet() },
                )
                delivered++
            }
        }
        try {
            org.junit.Assert.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            workers.forEach { it.cancel() }
            assertEquals(2, cancelled.get())
            val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                EndpointProbeRunner.awaitNativeProbe(
                    measure = { queuedStarted.countDown(); 30L },
                    cancel = { cancelled.incrementAndGet() },
                )
                delivered++
            }
            assertFalse(queuedStarted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            queued.cancel()
            assertEquals(3, cancelled.get())
            release.countDown()
            org.junit.Assert.assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.withTimeout(5_000) {
                workers.forEach { it.join() }
                queued.join()
                assertEquals(40L, EndpointProbeRunner.awaitNativeProbe({ 40L }, {}))
            }
            assertEquals(1L, queuedStarted.count)
            assertEquals(0, delivered)
        } finally {
            release.countDown()
            workers.forEach { it.cancel() }
        }
    }

    @Test
    fun candidateProbeRejectsNativeResultsAfterContextInvalidation() = runBlocking {
        val context = Fixture(this).service
        val repository = SettingsRepository(context)
        val candidate = hysteriaCandidate("probe")
        val session = BackendVpnSession(
            canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
            endpointCode = candidate.code, entryHost = candidate.entryHost, entryPort = candidate.entryPort,
            serverName = candidate.serverName, proxyType = candidate.proxyType, transport = candidate.transport,
            transportMode = candidate.transportMode, security = candidate.security, fingerprint = null,
            requestHost = null, path = null, alpn = null, allowInsecure = false, enableMux = false,
            randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "test",
            vpnSecret = "test-secret", flow = null, planCode = null, endpointCandidates = listOf(candidate),
        )
        for (change in listOf("cancel", "generation", "network", "logout", "native-cancel", "api-down", "valid")) {
            val settings = repository.updateSettings {
                it.copy(isAuthenticated = true, backendAccessToken = "probe-token-$change",
                    advancedSettings = it.advancedSettings.copy(endpointSelectionMode = EndpointSelectionMode.AUTO,
                        anonymousLogsEnabled = true, connectionLogsEnabled = true))
            }
            var generation = 1L
            var network = "network-a"
            var uploads = 0
            val before = repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI)
            lateinit var job: kotlinx.coroutines.Job
            val runner = EndpointProbeRunner(
                context = context,
                repository = repository,
                endpointHealthReporter = com.noki.vpn.data.EndpointHealthEventReporter(
                    store = repository, upload = { _, _ -> uploads++ }, loadSettings = repository::load),
                measureTcpDelay = { host, _, _ ->
                    if (change == "api-down" && host == com.noki.vpn.data.NokiBackendConfig.backendProbeHost) null else 10
                },
                measureNativeDelay = { _, _ ->
                    when (change) {
                        "cancel" -> job.cancel()
                        "generation" -> generation++
                        "network" -> network = "network-b"
                        "logout" -> repository.clearAuthAndStageRefreshToken()
                        "native-cancel" -> throw kotlinx.coroutines.CancellationException("native cancelled")
                    }
                    20L
                },
            )
            job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                runner.probeAutoCandidates(session, settings,
                    isCurrent = { generation == 1L && network == "network-a" },
                    networkKind = EndpointRankingPolicy.NetworkKind.WIFI)
            }
            job.start()
            kotlinx.coroutines.withTimeout(5_000) { job.join() }
            val healthy = change == "valid" || change == "api-down"
            assertEquals(change, !healthy, job.isCancelled)
            if (healthy) {
                assertEquals(1, uploads)
                assertEquals((before["probe"]?.successCount ?: 0) + 1, repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI)["probe"]?.successCount)
            } else {
                assertEquals(change, before, repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI))
                assertEquals(change, 0, uploads)
                assertEquals(emptyList<com.noki.vpn.data.EndpointHealthEvent>(), repository.loadEndpointHealthEventQueue())
            }
        }
    }

    @Test
    fun coreAssetRefreshReplacesEqualLengthDataAndPreservesOldFileOnFailure() {
        val context = Fixture(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).service
        val asset = context.assets.open("geosite.dat").use { it.readBytes() }
        val target = java.io.File(context.noBackupFilesDir, "geosite.dat")
        target.writeBytes(ByteArray(asset.size))
        XrayController.ensureCoreAsset(context, "geosite.dat")
        org.junit.Assert.assertArrayEquals(asset, target.readBytes())
        val missing = java.io.File(context.noBackupFilesDir, "missing-test.dat")
        missing.writeText("previous valid data")
        try {
            XrayController.ensureCoreAsset(context, "missing-test.dat")
            org.junit.Assert.fail("Missing bundled asset must fail")
        } catch (_: java.io.IOException) {
            assertEquals("previous valid data", missing.readText())
        }
    }

    @Test
    fun diagnosticQueuesAreClearedOnOptOutAndLogoutWithoutDeletingLocalLogs() = runBlocking {
        val repository = SettingsRepository(Fixture(this).service)
        for (logout in listOf(false, true)) {
            val settings = repository.updateSettings {
                it.copy(isAuthenticated = true, backendAccessToken = "token",
                    advancedSettings = it.advancedSettings.copy(connectionLogsEnabled = true, anonymousLogsEnabled = true))
            }
            repository.recordAppLog("test", message = "local-only")
            repository.enqueueVpnIncident(com.noki.vpn.data.VpnIncidentReport(
                id = "incident", reason = "timeout", countryCode = "lv", locationCode = "lv1",
                recoveryAttempts = 1, outcome = "failed", occurredAt = "2026-09-19T00:00:00Z",
            ), settings)
            repository.saveEndpointHealthEventQueue(listOf(com.noki.vpn.data.EndpointHealthEvent(
                "endpoint", EndpointRankingPolicy.NetworkKind.WIFI,
                com.noki.vpn.data.EndpointHealthEventType.CONNECT_FAIL, false, false,
                com.noki.vpn.data.EndpointScoreBucket.BAD,
            )))
            if (logout) repository.clearAuthAndStageRefreshToken() else repository.updateSettings {
                it.copy(advancedSettings = it.advancedSettings.copy(anonymousLogsEnabled = false))
            }
            assertEquals(emptyList<com.noki.vpn.data.EndpointHealthEvent>(), repository.loadEndpointHealthEventQueue())
            assertEquals(emptyList<com.noki.vpn.data.VpnIncidentReport>(), repository.loadPendingVpnIncidents())
            org.junit.Assert.assertTrue(repository.exportAppLogs().contains("local-only"))
        }
    }

    @Test
    fun killSwitchRetainsTunnelAcrossFailuresAndRestartUntilExplicitStop() = runBlocking {
        val fixture = Fixture(this)
        fixture.enableKillSwitch()
        fixture.useOfflinePreparation()
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.prepareFailure(java.io.IOException("network lost"))
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertFalse(shadowOf(fixture.service).isStoppedBySelf)

        fixture.prepareFailure(IllegalStateException("auth_required"))
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.invoke("restartVpn", VpnPreparationStrategy.FreshOnly)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())

        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertNull(fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun killSwitchKeepsFailedStartupAndInvalidatedReadinessBlocked() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
        fixture.enableKillSwitch()
        fixture.startPrepared()
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        org.junit.Assert.assertNotNull(fixture.orchestrator.currentTunnel())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()

        fixture.onProbe = { fixture.orchestrator.invalidate() }
        fixture.startPrepared()
        org.junit.Assert.assertNotNull(fixture.orchestrator.currentTunnel())
        fixture.stop(2)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun requestedLatencyPublishesFreshSamplesAndCoalescesConcurrentRefreshes() = runBlocking {
        val fixture = Fixture(this)
        val repository = SettingsRepository(fixture.service)
        val settings = repository.load().let {
            it.copy(userProfile = it.userProfile.copy(selectedServerCode = "lv"))
        }
        var measured = 40
        var probes = 0
        val samples = mutableListOf<Int>()
        val coordinator = VpnStatsCoordinator(
            repository = repository,
            scope = this,
            scheduler = object : DelayedTaskScheduler {
                override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) = Unit
                override fun cancel(owner: Any) = Unit
            },
            measureConnectedLatencyMs = { probes++; yield(); measured },
            onLatencySample = { _, latency -> samples += latency },
        )
        coordinator.start(settings, RuntimeOwner(1L, 1L))
        coordinator.refreshLatency()
        coordinator.refreshLatency()
        repeat(3) { yield() }
        assertEquals(listOf(40), samples)
        assertEquals(1, probes)
        measured = 75
        coordinator.refreshLatency()
        repeat(3) { yield() }
        assertEquals(listOf(40, 75), samples)
        coordinator.refreshLatency()
        coordinator.reset()
        repeat(3) { yield() }
        assertEquals(listOf(40, 75), samples)
    }

    @Test
    fun transientPreparationFailuresKeepRecoveryAliveAndBackOff() = runBlocking {
        val fixture = Fixture(this)
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.useOfflinePreparation()
        fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
        fixture.prepareFailure(java.io.IOException("network lost"))
        repeat(3) { attempt ->
            if (attempt > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(
                    java.time.Duration.ofMillis(ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt - 1, jitterFraction = 1.0)),
                )
                fixture.orchestrator.activeTransitionJob()!!.join()
            }

            assertFalse("network loss must not stop automatic recovery", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull("failed VPN must release ordinary internet", fixture.orchestrator.currentTunnel())
            assertEquals(attempt + 1, fixture.recoveryAttempt())
            fixture.invoke("broadcastCurrentState")
            assertFalse("QUERY_STATE must preserve the retry", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        }
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
        assertEquals(0, fixture.recoveryAttempt())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun offlineRecoveryWaitsForValidationThenResumesOnlyOnce() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            if (killSwitch) fixture.enableKillSwitch()
            fixture.setNetworkAvailability(UnderlyingNetworkAvailability.None)
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
            fixture.prepareFailure(java.io.IOException("offline"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            val attempt = fixture.recoveryAttempt()
            assertNull(fixture.orchestrator.activeTransitionJob())
            org.junit.Assert.assertNotNull(fixture.pendingNetworkRecovery())
            assertEquals(killSwitch, fixture.orchestrator.currentTunnel() != null)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Unvalidated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            assertEquals(attempt, fixture.recoveryAttempt())
            assertNull(fixture.orchestrator.activeTransitionJob())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            val recoveryJob = checkNotNull(fixture.orchestrator.activeTransitionJob())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            assertEquals(recoveryJob, fixture.orchestrator.activeTransitionJob())
            recoveryJob.join()
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull(fixture.pendingNetworkRecovery())
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun stoppedOrSupersededOfflineRecoveryCannotRestartOnNetworkReturn() = runBlocking {
        for (invalidation in listOf("stop", "generation", "logout")) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            fixture.setNetworkAvailability(UnderlyingNetworkAvailability.None)
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
            fixture.prepareFailure(java.io.IOException("offline"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
            org.junit.Assert.assertNotNull(fixture.pendingNetworkRecovery())
            when (invalidation) {
                "stop" -> {
                    fixture.stop(1)
                    fixture.orchestrator.activeTransitionJob()!!.join()
                }
                "generation" -> fixture.orchestrator.beginTransition()
                "logout" -> fixture.clearStoredAuth()
            }
            val attempts = fixture.recoveryAttempt()
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
            assertNull(invalidation, fixture.orchestrator.activeTransitionJob())
            assertNull(invalidation, fixture.pendingNetworkRecovery())
            assertEquals(invalidation, attempts, fixture.recoveryAttempt())
        }
    }

    @Test
    fun permanentPreparationFailureStopsRecovery() = runBlocking {
        val fixture = Fixture(this)
        fixture.set("transientRecoveryAttempt", 2)
        fixture.prepareFailure(IllegalStateException("auth_required"))

        org.junit.Assert.assertTrue(shadowOf(fixture.service).isStoppedBySelf)
        assertEquals(0, fixture.recoveryAttempt())
        assertNull(fixture.orchestrator.currentTunnel())
    }

    @Test
    fun permanentPreparationFailureKeepsKillSwitchWithoutRetry() = runBlocking {
        for (destructive in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            fixture.enableKillSwitch()
            fixture.orchestrator.updateState(VpnConnectionState.FAILED)
            val tunnel = fixture.orchestrator.currentTunnel()
            fixture.prepareFailure(IllegalStateException("auth_required"), destructive)
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertNull(fixture.orchestrator.activeTransitionJob())
            assertNull(fixture.pendingNetworkRecovery())
            assertEquals(0, fixture.recoveryAttempt())
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun nonDestructiveRefreshFailureKeepsConnectedTunnel() = runBlocking {
        val fixture = Fixture(this)
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.prepareFailure(java.io.IOException("network lost"), destructive = false)

        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(0, fixture.recoveryAttempt())
    }

    @Test
    fun failedRuntimeCandidatesReachHysteriaWithinOneStart() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.startPrepared()

        assertEquals(setOf("tcp1", "tcp2", "tcp3", "tcp4", "hy2"), fixture.startedEndpoints.toSet())
        assertEquals(5, fixture.startedEndpoints.size)
        assertEquals("hy2", fixture.startedEndpoints.last())
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals("hy2", fixture.orchestrator.currentSettings()?.profile?.endpointCode)
        assertEquals("hy2", fixture.savedSettings().profile.endpointCode)
        assertEquals(1, fixture.tunnelsCreated)
    }

    @Test
    fun losingValidatedUnderlayStopsReportingConnectedAndPreservesRecovery() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this)
            if (killSwitch) fixture.enableKillSwitch()
            val tunnel = fixture.orchestrator.currentTunnel()
            fixture.observeNetwork(UnderlyingNetworkAvailability.None)
            fixture.orchestrator.activeTransitionJob()?.join()
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            if (killSwitch) assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            else assertNull(fixture.orchestrator.currentTunnel())
            assertFalse(shadowOf(fixture.service).isStoppedBySelf)
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
        }
    }

    @Test
    fun healthyReplacementReportsConnectingUntilReadinessIsProven() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        var observed = false
        fixture.onProbe = {
            observed = true
            assertEquals(VpnConnectionState.CONNECTING, fixture.orchestrator.currentState())
        }
        org.junit.Assert.assertTrue(fixture.replaceConnected())
        org.junit.Assert.assertTrue(observed)
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedReplacementThenBackendTimeoutReportsFailureAndKeepsRetry() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        fixture.workingEndpoint = null
        assertFalse(fixture.replaceConnected())
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.useOfflinePreparation()
        fixture.prepareFailure(java.io.IOException("backend timeout"), destructive = false)
        fixture.invoke("broadcastCurrentState")
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertFalse(shadowOf(fixture.service).isStoppedBySelf)
        assertEquals(1, fixture.recoveryAttempt())
        assertNull(fixture.orchestrator.currentTunnel())
        shadowOf(Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(ConnectedWatchdogPolicy.transientRetryDelayMillis(0, jitterFraction = 1.0)),
        )
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertEquals(2, fixture.recoveryAttempt())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedReplacementRetainsTunnelForNextHealthyCandidate() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.workingEndpoint = null
        assertFalse(fixture.replaceConnected())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.workingEndpoint = "tcp1"
        org.junit.Assert.assertTrue(fixture.replaceConnected())
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedAutoStartExhaustsCandidatesOnceBeforeReportingFailure() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
        fixture.startPrepared()

        assertEquals(setOf("tcp1", "tcp2", "tcp3", "tcp4", "hy2"), fixture.startedEndpoints.toSet())
        assertEquals(5, fixture.startedEndpoints.size)
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertNull(fixture.orchestrator.currentTunnel())
        assertFalse(fixture.savedSettings().profile.endpointCode == "hy2")
    }

    @Test
    fun manualStartDoesNotSwitchToAnotherEngineAfterRuntimeFailure() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.startPrepared(mode = EndpointSelectionMode.MANUAL)

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
    }

    @Test
    fun successfulFirstCandidateDoesNotStartFallbacks() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun invalidatedStartDoesNotTryAnotherCandidate() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.onProbe = { fixture.orchestrator.invalidate() }
        fixture.startPrepared()

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertNull(fixture.orchestrator.currentTunnel())
        assertNull(fixture.orchestrator.currentSettings())
    }

    @Before
    fun provideInMemoryAndroidKeyStore() {
        Security.addProvider(object : Provider("NokiVpnCommandTest", 1.0, "Test-only Android keystore") {
            init {
                put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name)
            }
        })
    }

    @After
    fun removeInMemoryAndroidKeyStore() {
        Security.removeProvider("NokiVpnCommandTest")
    }

    @Test
    fun startAfterTunnelReleaseKeepsServiceAliveUntilStopCleanupCompletes() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        fixture.orchestrator.withLifecycleLock {
            fixture.orchestrator.releaseResourcesWhileOwned(VpnConnectionState.DISCONNECTED)
        }
        try {
            fixture.startAccount()

            assertFalse("queued START still needs this service", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly), fixture.pendingStart())
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun accountStartWhileConnectedStopIsCleaningUpIsRetained() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        try {
            fixture.startAccount()

            assertEquals(
                VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly),
                fixture.pendingStart(),
            )
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun temporaryStartWhileConnectedStopIsCleaningUpIsRetained() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        try {
            fixture.invoke("startTemporaryVpn")

            assertEquals(
                VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly, VpnRuntimeMode.AUTH_TEMP),
                fixture.pendingStart(),
            )
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun postedStopCompletionCannotRestoreStartCancelledByNewerStop() = runBlocking {
        val fixture = Fixture(this)
        fixture.stop(1)
        // The START was already admitted while cleanup was in progress. Keep this
        // regression independent of the separate connected-start admission bug.
        fixture.set("pendingStartOptions", VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly))
        fixture.orchestrator.activeTransitionJob()!!.join()

        fixture.orchestrator.lifecycleMutex.lock()
        try {
            fixture.stop(2)
            val newerStop = fixture.orchestrator.activeTransitionJob()!!
            yield()
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)

            shadowOf(Looper.getMainLooper()).idle()

            assertNull("the newest STOP must not acquire the older queued START", fixture.pendingStart())
            assertEquals(newerStop, fixture.orchestrator.activeTransitionJob())
        } finally {
            fixture.orchestrator.lifecycleMutex.unlock()
            fixture.orchestrator.activeTransitionJob()?.join()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        private val probeFallback: Boolean = false,
        var workingEndpoint: String? = "hy2",
    ) {
        val service = Robolectric.buildService(AppVpnService::class.java).get()
        val startedEndpoints = mutableListOf<String>()
        var tunnelsCreated = 0
        var onProbe: () -> Unit = {}
        private val store = object : AtomicStoredSettingsStore {
            private var value = DefaultStoredSettingsFactory.create()
            override fun load(): StoredSettings = value
            override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
                val (updated, result) = transform(value)
                value = updated
                return result
            }
        }
        private val scheduler = HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper()))
        private val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.OTHER },
            resolveStart = { _, _, _, _ -> error("unexpected backend call") },
            refreshAccessToken = { error("unexpected token refresh") },
        )
        val orchestrator = VpnConnectionOrchestrator(
            xray = object : XrayRuntime {
                override fun start(config: String, tunFd: Int): Boolean {
                    check(probeFallback)
                    val outbound = JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
                    val protocol = outbound.getString("protocol")
                    startedEndpoints += if (protocol == "hysteria") "hy2" else {
                        outbound.getJSONObject("settings").getJSONArray("vnext")
                            .getJSONObject(0).getString("address").substringBefore('.')
                    }
                    return true
                }
                override fun stop() = Unit
                override fun cancelMeasureDelay() = Unit
                override fun measureDelay(targetUrl: String, timeoutMillis: Long): XrayProbeResult {
                    onProbe()
                    return XrayProbeResult(delayMs = if (startedEndpoints.last() == workingEndpoint) 30L else null)
                }
            },
            tunFactory = object : TunInterfaceFactory {
                override fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle {
                    check(probeFallback)
                    tunnelsCreated++
                    return object : TunHandle {
                        override val fd = 7
                        override fun close() = Unit
                    }
                }
            },
            sidecars = OwnedVpnConnectedSidecars(onStart = { _, _ -> }, onStop = {}),
        )

        init {
            // Do not run onCreate: Android/native adapters are the external
            // boundary; lifecycle arbitration and command handling remain real.
            set("connectionOrchestrator", orchestrator)
            set("connectionPreparer", preparer)
            set("backgroundScope", scope)
            set("delayedTaskScheduler", scheduler)
            set("watchdogController", VpnWatchdogController(
                scheduler = scheduler,
                nowMillis = { 0L },
                launchProbe = { _, _ -> null },
                isLockdown = { false },
                evidenceFreshMillis = 1L,
            ))
            set("warmupController", VpnWarmupController<BackendVpnSession>(scheduler, 1L))
            set("statsCoordinator", VpnStatsCoordinator(SettingsRepository(service), scope, scheduler, { null }, { _, _ -> }))
            set("isXrayRuntimeAvailable", { true })
            set("underlyingNetworkSource", AndroidUnderlyingNetworkSource(service))
            set("notificationFactory", VpnNotificationFactory(service))
            set("settingsStore", store)
            set("networkMonitor", VpnNetworkMonitor(
                source = { error("unexpected network callback") },
                scheduler = scheduler,
                debounceMillis = 1L,
                register = { null },
            ))
            orchestrator.updateState(VpnConnectionState.CONNECTED)
            orchestrator.replaceTunnel(object : TunHandle {
                override val fd = 7
                override fun close() = Unit
            })
        }

        fun startAccount() = invoke("startVpn", VpnPreparationStrategy.FreshOnly)

        fun enableKillSwitch() {
            store.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = true)) }
            SettingsRepository(service).updateSettings {
                it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = true))
            }
        }

        fun useOfflinePreparation() {
            store.updateSettings { it.copy(backendAccessToken = "test-token") }
            set("connectionPreparer", VpnConnectionPreparer(
                store = store,
                currentNetworkKind = { EndpointRankingPolicy.NetworkKind.OTHER },
                resolveStart = { _, _, _, _ ->
                    VpnStartCoordinator.StartDecision.Failure(java.io.IOException("network lost"))
                },
                refreshAccessToken = { error("unexpected token refresh") },
                retryCount = 0,
            ))
        }

        fun prepareFailure(error: Throwable, destructive: Boolean = true) {
            AppVpnService::class.java.getDeclaredMethod(
                "handlePrepareFailure", SettingsRepository::class.java, String::class.java,
                Throwable::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(
                service, SettingsRepository(service), "profile_prepare_error", error, destructive,
            )
        }

        fun recoveryAttempt(): Int = AppVpnService::class.java.getDeclaredField("transientRecoveryAttempt")
            .apply { isAccessible = true }.getInt(service)

        fun pendingNetworkRecovery(): Any? = AppVpnService::class.java.getDeclaredField("pendingNetworkRecovery")
            .apply { isAccessible = true }.get(service)

        fun clearStoredAuth() {
            store.updateSettings { it.copy(isAuthenticated = false, backendAccessToken = null, backendRefreshToken = null) }
        }

        fun setNetworkAvailability(availability: UnderlyingNetworkAvailability) {
            val manager = service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val shadow = shadowOf(manager)
            shadow.clearAllNetworks()
            if (availability == UnderlyingNetworkAvailability.None) return
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
            val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI,
                0, true, true,
            )
            val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).apply {
                addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                if (availability == UnderlyingNetworkAvailability.Validated) {
                    addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            shadow.addNetwork(network, info)
            shadow.setNetworkCapabilities(network, capabilities)
        }

        fun observeNetwork(availability: UnderlyingNetworkAvailability) {
            setNetworkAvailability(availability)
            invoke("handleUnderlyingNetworkObservation", AndroidUnderlyingNetworkSource(service).currentObservation())
        }

        fun savedSettings(): StoredSettings = store.load()

        suspend fun replaceConnected(): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
            val settings = checkNotNull(orchestrator.currentSettings())
            AppVpnService::class.java.declaredMethods.single { it.name == "replaceXrayOnExistingTunnel" }
                .apply { isAccessible = true }.invoke(
                    service, SettingsRepository(service), settings, settings, settings,
                    EndpointRankingPolicy.NetworkKind.CELLULAR, orchestrator.currentTunnel(),
                    orchestrator.beginTransition(), null, false, false, continuation,
                )
        }

        suspend fun startPrepared(mode: EndpointSelectionMode = EndpointSelectionMode.AUTO) {
            val candidates = (1..4).map { tcpCandidate("tcp$it") } + hysteriaCandidate("hy2")
            val session = BackendVpnSession(
                canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
                endpointCode = "tcp1", entryHost = "tcp1.example.com", entryPort = 443,
                serverName = "www.lu.lv", proxyType = "vless", transport = "tcp", transportMode = null,
                security = "reality", fingerprint = "chrome", requestHost = null, path = null, alpn = null,
                allowInsecure = false, enableMux = false, randomUserAgent = false,
                publicKey = "public", shortId = "short", vpnUsername = "test",
                vpnSecret = "e912e725-2dc7-4b44-85d2-7fbc31a48b5e", flow = "xtls-rprx-vision",
                planCode = null, endpointCandidates = candidates,
            )
            val baseline = store.load()
            val profile = EndpointSelector.profileFromCandidate(session, candidates.first())
            val settings = baseline.copy(
                profile = profile,
                userProfile = baseline.userProfile.copy(selectedServerCode = "lv1"),
                endpointOptions = EndpointSelector.optionsFromSession(session),
                advancedSettings = baseline.advancedSettings.copy(endpointSelectionMode = mode),
            )
            val prepared = PreparedVpnSession(
                baseline, settings, EndpointRankingPolicy.NetworkKind.CELLULAR,
                VpnStartCoordinator.Result(
                    settings, session,
                    BackendDevice(id = "device", deviceKey = "key", deviceName = "Phone",
                        platform = "android", accessRole = "owner", isActive = true, lastSeenAt = null),
                    EndpointSelector.EndpointSelectionResult(profile, profile.endpointCode, ""), emptyList(),
                ),
            )
            orchestrator.replaceTunnel(null)
            orchestrator.updateState(VpnConnectionState.CONNECTING)
            val generation = orchestrator.beginTransition()
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = AppVpnService::class.java.declaredMethods.single { it.name == "startTunnel" }
                    .apply { isAccessible = true }
                val result = method.invoke(service, SettingsRepository(service), settings, baseline,
                    prepared, generation, VpnRuntimeMode.ACCOUNT, true, continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
        }
        fun stop(startId: Int) = invoke("stopVpn", startId, false)

        fun pendingStart(): Any? = AppVpnService::class.java.getDeclaredField("pendingStartOptions")
            .apply { isAccessible = true }.get(service)

        fun set(name: String, value: Any) {
            AppVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }

        fun invoke(name: String, vararg args: Any) {
            val types = args.map { when (it) { is Boolean -> Boolean::class.javaPrimitiveType; is Int -> Int::class.javaPrimitiveType; else -> it.javaClass } }
            AppVpnService::class.java.getDeclaredMethod(name, *types.toTypedArray())
                .apply { isAccessible = true }.invoke(service, *args)
        }
    }

    // AndroidKeyStore is unavailable on the host JVM. Keep SettingsRepository
    // and AES/GCM real; replace only the platform key storage boundary.
    class InMemoryAndroidKeyStore : KeyStoreSpi() {
        private val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        override fun engineGetEntry(alias: String?, protection: KeyStore.ProtectionParameter?) = KeyStore.SecretKeyEntry(key)
        override fun engineGetKey(alias: String?, password: CharArray?): Key = key
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineContainsAlias(alias: String?) = alias == "noki_settings_aes"
        override fun engineAliases() = Collections.enumeration(listOf("noki_settings_aes"))
        override fun engineSize() = 1
        override fun engineIsKeyEntry(alias: String?) = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificateAlias(certificate: Certificate?): String? = null
        override fun engineGetCreationDate(alias: String?) = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<Certificate>?) = error("not needed")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) = error("not needed")
        override fun engineSetCertificateEntry(alias: String?, certificate: Certificate?) = error("not needed")
        override fun engineDeleteEntry(alias: String?) = error("not needed")
        override fun engineStore(stream: OutputStream?, password: CharArray?) = error("not needed")
    }
}
