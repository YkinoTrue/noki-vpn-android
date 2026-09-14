package com.noki.vpn.data

import com.noki.vpn.AppUiState
import com.noki.vpn.SettingsPreparedStatePolicy
import com.noki.vpn.ui.AccountPresentationPolicy
import com.noki.vpn.ui.AccountPlanTitleStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapStateMapperTest {
    @Test
    fun `account device custom name is title and model stays in subtitle`() {
        val device = BackendDevice(
            id = "device-1",
            deviceKey = "stable-key",
            deviceName = "Samsung SM-G996B",
            customName = "Рабочий телефон",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )

        val row = BootstrapStateMapper.mapDevices(
            devices = listOf(device),
            language = AppLanguage.RU,
            currentDeviceId = "device-1",
            currentDeviceKey = "stable-key",
        ).single()

        assertEquals("Рабочий телефон", row.title)
        assertTrue(row.subtitle.startsWith("Samsung Galaxy S21+ 5G • "))
        assertEquals("Samsung Galaxy S21+ 5G", AndroidDeviceInfo.displayName("samsung SM-G996B"))
        assertEquals("Unknown MODEL-123", AndroidDeviceInfo.displayName("Unknown MODEL-123"))
        val unnamed = BootstrapStateMapper.mapDevices(
            listOf(device.copy(customName = null)), AppLanguage.EN, "device-1", "stable-key",
        ).single()
        assertEquals("Samsung Galaxy S21+ 5G", unnamed.title)
    }

    @Test
    fun `country and server latency only use device samples`() {
        val server = VpnServer("node", "Server", "LV", "lv1", "node.example", 443, true, latencyMs = 2)
        val backend = location("lv1").copy(latencyMs = 1, servers = listOf(server))
        val withoutSamples = BootstrapStateMapper.mapLocations(listOf(backend), AppLanguage.RU, emptyMap()).single()
        assertEquals(null, withoutSamples.latencyMs)
        assertEquals(null, withoutSamples.servers.single().latencyMs)
        val measured = BootstrapStateMapper.mapLocations(
            listOf(backend), AppLanguage.RU, mapOf(clientLatencyTargetKey(server)!! to 87),
        ).single()
        assertEquals(87, measured.latencyMs)
        assertEquals(87, measured.servers.single().latencyMs)
        assertEquals(null, measured.withClientLatencies(emptyMap()).latencyMs)
    }

    @Test
    fun `traffic labels use binary terabytes and keep smaller limits precise`() {
        for ((gb, ru, en) in listOf(
            Triple(0.5, "0,5 ГБ", "0.5 GB"),
            Triple(100.0, "100 ГБ", "100 GB"),
            Triple(1023.0, "1023 ГБ", "1023 GB"),
            Triple(1024.0, "1 ТБ", "1 TB"),
            Triple(2048.0, "2 ТБ", "2 TB"),
            Triple(4096.0, "4 ТБ", "4 TB"),
        )) {
            assertEquals(ru, TrafficFormat.gigabytes(gb, AppLanguage.RU).label)
            assertEquals(en, TrafficFormat.gigabytes(gb, AppLanguage.EN).label)
            val plan = BackendPlan(
                code = "plus-monthly", name = "Plus", tier = "plus", billingPeriodMonths = 1,
                priceRub = 300, monthlyEquivalentRub = 300, deviceLimit = 3,
                trafficLimitGb = gb, speedProfile = "100mbps", features = emptyList(),
                headline = null, badgeColor = null, isActive = true, sortOrder = 0,
            )
            assertEquals(ru, BootstrapStateMapper.mapPlans(listOf(plan), AppLanguage.RU).single().trafficLabel)
        }
        assertEquals("-- ГБ", TrafficFormat.gigabytes(Double.NaN, AppLanguage.RU).label)
        assertEquals("100 МБ", TrafficFormat.bytes(100L * 1024 * 1024, AppLanguage.RU).label)
        assertEquals("2 TB", TrafficFormat.bytes(2L * 1024 * 1024 * 1024 * 1024, AppLanguage.EN).label)
    }

    @Test
    fun `startup does not offer built in tariffs before the server catalog arrives`() {
        assertTrue(BootstrapStateMapper.initialPlans().isEmpty())
        assertTrue(BootstrapStateMapper.mapPlans(emptyList(), AppLanguage.RU).isEmpty())
        assertTrue(BootstrapStateMapper.mapPlans(emptyList(), AppLanguage.EN).isEmpty())
    }

    @Test
    fun `hidden assigned plan retains its identity without entering the purchase catalog`() {
        val visiblePlans = listOf(BackendPlan(
            code = "free", name = "Free", tier = "free", billingPeriodMonths = 1,
            priceRub = 0, monthlyEquivalentRub = 0, deviceLimit = 1,
            trafficLimitGb = 0.5, speedProfile = "unlimited", features = emptyList(),
            headline = null, badgeColor = "#FF0000", isActive = true, sortOrder = 0,
        ))
        val mapped = mapSubscription(visiblePlans)
        val defaults = DefaultStoredSettingsFactory.create(java.util.Locale.US)
        val codec = StoredSettingsCodec { defaults }
        val restored = codec.decode(codec.encode(
            defaults.copy(userProfile = mapped.userProfile),
        ))
        val state = AppUiState(
            userProfile = restored.userProfile, plans = mapped.plans,
            personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
        )
        val settings = SettingsPreparedStatePolicy.prepare(state)
        val account = AccountPresentationPolicy.prepare(state)

        assertEquals("Partner Special", settings.planTitle)
        assertEquals("Partner Special", account.planTitle)
        assertEquals(0xFF12AABBL, account.planColorArgb)
        assertEquals("О тарифе", settings.planActionLabel)
        assertFalse(account.isFree)
        assertEquals(AccountPlanTitleStyle.BackendMetallic, account.planTitleStyle)
        assertEquals(listOf("free"), PlanCatalogPolicy.visiblePlans(mapped.plans, BillingCycle.MONTHLY).map { it.code })
    }

    @Test
    fun `empty purchase catalog remains empty when all tariffs are hidden`() {
        val mapped = mapSubscription(emptyList())
        assertTrue(mapped.plans.isEmpty())
        assertEquals("Partner Special", AccountPresentationPolicy.prepare(
            AppUiState(userProfile = mapped.userProfile, plans = mapped.plans),
        ).planTitle)
    }

    private fun mapSubscription(plans: List<BackendPlan>): BootstrapStateMapper.Result =
        BootstrapStateMapper.mapBootstrap(
            bootstrap = BootstrapPayload(
                user = BackendUser("user-1", "tester", "tester@example.com", null, true, false),
                subscription = JSONObject("""{
                    "status":"active", "plan_code":"partner-yearly",
                    "plan_name":"Partner Special", "plan_tier":"partner",
                    "plan_badge_color":"#12AABB", "traffic_used_gb":25,
                    "traffic_limit_gb":800, "expires_at":"2027-08-30T00:00:00Z"
                }""").toBackendSubscription(),
                plans = plans, locations = emptyList(), devices = emptyList(), paymentsReady = false,
            ),
            language = AppLanguage.RU, currentUserProfile = UserProfile(),
            currentProfile = VlessProfile(), advancedSettings = AdvancedSettings(),
            endpointOptions = emptyList(), currentDeviceId = "device-1", currentDeviceKey = "key-1",
            previousDeviceAccessRole = "owner", clientLatencyByTarget = emptyMap(),
        )

    @Test
    fun `country names use admin translation independently of current server load`() {
        val named = location("us-1", "US", 80).copy(nameRu = " США ", nameEn = " USA ")
        val other = location("us-2", "US", 10).copy(nameRu = "Другое имя", nameEn = "Other name")
        for ((language, expected) in listOf(AppLanguage.RU to "США", AppLanguage.EN to "USA")) {
            for (catalog in listOf(listOf(named, other), listOf(named.copy(isOnline = false), other))) {
                val row = BootstrapStateMapper.mapLocations(catalog, language, emptyMap()).single()
                assertEquals(expected, row.country)
                assertEquals("US", row.countryCode)
                assertEquals("us-2.example.com", row.host)
            }
        }
        val blank = named.copy(nameRu = " ", nameEn = null)
        assertEquals("Другое имя", BootstrapStateMapper.mapLocations(listOf(blank, other), AppLanguage.RU, emptyMap()).single().country)
        assertEquals("United States", BootstrapStateMapper.mapLocations(listOf(blank), AppLanguage.EN, emptyMap()).single().country)
    }

    @Test
    fun `locations with the same country code become one country row`() {
        val rows = BootstrapStateMapper.mapLocations(
            locations = listOf(
                location(code = "lv-1", loadPercent = 80),
                location(code = "lv-2", loadPercent = 20),
            ),
            language = AppLanguage.EN,
            clientLatencyByTarget = mapOf(
                requireNotNull(clientLatencyTargetKey("LV", "lv-2.example.com")) to 40,
            ),
        )

        assertEquals(listOf("LV"), rows.map { it.code })
        assertEquals(20, rows.single().loadPercent)
        assertEquals(40, rows.single().latencyMs)
    }

    @Test
    fun `legacy concrete server selection migrates to its country`() {
        val backendLocations = listOf(
            location(code = "lv-1", countryCode = "LV"),
            location(code = "de-1", countryCode = "DE"),
        )
        val countryRows = BootstrapStateMapper.mapLocations(
            locations = backendLocations,
            language = AppLanguage.EN,
            clientLatencyByTarget = emptyMap(),
        )

        assertEquals(
            "LV",
            BootstrapStateMapper.selectedCountryCode(
                locations = countryRows,
                backendLocations = backendLocations,
                currentCountryCode = "",
                legacyServerCode = "lv-1",
            ),
        )
    }

    private fun location(
        code: String,
        countryCode: String = "LV",
        loadPercent: Int? = null,
    ) = BackendLocation(
        id = code,
        code = code,
        name = if (countryCode == "LV") "Latvia" else "Germany",
        nameRu = if (countryCode == "LV") "Латвия" else "Германия",
        nameEn = if (countryCode == "LV") "Latvia" else "Germany",
        entryHost = "$code.example.com",
        countryCode = countryCode,
        isOnline = true,
        capacityMbps = 1_000,
        downloadMbps = null,
        uploadMbps = null,
        latencyMs = null,
        loadPercent = loadPercent,
    )
}
