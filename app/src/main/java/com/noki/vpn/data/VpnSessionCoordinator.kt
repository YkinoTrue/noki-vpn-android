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
    private var sessionApi: VpnSessionApi = backendApi
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
        val network = context?.let { com.noki.vpn.vpn.AndroidUnderlyingNetworkSource(it).currentSnapshot()?.network }
        sessionApi = if (backendApi is BackendApiClient && network != null) backendApi.onNetwork(network) else backendApi
        val catalog = backendStep("server_catalog") {
            sessionApi.serverLocations(token, settings.backendDeviceId.ifBlank { null }, settings.backendDeviceKey.ifBlank { null })
        }
        val locations = BootstrapStateMapper.mapLocations(catalog, settings.personalizationSettings.language, emptyMap())
        val samples = if (context == null) emptyMap() else ClientLatencySampler(context = context).measure(locations)
        val scope = settings.userProfile.let { profile ->
            when {
                sessionSelection.nodeId != null -> profile.copy(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = sessionSelection.nodeId)
                profile.serverSelectionMode == ServerSelectionMode.COUNTRY -> profile.copy(selectedCountryCode = sessionSelection.countryCode)
                else -> profile
            }
        }
        val nodes = EndpointRankingPolicy.rankServers(
            locations.flatMap { it.withClientLatencies(samples).servers }.filter {
                it.id !in sessionSelection.excludedNodeIds &&
                    (sessionSelection.locationCode == null || it.locationCode == sessionSelection.locationCode) &&
                    it.locationCode != sessionSelection.excludeLocationCode
            }, scope,
        )
        if (nodes.isEmpty()) throw BackendException("No available servers", 503)
        var lastFailure: Exception = BackendException("No available servers", 503)
        for (node in nodes) {
            try {
                val result = prepareAtNode(token, settings, knownDevices, sessionSelection.copy(
                    countryCode = node.countryCode, locationCode = node.locationCode, nodeId = node.id,
                ))
                if (!VpnProfileValidator.isUsable(result.settings) && node != nodes.last()) continue
                return@withContext result.copy(settings = result.settings.copy(userProfile = result.settings.userProfile.copy(actualCountryCode = node.countryCode)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BackendException) {
                if (error.statusCode !in setOf(404, 503)) throw error
                lastFailure = error
            } catch (error: UnusableFreshProfileException) {
                lastFailure = error
            }
        }
        throw lastFailure
    }

    private suspend fun prepareAtNode(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        sessionSelection: VpnSessionSelection,
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
        val prepared = prepare(
            token = token,
            settings = settings.copy(
                advancedSettings = settings.advancedSettings.copy(protocol = VpnProtocol.AUTO),
            ),
            knownDevices = knownDevices,
            sessionSelection = VpnSessionSelection(
                countryCode = settings.userProfile.selectedCountryCode,
            ),
        )
        EndpointOptionsResult(
            session = prepared.session,
            endpointOptions = EndpointSelector.optionsFromSession(prepared.session),
            currentDevice = prepared.currentDevice,
            backendDeviceKey = prepared.currentDevice.deviceKey,
            backendDeviceId = prepared.currentDevice.id,
            backendDeviceAccessRole = prepared.settings.backendDeviceAccessRole,
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

        val access = backendStep("access") { sessionApi.vpnAccess(
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
            backendStep("device_challenge") { sessionApi.createDeviceChallenge(token, currentDevice.id) }
        } catch (error: BackendException) {
            if (error.statusCode !in RECOVERABLE_DEVICE_STATUS_CODES) throw error
            currentDevice = registerDevice(token, safeDeviceKey, null)
            backendStep("device_challenge") { sessionApi.createDeviceChallenge(token, currentDevice.id) }
        }
        deviceId = currentDevice.id
        accessRole = currentDevice.accessRole.ifBlank { accessRole }

        val signature = backendStep("challenge_sign") { challengeSigner(challenge.nonce) }
        val session = backendStep("session_create") { sessionApi.createVpnSession(
                token = token,
                deviceId = deviceId,
                deviceKey = currentDevice.deviceKey,
                deviceNonce = challenge.nonce,
                deviceSignature = signature,
                countryCode = sessionSelection.countryCode,
                locationCode = sessionSelection.locationCode,
                excludeLocationCode = sessionSelection.excludeLocationCode,
                profileCode = profileCode,
                nodeId = sessionSelection.nodeId,
        ) }
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
        return backendStep("device_register") { sessionApi.registerDevice(
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
        val network = context?.let { com.noki.vpn.vpn.AndroidUnderlyingNetworkSource(it).currentSnapshot()?.network }
        return DeviceLatency.measureTcpConnectMs(
            rawHost = candidate.connectionHost(),
            port = candidate.entryPort,
            timeoutMs = startupTcpPrecheckTimeoutMs,
            onDiagnostic = { result ->
                onDiagnostic("stage=tcp_precheck; endpoint=${candidate.code}; target=${candidate.connectionHost().take(255)}; port=${candidate.entryPort}; timeout_ms=$startupTcpPrecheckTimeoutMs; $result")
            },
            socketFactory = { network?.socketFactory?.createSocket() ?: java.net.Socket() },
            lookup = { host -> network?.getAllByName(host) ?: java.net.InetAddress.getAllByName(host) },
        ) != null
    }

    private companion object {
        private const val DEFAULT_STARTUP_TCP_PRECHECK_TIMEOUT_MS = 800
        private val RECOVERABLE_DEVICE_STATUS_CODES = setOf(400, 403, 404, 409)
    }
}
