package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VpnSessionCoordinatorTest {
    @Test
    fun `failed TCP selection reports filters and empty profile before validation`() = runBlocking {
        val events = mutableListOf<String>()
        val candidates = listOf(
            tcpCandidate("eligible"),
            tcpCandidate("no-host").copy(entryHost = ""),
            tcpCandidate("insecure").copy(allowInsecure = true),
            tcpCandidate("canary").copy(canaryOnly = true),
        )
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(),
            repository = FakeVpnSessionStore(),
            backendApi = FakeVpnSessionApi(candidates = candidates),
            deviceNameProvider = { "Phone" },
            challengeSigner = { "sensitive-signature" },
            startupTcpPrecheck = { false },
            onDiagnostic = events::add,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id",
            backendDeviceKey = "sensitive-device-key",
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.AUTO),
        )
        val result = coordinator.prepare("sensitive-token", settings)
        assertTrue(result.settings.profile.host.isBlank())
        assertTrue(events.any { it.contains("received=4; with_host=3; security_allowed=2; protocol_allowed=2; eligible=1") })
        assertTrue(events.any { it.contains("stage=selection; selected=; tcp_failed=1; legacy=false") })
        assertTrue(events.any { it.contains("stage=validation; reason=missing_host") })
        assertTrue(events.none { it.contains("sensitive-") })
    }

    @Test
    fun `preparation logs exact API stage without challenge or credentials`() = runBlocking {
        val events = mutableListOf<String>()
        val api = FakeVpnSessionApi(sessionError = BackendException("Device challenge expired", 403))
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            deviceNameProvider = { "Phone" },
            challengeSigner = { "sensitive-signature" },
            onDiagnostic = events::add,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id",
            backendDeviceKey = "sensitive-device-key",
        )
        val error = runCatching { coordinator.prepare("sensitive-token", settings) }.exceptionOrNull()
        assertTrue(error is BackendException)
        assertTrue(events.any { it.contains("stage=session_create") && it.contains("http_status=403") && it.contains("reason=challenge_expired") })
        assertTrue(events.none { it.contains("sensitive-") || it.contains("nonce") })
    }

    @Test
    fun `validator exposes reason without changing boolean contract`() {
        val profile = VlessProfile(host = "node.example", port = "443", security = "tls", serverName = "node.example", uuid = "e912e725-2dc7-4b44-85d2-7fbc31a48b5e")
        val settings = DefaultStoredSettingsFactory.create().copy(
            profile = profile,
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.TLS),
        )
        assertNull(VpnProfileValidator.rejectionReason(settings))
        assertTrue(VpnProfileValidator.isUsable(settings))
        assertEquals("missing_host", VpnProfileValidator.rejectionReason(settings.copy(profile = profile.copy(host = ""))))
        assertEquals("invalid_credential", VpnProfileValidator.rejectionReason(settings.copy(profile = profile.copy(uuid = "do-not-log"))))
        assertEquals("protocol_mismatch", VpnProfileValidator.rejectionReason(settings.copy(advancedSettings = AdvancedSettings(protocol = VpnProtocol.REALITY))))
    }

    @Test
    fun `fresh prepare sends country and keeps concrete server runtime only`() = runBlocking {
        val api = FakeVpnSessionApi()
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            userProfile = UserProfile(
                selectedCountryCode = "DE",
                selectedServerCode = "lv-1",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        val result = coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals("DE", api.countryCode)
        assertNull(api.locationCode)
        assertEquals("DE", result.settings.userProfile.selectedCountryCode)
        assertEquals("de-2", result.settings.userProfile.selectedServerCode)
    }

    @Test
    fun `country request falls back to stored concrete server for legacy backend`() = runBlocking {
        val api = FakeVpnSessionApi(rejectCountryRequest = true)
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            userProfile = UserProfile(
                selectedCountryCode = "LV",
                selectedServerCode = "LV",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals(listOf("LV" to null, null to "lv"), api.requests)
    }

    @Test
    fun `blank country uses stored concrete server without invalid request`() = runBlocking {
        val api = FakeVpnSessionApi()
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            userProfile = UserProfile(
                selectedCountryCode = "",
                selectedServerCode = "lv2",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals(listOf(null to "lv2"), api.requests)
    }

    private class FakeVpnSessionStore : VpnSessionStore {
        override fun ensureBackendDeviceKey(existing: String): String = existing
        override fun loadEndpointHealth(): Map<String, EndpointHealth> = emptyMap()
        override fun nextEndpointRotationIndex(rotationKey: String): Int = 0
    }

    private class FakeVpnSessionApi(
        private val rejectCountryRequest: Boolean = false,
        private val sessionError: BackendException? = null,
        private val candidates: List<BackendEndpointCandidate> = emptyList(),
    ) : VpnSessionApi {
        var countryCode: String? = null
        var locationCode: String? = null
        val requests = mutableListOf<Pair<String?, String?>>()

        override suspend fun vpnAccess(
            token: String,
            deviceId: String?,
            deviceKey: String?,
        ) = BackendVpnAccess(canConnect = true, reason = null, planCode = null)

        override suspend fun registerDevice(
            token: String,
            deviceKey: String?,
            deviceId: String?,
            deviceName: String,
            publicKey: String,
            deviceClaims: List<String>,
            platform: String,
        ): BackendDevice = error("not used")

        override suspend fun createDeviceChallenge(
            token: String,
            deviceId: String,
        ) = BackendDeviceChallenge(deviceId = deviceId, nonce = "nonce", expiresAt = null)

        override suspend fun createVpnSession(
            token: String,
            deviceId: String,
            deviceKey: String?,
            deviceNonce: String,
            deviceSignature: String,
            countryCode: String?,
            locationCode: String?,
            excludeLocationCode: String?,
            profileCode: String,
        ): BackendVpnSession {
            sessionError?.let { throw it }
            this.countryCode = countryCode
            this.locationCode = locationCode
            requests += countryCode to locationCode
            if (rejectCountryRequest && countryCode != null && locationCode == null) {
                throw BackendException("Location not found", 503)
            }
            return BackendVpnSession(
                canConnect = true,
                profileCode = "tls",
                locationCode = "de-2",
                locationName = "Germany",
                endpointCode = "de-endpoint",
                entryHost = "de.example.com",
                entryPort = 443,
                serverName = "de.example.com",
                proxyType = "vless",
                transport = "tcp",
                transportMode = null,
                security = "tls",
                fingerprint = null,
                requestHost = null,
                path = null,
                alpn = null,
                allowInsecure = false,
                enableMux = false,
                randomUserAgent = false,
                publicKey = null,
                shortId = null,
                vpnUsername = "owner",
                vpnSecret = "secret",
                flow = null,
                planCode = null,
                endpointCandidates = candidates,
            )
        }
    }
}
