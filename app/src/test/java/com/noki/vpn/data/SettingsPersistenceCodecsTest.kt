package com.noki.vpn.data

import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceCodecsTest {
    private val defaults = DefaultStoredSettingsFactory.create(Locale.US)
    private val settingsCodec = StoredSettingsCodec { defaults }

    @Test
    fun `RU relay defaults off and preserves normal protocol across storage`() {
        assertFalse(settingsCodec.decode(null).advancedSettings.ruRelayEnabled)
        val settings = defaults.copy(advancedSettings = defaults.advancedSettings.copy(
            protocol = VpnProtocol.REALITY,
            ruRelayEnabled = true,
        ))
        val restored = settingsCodec.decode(settingsCodec.encode(settings)).advancedSettings
        assertTrue(restored.ruRelayEnabled)
        assertEquals(VpnProtocol.REALITY, restored.protocol)
        assertEquals(VpnProtocol.WIREGUARD, restored.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, restored.copy(ruRelayEnabled = false).effectiveProtocol)
        val damaged = settingsCodec.decode("""{"protocol":"WIREGUARD","ruRelayEnabled":false}""")
        assertFalse(damaged.advancedSettings.ruRelayEnabled)
        assertEquals(VpnProtocol.AUTO, damaged.advancedSettings.effectiveProtocol)
    }

    @Test
    fun `legacy manual RU preference is ignored after upgrade`() {
        val saved = defaults.copy(ruRelaySelectionRevision = 17L, advancedSettings = defaults.advancedSettings.copy(
            ruRelayEnabled = true,
        ))
        val legacy = org.json.JSONObject(settingsCodec.encode(saved))
            .put("ruRelayAutoPath", false).put("manualRuRelayNodeId", "relay-a").toString()
        val restored = settingsCodec.decode(legacy)
        assertTrue(restored.advancedSettings.ruRelayEnabled)
        assertEquals(RuRelaySelection.Auto, restored.advancedSettings.ruRelaySelection)
        assertEquals(17L, restored.ruRelaySelectionRevision)
        assertFalse(settingsCodec.encode(restored).contains("manualRuRelayNodeId"))
    }

    @Test
    fun `multiHop intent and v2 runtime survive process storage round trip`() {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "node-b"))
        val runtime = MultiHopRuntime(selection, "node-a", "node-b", "a".repeat(64))
        val stored = defaults.copy(
            profile = defaults.profile.copy(multiHop = runtime),
            advancedSettings = defaults.advancedSettings.copy(multiHop = selection),
        )
        val restored = settingsCodec.decode(settingsCodec.encode(stored))
        assertEquals(selection, restored.advancedSettings.multiHop)
        assertEquals(runtime, restored.profile.multiHop)
        val legacy = settingsCodec.encode(stored).replace("\"version\":2", "\"version\":1")
        val migrated = settingsCodec.decode(legacy)
        assertEquals(selection, migrated.advancedSettings.multiHop)
        assertNull(migrated.profile.multiHop)
    }

    @Test
    fun `kill switch is opt in and survives settings round trip`() {
        assertFalse(settingsCodec.decode(null).advancedSettings.killSwitchEnabled)
        assertFalse(settingsCodec.decode("{}").advancedSettings.killSwitchEnabled)
        val enabled = defaults.copy(advancedSettings = defaults.advancedSettings.copy(killSwitchEnabled = true))
        assertTrue(settingsCodec.decode(settingsCodec.encode(enabled)).advancedSettings.killSwitchEnabled)
    }

    @Test
    fun `fresh selection is auto while legacy storage retains its country`() {
        assertEquals(ServerSelectionMode.AUTO, settingsCodec.decode(null).userProfile.serverSelectionMode)
        val migrated = settingsCodec.decode("""{"selectedCountryCode":"DE"}""")
        assertEquals(ServerSelectionMode.COUNTRY, migrated.userProfile.serverSelectionMode)
        assertEquals("DE", migrated.userProfile.selectedCountryCode)
    }

    @Test
    fun `manual node and actual connection round trip independently`() {
        val profile = defaults.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.SERVER,
            selectedCountryCode = "DE",
            selectedNodeId = "node-a",
            selectedServerCode = "de-frankfurt",
            actualCountryCode = "DE",
        )
        assertEquals(profile, settingsCodec.decode(settingsCodec.encode(defaults.copy(userProfile = profile))).userProfile)
    }

    @Test
    fun `empty storage gets Russian direct routing defaults`() {
        val fresh = settingsCodec.decode(null)

        assertEquals(AppFilterMode.ALL_EXCEPT_SELECTED, fresh.filterMode)
        val legacy = setOf(
                "ru.rostel",
                "ru.sberbankmobile",
                "com.idamob.tinkoff.android",
                "ru.alfabank.mobile.android",
                "ru.vtb24.mobilebanking.android",
                "ru.gazprombank.android.mobilebank.app",
                "ru.nspk.mirpay",
                "ru.yandex.searchplugin",
                "ru.yandex.yandexmaps",
                "ru.yandex.taxi",
                "ru.beru.android",
                "ru.yandex.music",
                "ru.kinopoisk",
                "ru.dublgis.dgismobile",
                "ru.ozon.app.android",
                "com.wildberries.ru",
                "com.avito.android",
                "com.vkontakte.android",
                "ru.ok.android",
                "ru.mail.mailapp",
                "ru.rutube.app",
                "ru.sbcs.store",
            )
        assertTrue(fresh.selectedPackages.containsAll(legacy))
        assertTrue(fresh.selectedPackages.containsAll(setOf("ru.oneme.app", "com.vk.vkvideo", "ru.mts.mymts", "com.logistic.sdek")))
        val legacyPayload = org.json.JSONObject(settingsCodec.encode(fresh.copy(selectedPackages = legacy)))
            .apply { remove("russianDirectDefaultsVersion") }.toString()
        assertEquals(fresh.selectedPackages, settingsCodec.decode(legacyPayload).selectedPackages)
        assertEquals(legacy, settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = legacy))).selectedPackages)
        val custom = setOf("com.example.app")
        assertEquals(custom, settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = custom))).selectedPackages)
        assertEquals(emptySet<String>(), settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = emptySet()))).selectedPackages)
        assertEquals(
            listOf("geosite:category-ru"),
            fresh.advancedSettings.bypassDomains,
        )
    }

    @Test
    fun `stored settings current payload round trips`() {
        val expected = defaults.copy(
            profile = defaults.profile.copy(
                youtubeCascade = YoutubeCascadeProfile(
                    host = "ru-cascade.example",
                    uuid = "cascade-uuid",
                    serverName = "www.lu.lv",
                    publicKey = "cascade-public",
                    shortId = "cascade-short",
                ),
            ),
            selectedPackages = setOf("org.example.two", "org.example.one"),
            isAuthenticated = true,
            backendAccessToken = "access",
            backendRefreshRequestId = "861087a2-a3e8-4e92-b89a-18377e6212ee",
            advancedSettings = defaults.advancedSettings.copy(
                alwaysOnDomains = listOf("domain:example.com"),
                bypassDomains = listOf("domain:internal.example"),
            ),
        )

        assertEquals(expected, settingsCodec.decode(settingsCodec.encode(expected)))
    }

    @Test
    fun `removed YouTube DNS preference is ignored and not persisted`() {
        val decoded = settingsCodec.decode("""{"youtubeRussianDnsEnabled":true}""")

        assertFalse(settingsCodec.encode(decoded).contains("youtubeRussianDnsEnabled"))
    }

    @Test
    fun `YouTube direct DPI mode defaults off and round trips`() {
        assertFalse(
            settingsCodec.decode("""{}""")
                .advancedSettings
                .youtubeDirectDpiEnabled,
        )

        val enabled = defaults.copy(
            advancedSettings = defaults.advancedSettings.copy(
                youtubeDirectDpiEnabled = true,
            ),
        )
        val encoded = settingsCodec.encode(enabled)
        val decoded = settingsCodec.decode(encoded)

        assertTrue(decoded.advancedSettings.youtubeDirectDpiEnabled)
        assertTrue(encoded.contains("\"youtubeDirectDpiEnabled\":true"))
    }

    @Test
    fun `stored settings persists country preference without concrete server`() {
        val encoded = settingsCodec.encode(
            defaults.copy(
                userProfile = defaults.userProfile.copy(
                    selectedCountryCode = "DE",
                    selectedServerCode = "de-2",
                ),
            ),
        )
        val decoded = settingsCodec.decode(encoded)

        assertEquals("DE", decoded.userProfile.selectedCountryCode)
        assertEquals("de-2", decoded.userProfile.selectedServerCode)
        assertTrue(encoded.contains("\"selectedServerCode\""))
    }

    @Test
    fun `stored settings glass mode round trips and replaces legacy key`() {
        GlassMode.entries.forEach { mode ->
            val encoded = settingsCodec.encode(
                defaults.copy(
                    personalizationSettings = defaults.personalizationSettings.copy(glassMode = mode),
                ),
            )

            assertEquals(mode, settingsCodec.decode(encoded).personalizationSettings.glassMode)
            assertFalse(encoded.contains("simpleModeEnabled"))
        }
    }

    @Test
    fun `stored settings migrates legacy simple mode`() {
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"simpleModeEnabled":true}""")
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"glassMode":"SIMPLE_ANIMATION"}""")
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"glassMode":"broken","simpleModeEnabled":true}""")
                .personalizationSettings.glassMode,
        )
    }

    @Test
    fun `fresh install defaults to simple only below full glass api`() {
        assertTrue(isFreshPackageInstall(firstInstallTime = 100L, lastUpdateTime = 100L))
        assertFalse(isFreshPackageInstall(firstInstallTime = 100L, lastUpdateTime = 200L))
        assertEquals(
            GlassMode.SIMPLE,
            DefaultStoredSettingsFactory.create(sdkInt = 32, isFreshInstall = true)
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            DefaultStoredSettingsFactory.create(sdkInt = 33, isFreshInstall = true)
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            DefaultStoredSettingsFactory.create(sdkInt = 32, isFreshInstall = false)
                .personalizationSettings.glassMode,
        )
    }

    @Test
    fun `stored settings legacy payload gets defaults and normalization`() {
        val decoded = settingsCodec.decode(
            """{"username":"legacy","alwaysOnDomains":["example.com","domain:example.com"]}""",
        )

        assertEquals("legacy", decoded.userProfile.username)
        assertEquals(listOf("domain:example.com"), decoded.advancedSettings.alwaysOnDomains)
        assertEquals("owner", decoded.backendDeviceAccessRole)
        assertEquals(AppFilterMode.ALL_APPS, decoded.filterMode)
        assertTrue(decoded.selectedPackages.isEmpty())
        assertTrue(decoded.advancedSettings.bypassDomains.isEmpty())
    }

    @Test
    fun `stored settings corrupt payload falls back to supplied defaults`() {
        assertEquals(defaults, settingsCodec.decode("{not-json"))
    }

    @Test
    fun `daily stats codec clamps values drops corrupt and prunes expired days`() {
        val today = LocalDate.of(2026, 7, 12)
        val raw = """[
            {"date":"2026-07-12","rxBytes":-4,"sessions":-2},
            {"date":"2024-01-01","rxBytes":8},
            {"date":"not-a-date","rxBytes":9}
        ]""".trimIndent()

        assertEquals(listOf(DailyStats(date = "2026-07-12")), DailyStatsStore.decode(raw, today))
        assertTrue(DailyStatsStore.decode("broken", today).isEmpty())
    }

    @Test
    fun `app logs codec round trips and prunes invalid timestamps`() {
        val now = Instant.parse("2026-07-12T00:00:00Z")
        val current = AppLogEntry(
            timestamp = "2026-07-11T00:00:00Z",
            level = "info",
            category = "vpn",
            message = "connected",
            connectionSuccess = true,
        )
        val expired = current.copy(timestamp = "2025-01-01T00:00:00Z")
        val recent = current.copy(timestamp = Instant.now().minusSeconds(60).toString())

        assertEquals(listOf(current), AppLogStore.prune(listOf(expired, current), now))
        assertEquals(listOf(recent), AppLogStore.decode(AppLogStore.encode(listOf(recent))))
    }

    @Test
    fun `endpoint health codec round trips sanitized state and ignores blank key`() {
        val decoded = EndpointHealthStore.decode(
            """[{"code":"","score":20},{"code":"lv","score":130,"failureCount":-2}]""",
        )

        assertEquals(100, decoded.getValue("lv").score)
        assertEquals(0, decoded.getValue("lv").failureCount)
        assertEquals(decoded, EndpointHealthStore.decode(EndpointHealthStore.encode(decoded)))
    }

    @Test
    fun `temporary vpn lease codec round trips credentials without normal settings`() {
        val expected = TemporaryVpnLease(
            sessionId = "5d330ebf-6204-4773-99c2-f219f808b056",
            controlToken = "opaque-control-token",
            expiresAtEpochMillis = 1_784_000_000_000L,
            trafficLimitBytes = 100L * 1024L * 1024L,
            locationCode = "lv",
            locationName = "Латвия",
            profile = VlessProfile(
                remark = "Noki Латвия",
                endpointCode = "auth-temp-lv",
                host = "vpn.example.test",
                port = "443",
                uuid = "b3e85e7a-c160-4dd0-9b26-86f32b9aa5d6",
                security = "reality",
                serverName = "cdn.example.test",
                publicKey = "server-public-key",
                shortId = "0123456789abcdef",
            ),
        )

        assertEquals(expected, TemporaryVpnLeaseCodec.decode(TemporaryVpnLeaseCodec.encode(expected)))
        assertNull(TemporaryVpnLeaseCodec.decode("{not-json"))
    }

    @Test
    fun `temporary vpn lease requires usable profile and remaining lifetime`() {
        val now = 10_000L
        val lease = TemporaryVpnLease(
            sessionId = "session",
            controlToken = "control",
            expiresAtEpochMillis = now + 10_000L,
            trafficLimitBytes = 100L,
            locationCode = "lv",
            locationName = "Латвия",
            profile = defaults.profile.copy(
                host = "vpn.example.test",
                uuid = "b3e85e7a-c160-4dd0-9b26-86f32b9aa5d6",
                serverName = "cdn.example.test",
                publicKey = "server-public-key",
                shortId = "0123456789abcdef",
            ),
        )

        assertTrue(TemporaryVpnLeasePolicy.isUsable(lease, now, minimumRemainingMillis = 5_000L))
        assertFalse(TemporaryVpnLeasePolicy.isUsable(lease, now + 6_000L, minimumRemainingMillis = 5_000L))
        assertFalse(TemporaryVpnLeasePolicy.isUsable(lease.copy(profile = VlessProfile()), now))
        assertFalse(
            TemporaryVpnLeasePolicy.isUsable(
                lease.copy(trafficLimitBytes = 100L * 1024L * 1024L + 1L),
                now,
            ),
        )
        assertFalse(
            TemporaryVpnLeasePolicy.isUsable(
                lease.copy(expiresAtEpochMillis = now + 10L * 60L * 1_000L + 30_001L),
                now,
            ),
        )
    }

    @Test
    fun `temporary vpn pending revoke codec keeps only control data`() {
        val expected = TemporaryVpnPendingRevoke(
            sessionId = "5d330ebf-6204-4773-99c2-f219f808b056",
            controlToken = "opaque-control-token",
            expiresAtEpochMillis = 1_784_000_000_000L,
        )

        assertEquals(
            expected,
            TemporaryVpnPendingRevokeCodec.decode(TemporaryVpnPendingRevokeCodec.encode(expected)),
        )
        assertNull(TemporaryVpnPendingRevokeCodec.decode("{not-json"))
    }

    @Test
    fun `vpn incident queue keeps latest ten reports`() {
        val incidents = (1..12).map { index ->
            VpnIncidentReport(
                id = "incident-$index",
                reason = "readiness_failed",
                countryCode = "LV",
                locationCode = "lv-2",
                recoveryAttempts = 3,
                outcome = "failed",
                occurredAt = "2026-07-27T10:00:00Z",
            )
        }

        val decoded = VpnIncidentStore.decode(VpnIncidentStore.encode(incidents))

        assertEquals(10, decoded.size)
        assertEquals("incident-3", decoded.first().id)
        assertEquals("incident-12", decoded.last().id)
    }
}
