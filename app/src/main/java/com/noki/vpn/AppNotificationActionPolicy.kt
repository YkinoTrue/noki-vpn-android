package com.noki.vpn

import android.content.Context
import java.util.UUID

internal enum class AppNotificationAction(val wireValue: String) {
    OpenSecurityUpdate("open_security_update");

    companion object {
        fun parse(value: String?): AppNotificationAction? =
            entries.firstOrNull { it.wireValue == value?.trim() }
    }
}

internal object AppNotificationActionNonceStore {
    internal const val TTL_MILLIS = 7 * 24 * 60 * 60_000L
    internal const val MAX_NONCES = 64

    @Synchronized
    fun issue(context: Context, nowMillis: Long = System.currentTimeMillis()): String? {
        val nonce = UUID.randomUUID().toString()
        val updated = validEntries(context, nowMillis).takeLast(MAX_NONCES - 1) + "$nowMillis:$nonce"
        return nonce.takeIf { save(context, updated.toSet()) }
    }

    @Synchronized
    fun validateAndConsume(
        context: Context,
        action: String?,
        nonce: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): AppNotificationAction? {
        val parsed = AppNotificationAction.parse(action)
        val entries = validEntries(context, nowMillis)
        val matching = entries.firstOrNull { it.substringAfter(':') == nonce?.trim() }
        val accepted = parsed != null && matching != null
        val updated = if (accepted) entries - matching else entries
        // Persist removal before accepting, including across process restarts.
        return parsed.takeIf { save(context, updated.toSet()) && accepted }
    }

    private fun validEntries(context: Context, nowMillis: Long): List<String> =
        preferences(context).getStringSet(KEY_NONCES, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val issuedAt = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
                if (nowMillis - issuedAt in 0 until TTL_MILLIS) issuedAt to entry else null
            }
            .sortedBy { it.first }
            .takeLast(MAX_NONCES)
            .map { it.second }

    private fun save(context: Context, entries: Set<String>): Boolean = preferences(context).edit()
        .remove("issued_nonces")
        .putStringSet(KEY_NONCES, entries)
        .commit()

    private fun preferences(context: Context) =
        context.getSharedPreferences("noki_app_notification_action_nonces", Context.MODE_PRIVATE)

    private const val KEY_NONCES = "timed_nonces"
}
