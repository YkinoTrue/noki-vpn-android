package com.noki.vpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.Locale

interface VpnSessionStore {
    fun ensureBackendDeviceKey(existing: String): String
    fun loadEndpointHealth(): Map<String, EndpointHealth>
    fun loadEndpointHealth(networkKind: EndpointRankingPolicy.NetworkKind): Map<String, EndpointHealth> =
        loadEndpointHealth()
    fun nextEndpointRotationIndex(rotationKey: String): Int
}

class VpnSessionCoordinator(
    private val context: Context?,
    private val repository: VpnSessionStore,
    private val backendApi: VpnSessionApi = BackendApiClient(),
    private val deviceNameProvider: () -> String = AndroidDeviceInfo::deviceName,
    private val publicKeyProvider: () -> String = DeviceIdentity::publicKeyBase64,
    private val challengeSigner: (String) -> String = DeviceIdentity::signChallenge,
    private val deviceClaimsProvider: (Context) -> List<String> = DeviceIdentity::deviceClaims,
    private val startupTcpPrecheckTimeoutMs: Int = DEFAULT_STARTUP_TCP_PRECHECK_TIMEOUT_MS,
    private val startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)? = null,
    private val endpointSelectionProvider: ((
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)?,
    ) -> EndpointSelector.EndpointSelectionResult)? = null,
    private val onDiagnostic: (String) -> Unit = {},
) {
    data class Result(
        val settings: StoredSettings,
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val selection: EndpointSelector.EndpointSelectionResult,
    )

    data class EndpointOptionsResult(
        val session: BackendVpnSession,
        val endpointOptions: List<VpnEndpointOption>,
        val currentDevice: BackendDevice,
        val backendDeviceKey: String,
        val backendDeviceId: String,
        val backendDeviceAccessRole: String,
    )

    suspend fun prepare(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice> = emptyList(),
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): Result = withContext(Dispatchers.IO) {
        val deviceSession = createDeviceSession(
            token = token,
            settings = settings,
            knownDevices = knownDevices,
            profileCode = settings.advancedSettings.protocol.name.lowercase(Locale.ROOT),
            sessionSelection = sessionSelection,
        )

        val networkKind = context?.let(EndpointSelector::currentNetworkKind)
            ?: EndpointRankingPolicy.NetworkKind.OTHER
        val endpointHealth = repository.loadEndpointHealth(networkKind)
        val tcpPrecheck = startupTcpPrecheck ?: ::defaultStartupTcpPrecheck
        val selection = endpointSelectionProvider?.invoke(
            deviceSession.session,
            settings.advancedSettings,
            endpointHealth,
            repository::nextEndpointRotationIndex,
            tcpPrecheck,
        ) ?: EndpointSelector.selectionForSession(
            context = requireContext(),
            session = deviceSession.session,
            settings = settings.advancedSettings,
            endpointHealth = endpointHealth,
            rotationIndex = repository::nextEndpointRotationIndex,
            startupTcpPrecheck = tcpPrecheck,
            networkKind = networkKind,
            onDiagnostic = onDiagnostic,
        )
        val endpointOptions = EndpointSelector.optionsFromSession(deviceSession.session)
        val advancedSettings = EndpointGroupPolicy.settingsAfterSelection(
            settings = settings.advancedSettings,
            endpointCode = selection.endpointCode,
            endpointOptions = endpointOptions,
        )
        val updatedSettings = settings.copy(
            profile = selection.profile,
            advancedSettings = advancedSettings,
            endpointOptions = endpointOptions,
            userProfile = settings.userProfile.copy(
                selectedPlanCode = PlanCode.fromBackend(deviceSession.session.planCode, settings.userProfile.selectedPlanCode),
                selectedPlanCodeRaw = deviceSession.session.planCode?.ifBlank { null }
                    ?: settings.userProfile.selectedPlanCodeRaw,
                selectedServerCode = deviceSession.session.locationCode,
            ),
            backendDeviceKey = deviceSession.currentDevice.deviceKey,
            backendDeviceId = deviceSession.currentDevice.id,
            backendDeviceAccessRole = deviceSession.accessRole,
        )
        onDiagnostic("stage=validation; reason=${VpnProfileValidator.rejectionReason(updatedSettings) ?: "ok"}; endpoint=${selection.endpointCode}; candidates=${endpointOptions.size}")
        Result(
            settings = updatedSettings,
            session = deviceSession.session,
            currentDevice = deviceSession.currentDevice,
            selection = selection,
        )
    }

    suspend fun endpointOptions(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice> = emptyList(),
    ): EndpointOptionsResult = withContext(Dispatchers.IO) {
        val deviceSession = createDeviceSession(
            token = token,
            settings = settings,
            knownDevices = knownDevices,
            profileCode = VpnProtocol.AUTO.name.lowercase(Locale.ROOT),
            sessionSelection = VpnSessionSelection(
                countryCode = settings.userProfile.selectedCountryCode,
            ),
        )
        EndpointOptionsResult(
            session = deviceSession.session,
            endpointOptions = EndpointSelector.optionsFromSession(deviceSession.session),
            currentDevice = deviceSession.currentDevice,
            backendDeviceKey = deviceSession.currentDevice.deviceKey,
            backendDeviceId = deviceSession.currentDevice.id,
            backendDeviceAccessRole = deviceSession.accessRole,
        )
    }

    private data class DeviceSession(
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val accessRole: String,
    )

    private suspend fun createDeviceSession(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        profileCode: String,
        sessionSelection: VpnSessionSelection,
    ): DeviceSession {
        val safeDeviceKey = repository.ensureBackendDeviceKey(settings.backendDeviceKey)
        var deviceId = settings.backendDeviceId
        var accessRole = settings.backendDeviceAccessRole.ifBlank { "owner" }

        val access = backendStep("access") { backendApi.vpnAccess(
            token = token,
            deviceId = deviceId.ifBlank { null },
            deviceKey = safeDeviceKey,
        ) }
        if (!access.canConnect) {
            throw BackendException(access.reason.orEmpty().ifBlank { "vpn_access_denied" }, 403)
        }

        var currentDevice = if (deviceId.isNotBlank() && safeDeviceKey.isNotBlank()) {
            knownDevices.firstOrNull { device ->
                device.id == deviceId || device.deviceKey == safeDeviceKey
            } ?: BackendDevice(
                id = deviceId,
                deviceKey = safeDeviceKey,
                deviceName = deviceNameProvider(),
                platform = "android",
                accessRole = accessRole,
                isActive = true,
                lastSeenAt = null,
            )
        } else {
            registerDevice(token, safeDeviceKey, deviceId.ifBlank { null })
        }

        val challenge = try {
            backendStep("device_challenge") { backendApi.createDeviceChallenge(token, currentDevice.id) }
        } catch (error: BackendException) {
            if (error.statusCode !in RECOVERABLE_DEVICE_STATUS_CODES) throw error
            currentDevice = registerDevice(token, safeDeviceKey, null)
            backendStep("device_challenge") { backendApi.createDeviceChallenge(token, currentDevice.id) }
        }
        deviceId = currentDevice.id
        accessRole = currentDevice.accessRole.ifBlank { accessRole }

        val countryCode = sessionSelection.countryCode.trim().takeIf { it.length >= 2 }
        val explicitLocationCode = sessionSelection.locationCode?.trim()?.takeIf { it.isNotEmpty() }
        val legacyLocationCode = settings.userProfile.selectedServerCode
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }
        val requestSession: suspend (String?, String?) -> BackendVpnSession = { requestedCountry, requestedLocation ->
            val signature = backendStep("challenge_sign") { challengeSigner(challenge.nonce) }
            backendStep("session_create") { backendApi.createVpnSession(
                token = token,
                deviceId = deviceId,
                deviceKey = currentDevice.deviceKey,
                deviceNonce = challenge.nonce,
                deviceSignature = signature,
                countryCode = requestedCountry,
                locationCode = requestedLocation,
                excludeLocationCode = sessionSelection.excludeLocationCode,
                profileCode = profileCode,
            ) }
        }
        val session = try {
            requestSession(
                countryCode,
                explicitLocationCode ?: legacyLocationCode.takeIf { countryCode == null },
            )
        } catch (error: BackendException) {
            val legacyBackend = countryCode != null &&
                explicitLocationCode == null &&
                legacyLocationCode != null &&
                error.statusCode == 503 &&
                error.message.equals("Location not found", ignoreCase = true)
            if (!legacyBackend) throw error
            requestSession(null, legacyLocationCode)
        }
        if (!session.canConnect) {
            throw BackendException("vpn_access_denied", 403)
        }
        return DeviceSession(
            session = session,
            currentDevice = currentDevice,
            accessRole = accessRole,
        )
    }

    private suspend fun registerDevice(
        token: String,
        deviceKey: String,
        deviceId: String?,
    ): BackendDevice {
        return backendStep("device_register") { backendApi.registerDevice(
            token = token,
            deviceKey = deviceKey,
            deviceId = deviceId,
            deviceName = deviceNameProvider(),
            publicKey = publicKeyProvider(),
            deviceClaims = deviceClaimsProvider(requireContext()),
            platform = "android",
        ) }
    }

    private suspend fun <T> backendStep(stage: String, block: suspend () -> T): T {
        onDiagnostic("stage=$stage; result=start")
        return try {
            block().also { onDiagnostic("stage=$stage; result=ok") }
        } catch (error: BackendException) {
            val reason = if (error.message.equals("Device challenge expired", ignoreCase = true)) {
                "challenge_expired"
            } else "backend_error"
            onDiagnostic("stage=$stage; result=error; http_status=${error.statusCode}; reason=$reason")
            throw error
        } catch (error: CancellationException) {
            onDiagnostic("stage=$stage; result=cancelled_or_timeout")
            throw error
        } catch (error: Exception) {
            onDiagnostic("stage=$stage; result=error; reason=transport_or_local_error")
            throw error
        }
    }

    private fun requireContext(): Context {
        return checkNotNull(context) { "Android context is required for this VPN session operation" }
    }

    private fun defaultStartupTcpPrecheck(candidate: BackendEndpointCandidate): Boolean {
        return DeviceLatency.measureTcpConnectMs(
            rawHost = candidate.connectionHost(),
            port = candidate.entryPort,
            timeoutMs = startupTcpPrecheckTimeoutMs,
            onDiagnostic = { result ->
                onDiagnostic("stage=tcp_precheck; endpoint=${candidate.code}; target=${candidate.connectionHost().take(255)}; port=${candidate.entryPort}; timeout_ms=$startupTcpPrecheckTimeoutMs; $result")
            },
        ) != null
    }

    private companion object {
        private const val DEFAULT_STARTUP_TCP_PRECHECK_TIMEOUT_MS = 800
        private val RECOVERABLE_DEVICE_STATUS_CODES = setOf(400, 403, 404, 409)
    }
}
