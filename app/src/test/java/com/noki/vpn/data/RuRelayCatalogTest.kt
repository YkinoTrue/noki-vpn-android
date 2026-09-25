package com.noki.vpn.data

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuRelayCatalogTest {
    private val exitId = "11111111-1111-4111-8111-111111111111"
    private val readyId = "22222222-2222-4222-8222-222222222222"
    private val offlineId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun parsesAvailableAndUnavailableRuRelaysWithoutInventingAvailability() {
        val catalog = RuRelayCatalogJsonParser.parse(JSONObject("""{
            "exit_node_id":"$exitId",
            "relays":[
                {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"unavailable_reason":null,"probe_host":"8.8.8.8"},
                {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false,"unavailable_reason":"offline","probe_host":"1.1.1.1"}
            ]
        }"""), exitId)
        assertEquals(listOf(true, false), catalog.relays.map { it.available })
        assertEquals("offline", catalog.relays[1].unavailableReason)
        assertEquals("8.8.8.8", catalog.relays[0].probeHost)
        assertEquals(null, catalog.relays[1].probeHost)
    }

    @Test
    fun rejectsWrongExitAndMissingUnavailableReason() {
        val json = JSONObject("""{"exit_node_id":"$exitId","relays":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(json, offlineId)
        }
        val missingReason = JSONObject("""{"exit_node_id":"$exitId","relays":[
            {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false}
        ]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(missingReason, exitId)
        }
    }

    @Test
    fun apiScopesCatalogToAuthenticatedDeviceAndExactExit() = runBlocking {
        var observed: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"exit_node_id":"$exitId","relays":[]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example")
            .ruRelayCatalog("access", readyId, "proof", exitId)
        assertEquals(exitId, result.exitNodeId)
        assertEquals("/v1/vpn/ru-relays", observed!!.url.encodedPath)
        assertEquals(exitId, observed!!.url.queryParameter("exit_node_id"))
        assertEquals(readyId, observed!!.url.queryParameter("device_id"))
        assertEquals("Bearer access", observed!!.header("Authorization"))
        assertEquals(readyId, observed!!.header("X-Device-Id"))
        assertEquals("proof", observed!!.header("X-Device-Key"))
    }
}
