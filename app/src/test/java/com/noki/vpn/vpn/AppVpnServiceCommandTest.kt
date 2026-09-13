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
            context = fixture.service,
            scope = this,
            scheduler = object : DelayedTaskScheduler {
                override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) = Unit
                override fun cancel(owner: Any) = Unit
            },
            measureConnectedLatencyMs = { probes++; yield(); measured },
            onLatencySample = { _, latency -> samples += latency },
        )
        coordinator.start(repository, settings, RuntimeOwner(1L, 1L))
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
        fixture.useOfflinePreparation()
        fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
        fixture.prepareFailure(java.io.IOException("network lost"))
        repeat(3) { attempt ->
            if (attempt > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(
                    java.time.Duration.ofMillis(ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt - 1)),
                )
                fixture.orchestrator.activeTransitionJob()!!.join()
            }

            assertFalse("network loss must not stop automatic recovery", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull("failed VPN must release ordinary internet", fixture.orchestrator.currentTunnel())
            assertEquals(attempt + 1, fixture.recoveryAttempt())
        }
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
        assertEquals(0, fixture.recoveryAttempt())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
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
            assertEquals(VpnServiceStartCommandPolicy.StartOptions(true, false), fixture.pendingStart())
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
                VpnServiceStartCommandPolicy.StartOptions(true, false),
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
                VpnServiceStartCommandPolicy.StartOptions(true, false, VpnRuntimeMode.AUTH_TEMP),
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
        fixture.set("pendingStartOptions", VpnServiceStartCommandPolicy.StartOptions(true, false))
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
        private val workingEndpoint: String? = "hy2",
    ) {
        val service = Robolectric.buildService(AppVpnService::class.java).get()
        val startedEndpoints = mutableListOf<String>()
        var tunnelsCreated = 0
        var onProbe: () -> Unit = {}
        private val store = object : AtomicStoredSettingsStore {
            private var value = DefaultStoredSettingsFactory.create()
            override fun load(): StoredSettings = value
            override fun updateSettings(transform: (StoredSettings) -> StoredSettings): StoredSettings =
                transform(value).also { value = it }
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
            preparer = preparer,
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = OwnedVpnConnectedSidecars(onStart = { _, _ -> }, onStop = {}),
        )

        init {
            // Do not run onCreate: Android/native adapters are the external
            // boundary; lifecycle arbitration and command handling remain real.
            set("connectionOrchestrator", orchestrator)
            set("connectionPreparer", preparer)
            set("backgroundScope", scope)
            set("delayedTaskScheduler", scheduler)
            set("warmupController", VpnWarmupController<BackendVpnSession>(scheduler, 1L))
            set("statsCoordinator", VpnStatsCoordinator(service, scope, scheduler, { null }, { _, _ -> }))
            set("isXrayRuntimeAvailable", { true })
            set("underlyingNetworkSource", AndroidUnderlyingNetworkSource(service))
            set("notificationFactory", VpnNotificationFactory(service))
            set("settingsCommitCoordinator", VpnSettingsCommitCoordinator(store))
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

        fun startAccount() = invoke("startVpn", true, false)

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

        fun savedSettings(): StoredSettings = store.load()

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
            val types = args.map { if (it is Boolean) Boolean::class.javaPrimitiveType else Int::class.javaPrimitiveType }
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
