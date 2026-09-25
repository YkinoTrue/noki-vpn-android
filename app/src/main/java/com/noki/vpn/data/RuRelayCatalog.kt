package com.noki.vpn.data

import org.json.JSONObject
import java.util.UUID

data class RuRelayCatalogEntry(
    val id: String,
    val name: String,
    val countryCode: String,
    val available: Boolean,
    val unavailableReason: String?,
    val probeHost: String? = null,
)

data class RuRelayCatalog(
    val exitNodeId: String,
    val relays: List<RuRelayCatalogEntry>,
)

object RuRelayCatalogJsonParser {
    fun parse(json: JSONObject, expectedExitNodeId: String): RuRelayCatalog {
        val exitNodeId = json.getString("exit_node_id")
        require(exitNodeId == expectedExitNodeId) { "ru_relay_catalog_exit_mismatch" }
        val items = json.getJSONArray("relays")
        val relays = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = item.getString("id")
            UUID.fromString(id)
            val name = item.getString("name").trim()
            val countryCode = item.getString("country_code").uppercase()
            val available = item.getBoolean("available")
            val unavailableReason = item.optString("unavailable_reason")
                .takeIf { it.isNotBlank() && it != "null" }
            val probeHost = item.optString("probe_host").trim().takeIf { value ->
                available && value.split('.').let { parts ->
                    parts.size == 4 && parts.all { part ->
                        part.isNotEmpty() && part.all(Char::isDigit) &&
                            part.toIntOrNull()?.let { it in 0..255 } == true
                    }
                }
            }
            require(name.isNotEmpty() && countryCode == "RU") { "ru_relay_catalog_entry_invalid" }
            require(available || unavailableReason != null) { "ru_relay_catalog_reason_missing" }
            RuRelayCatalogEntry(id, name, countryCode, available, unavailableReason, probeHost)
        }
        require(relays.map { it.id }.toSet().size == relays.size) { "ru_relay_catalog_duplicate" }
        return RuRelayCatalog(exitNodeId, relays)
    }
}
