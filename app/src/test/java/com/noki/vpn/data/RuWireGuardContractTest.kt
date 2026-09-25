package com.noki.vpn.data

import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class RuWireGuardContractTest {
    @Test
    fun keyEnrollmentSendsOnlyPublicKeyAndDeviceProof() = runBlocking {
        val keyId = "11111111-1111-4111-8111-111111111111"
        val deviceId = "22222222-2222-4222-8222-222222222222"
        val publicKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        var observedRequest: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observedRequest = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"id":"$keyId","public_key":"$publicKey"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example").registerRuWireGuardKey(
            token = "access", deviceId = deviceId, deviceKey = "proof",
            deviceNonce = "nonce-1234567890123456", deviceSignature = "signature".repeat(8),
            publicKey = publicKey,
        )
        assertEquals(keyId, result.id)
        assertEquals(publicKey, result.publicKey)
        val request = observedRequest!!
        assertEquals("/v1/vpn/wireguard-keys", request.url.encodedPath)
        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals(deviceId, request.header("X-Device-Id"))
        val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(publicKey, payload.getString("public_key"))
        assertFalse(payload.has("private_key"))
        assertEquals(setOf("device_id", "device_key", "device_nonce", "device_signature", "public_key"),
            payload.keys().asSequence().toSet())
    }
    private fun fixture(): JSONObject {
        val path = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "contracts/ru_wireguard_session_v1.json") }
            .first { it.isFile }
        return JSONObject(path.readText())
    }

    private fun expectation(root: JSONObject): RuWireGuardSessionExpectation {
        val request = root.getJSONObject("request")
        return RuWireGuardSessionExpectation(
            requestId = request.getString("request_id"),
            publicKeyId = request.getString("public_key_id"),
            exitNodeId = request.getString("exit_node_id"),
            relaySelection = RuRelaySelection.Node(request.getJSONObject("relay_selection").getString("node_id")),
        )
    }

    @Test
    fun sharedReadyAndPendingResponsesKeepManualRoute() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root)
        val ready = RuWireGuardSessionJsonParser.parse(root.getJSONObject("ready"), expected, now)
        val pending = RuWireGuardSessionJsonParser.parse(root.getJSONObject("pending"), expected, now)
        assertTrue(ready is BackendRuWireGuardSession.Ready)
        assertTrue(pending is BackendRuWireGuardSession.Pending)
        assertEquals("10.87.0.2/32", (ready as BackendRuWireGuardSession.Ready).wireguard.address)
        assertEquals(expected.exitNodeId, ready.identity.exitNodeId)
    }

    @Test
    fun autoRequestCarriesNoManualNodeAndResponseKeepsChosenRelay() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root).copy(relaySelection = RuRelaySelection.Auto)
        val payload = JSONObject(root.getJSONObject("ready").toString())
        payload.put("relay_selection", RuRelaySelection.Auto.toBackendJson())
        val result = RuWireGuardSessionJsonParser.parse(payload, expected, now)
        assertEquals(root.getJSONObject("ready").getString("relay_node_id"), result.identity.relayNodeId)
        assertFalse(RuRelaySelection.Auto.toBackendJson().has("node_id"))
        assertEquals(result.identity.relayNodeId,
            RuRelaySelection.Node(result.identity.relayNodeId).toBackendJson().getString("node_id"))
    }

    @Test
    fun requestMappingSendsOnlyTheRuRouteAndSelectedExit() {
        val root = fixture()
        val expected = expectation(root)
        val auto = RuWireGuardSessionRequest(
            deviceId = root.getJSONObject("request").getString("device_id"),
            deviceKey = "device-key", deviceNonce = "nonce-1234567890123456", deviceSignature = "signature",
            requestId = expected.requestId, publicKeyId = expected.publicKeyId,
            exitNodeId = expected.exitNodeId, relaySelection = RuRelaySelection.Auto,
            relayRttSamples = listOf(RuRelayRttSample(
                root.getJSONObject("ready").getString("relay_node_id"), 24)),
        ).toBackendJson()
        assertEquals("ru_wg_nebula", auto.getString("route_mode"))
        assertEquals("auto", auto.getString("profile_code"))
        assertEquals(expected.exitNodeId, auto.getString("exit_node_id"))
        assertEquals("auto", auto.getJSONObject("relay_selection").getString("kind"))
        assertFalse(auto.getJSONObject("relay_selection").has("node_id"))
        assertEquals(24, auto.getJSONArray("relay_rtt_samples").getJSONObject(0).getInt("rtt_ms"))
        assertFalse(auto.has("node_id"))
        assertFalse(auto.has("multihop"))
        val manual = RuWireGuardSessionRequest(
            deviceId = root.getJSONObject("request").getString("device_id"),
            deviceKey = null, deviceNonce = "nonce-1234567890123456", deviceSignature = "signature",
            requestId = expected.requestId, publicKeyId = expected.publicKeyId,
            exitNodeId = expected.exitNodeId, relaySelection = expected.relaySelection,
        ).toBackendJson()
        assertEquals((expected.relaySelection as RuRelaySelection.Node).nodeId,
            manual.getJSONObject("relay_selection").getString("node_id"))
    }

    @Test
    fun expiredUnknownEngineWrongKeyAndWrongRelayFailClosed() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root)
        val mutations = listOf<Pair<String, String>>(
            "lease_expires_at" to "2026-09-25T11:59:59Z",
            "engine" to "xray",
            "public_key_id" to "77777777-7777-4777-8777-777777777777",
            "relay_node_id" to "77777777-7777-4777-8777-777777777777",
            "exit_node_id" to "77777777-7777-4777-8777-777777777777",
        )
        for ((key, value) in mutations) {
            val payload = JSONObject(root.getJSONObject("ready").toString()).put(key, value)
            assertThrows(key, IllegalArgumentException::class.java) {
                RuWireGuardSessionJsonParser.parse(payload, expected, now)
            }
        }
        val missingConfig = JSONObject(root.getJSONObject("ready").toString())
        missingConfig.remove("wireguard")
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(missingConfig, expected, now)
        }
        val badKey = JSONObject(root.getJSONObject("ready").toString())
        badKey.getJSONObject("wireguard").put("server_public_key", "bad")
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(badKey, expected, now)
        }
    }
}
