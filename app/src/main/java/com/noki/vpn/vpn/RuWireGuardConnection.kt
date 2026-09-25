package com.noki.vpn.vpn

import android.content.Context
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendRuWireGuardSession
import com.noki.vpn.data.DeviceIdentity
import com.noki.vpn.data.DeviceLatency
import com.noki.vpn.data.RuRelayCatalog
import com.noki.vpn.data.RuRelayRttSample
import com.noki.vpn.data.RuRelaySelection
import com.noki.vpn.data.RuWireGuardSessionRequest
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.WireGuardKeyPair
import com.noki.vpn.data.WireGuardKeyStore
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal data class PreparedRuWireGuardConnection(
    val session: BackendRuWireGuardSession.Ready,
    val privateKey: ByteArray,
    val exitNodeId: String,
)

/** The control plane selects a certified relay/exit pair before Android opens a TUN. */
internal class RuWireGuardConnectionCoordinator(
    private val context: Context,
    private val repository: SettingsRepository,
    private val api: BackendApiClient,
) {
    suspend fun prepare(settings: StoredSettings): PreparedRuWireGuardConnection {
        require(settings.advancedSettings.ruRelayEnabled && !settings.advancedSettings.multiHop.enabled) {
            "ru_wireguard_selection_invalid"
        }
        val token = settings.backendAccessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("auth_required")
        val deviceKey = repository.ensureBackendDeviceKey(settings.backendDeviceKey)
        val device = if (settings.backendDeviceId.isNotBlank()) {
            settings.backendDeviceId
        } else {
            val registered = api.registerDevice(
                token = token,
                deviceKey = deviceKey,
                deviceId = null,
                deviceName = AndroidDeviceInfo.deviceName(),
                publicKey = DeviceIdentity.publicKeyBase64(),
                deviceClaims = DeviceIdentity.deviceClaims(context),
                platform = "android",
            )
            val saved = repository.updateAndReturn { current ->
                if (current.backendAccessToken != token || !current.advancedSettings.ruRelayEnabled) {
                    current to false
                } else {
                    current.copy(backendDeviceId = registered.id, backendDeviceKey = registered.deviceKey) to true
                }
            }
            check(saved) { "selection_changed" }
            registered.id
        }
        val access = api.vpnAccess(token, device, deviceKey)
        check(access.canConnect) { access.reason ?: "vpn_access_denied" }
        val pair = WireGuardKeyStore.android(context).getOrCreate()
        val locations = api.serverLocations(token, device, deviceKey)
        val country = settings.userProfile.selectedCountryCode.uppercase()
        val candidates = locations.asSequence()
            .filter { it.countryCode.uppercase() == country && it.isOnline }
            .flatMap { it.servers.asSequence() }
            .filter { it.isOnline && it.countryCode.uppercase() == country }
            .distinctBy(VpnServer::id)
            .filter { settings.userProfile.selectedNodeId.isBlank() ||
                settings.userProfile.serverSelectionMode != com.noki.vpn.data.ServerSelectionMode.SERVER ||
                it.id == settings.userProfile.selectedNodeId }
            .sortedWith(compareByDescending<VpnServer> { it.weight }.thenBy { it.id })
            .toList()
        var selectedExit: VpnServer? = null
        var selectedCatalog: RuRelayCatalog? = null
        for (candidate in candidates) {
            val catalog = api.ruRelayCatalog(token, device, deviceKey, candidate.id)
            val available = catalog.relays.any { relay -> relay.available }
            if (available) {
                selectedExit = candidate
                selectedCatalog = catalog
                break
            }
        }
        val exit = selectedExit ?: throw IllegalStateException("ru_relay_pair_unavailable")
        val relayRttSamples = measureRelayRtt(selectedCatalog!!)
        val keyProof = api.createDeviceChallenge(token, device)
        val key = api.registerRuWireGuardKey(
            token, device, deviceKey, keyProof.nonce,
            DeviceIdentity.signChallenge(keyProof.nonce), pair.publicKeyBase64(),
        )
        val sessionProof = api.createDeviceChallenge(token, device)
        val request = RuWireGuardSessionRequest(
            deviceId = device,
            deviceKey = deviceKey,
            deviceNonce = sessionProof.nonce,
            deviceSignature = DeviceIdentity.signChallenge(sessionProof.nonce),
            requestId = UUID.randomUUID().toString(),
            publicKeyId = key.id,
            exitNodeId = exit.id,
            relaySelection = RuRelaySelection.Auto,
            relayRttSamples = relayRttSamples,
        )
        var response = api.createRuWireGuardSession(token, request)
        val initialId = response.identity.sessionId
        val initialGeneration = response.identity.generation
        repeat(15) {
            if (response is BackendRuWireGuardSession.Ready) {
                return PreparedRuWireGuardConnection(response, pair.privateKey, exit.id)
            }
            delay(2_000)
            response = api.pollRuWireGuardSession(token, request, initialId)
            check(response.identity.sessionId == initialId &&
                response.identity.generation == initialGeneration) { "ru_wireguard_session_changed" }
        }
        throw IllegalStateException("ru_wireguard_session_timeout")
    }

    private suspend fun measureRelayRtt(catalog: RuRelayCatalog): List<RuRelayRttSample> = coroutineScope {
        val slots = Semaphore(6)
        catalog.relays.asSequence().filter { it.available && it.probeHost != null }
            .take(32).map { relay ->
                async {
                    slots.withPermit {
                        val rtt = withTimeoutOrNull(2_500L) {
                            runInterruptible(Dispatchers.IO) {
                                DeviceLatency.measureIcmpPingMs(relay.probeHost!!, count = 1,
                                    timeoutSeconds = 1)
                            }
                        }
                        rtt?.takeIf { it in 1..3000 }?.let { RuRelayRttSample(relay.id, it) }
                    }
                }
            }.toList().awaitAll().filterNotNull()
    }
}

internal fun wireGuardIpcConfig(connection: PreparedRuWireGuardConnection): String {
    val config = connection.session.wireguard
    val serverKey = Base64.getDecoder().decode(config.serverPublicKey)
    require(connection.privateKey.size == 32 && serverKey.size == 32)
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    return buildString {
        append("private_key=").append(connection.privateKey.hex()).append('\n')
        append("replace_peers=true\n")
        append("public_key=").append(serverKey.hex()).append('\n')
        append("endpoint=").append(config.endpoint).append('\n')
        for (allowed in config.allowedIps) append("allowed_ip=").append(allowed).append('\n')
        append("persistent_keepalive_interval=5\n")
    }.also { serverKey.fill(0) }
}
