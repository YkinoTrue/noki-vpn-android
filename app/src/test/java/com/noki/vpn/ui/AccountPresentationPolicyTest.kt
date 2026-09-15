package com.noki.vpn.ui

import androidx.compose.ui.graphics.Color
import com.noki.vpn.AppUiState
import com.noki.vpn.SettingsPreparedStatePolicy
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BillingCycle
import com.noki.vpn.data.DailyStats
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.PlanCatalogPolicy
import com.noki.vpn.data.PlanCode
import com.noki.vpn.data.PlanSummary
import com.noki.vpn.data.UserProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPresentationPolicyTest {
    @Test
    fun renewalRequiresActivePaidTierRegardlessOfBillingCycle() {
        val monthly = plan("plus-monthly", "plus", "Plus", null).copy(monthlyPriceRub = 250)
        val yearly = monthly.copy(code = "plus-yearly", monthlyPriceRub = 2500)
        val active = AppUiState(userProfile = UserProfile(
            selectedPlanCodeRaw = monthly.code,
            subscriptionStatus = "active",
            subscriptionExpiresAt = java.time.Instant.now().plusSeconds(86400).toString(),
        ))
        assertTrue(canRenewPlan(monthly, active))
        assertTrue(canRenewPlan(yearly, active))
        assertTrue(canRenewPlan(monthly, active.copy(userProfile = active.userProfile.copy(selectedPlanCodeRaw = yearly.code))))
        assertFalse(canRenewPlan(monthly.copy(code = "pro-monthly", tier = "pro"), active))
        assertFalse(canRenewPlan(monthly, active.copy(userProfile = active.userProfile.copy(subscriptionStatus = "expired"))))
        assertFalse(canRenewPlan(monthly, active.copy(userProfile = active.userProfile.copy(subscriptionExpiresAt = "2020-01-01T00:00:00Z"))))
        assertFalse(canRenewPlan(monthly.copy(monthlyPriceRub = 0, tier = "free", code = "free"), active))
    }

    private val today = LocalDate.of(2026, 7, 12)

    @Test
    fun terabyteLimitKeepsUsedGigabytesReadable() {
        val result = AccountPresentationPolicy.prepare(AppUiState(
            userProfile = UserProfile(
                selectedPlanCode = PlanCode.PLUS, selectedPlanCodeRaw = "plus-monthly",
                trafficUsedGb = 500.0, trafficLimitGb = 2048.0,
            ),
            personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
        ), today)
        assertEquals("500 ГБ / 2 ТБ", result.usageLabel)
        assertEquals("Израсходовано: 500 ГБ из 2 ТБ", result.usedExplanation)
        assertEquals(76, result.remainingPercent)
    }

    @Test
    fun invitedIdentityHidesOwnerPhotoAndEmailAcrossProfileSurfaces() {
        val state = AppUiState(
            currentDeviceAccessRole = "InViTeD",
            userProfile = UserProfile(
                username = "owner",
                email = "owner@example.com",
                avatarUri = "https://example.com/owner.jpg",
            ),
        )

        val settings = SettingsPreparedStatePolicy.prepare(state)
        val account = AccountPresentationPolicy.prepare(state, today)

        assertEquals("ow****@example.com", settings.email)
        assertEquals(settings.email, account.email)
        assertNull(settings.avatarUri)
        assertNull(account.avatarUri)
        assertTrue(account.isInvitedDevice)
    }

    @Test
    fun invitedEmailMaskKeepsTwoCharactersAndTheDomain() {
        for ((email, masked) in listOf(
            "a@example.com" to "a****@example.com",
            "ab@example.com" to "ab****@example.com",
            "longname@example.com" to "lo****@example.com",
            "unavailable" to "un****",
        )) {
            val state = AppUiState(
                currentDeviceAccessRole = "invited",
                userProfile = UserProfile(email = email),
            )
            assertEquals(masked, SettingsPreparedStatePolicy.prepare(state).email)
        }
    }

    @Test
    fun ownerIdentityIsUnchangedAndRoleSwitchClearsPreparedPhoto() {
        val owner = AppUiState(userProfile = UserProfile(
            email = "owner@example.com", avatarUri = "content://owner-avatar",
        ))
        val preparedOwner = SettingsPreparedStatePolicy.withPreparedSettingsState(owner)
        assertEquals("owner@example.com", preparedOwner.settingsPreparedState.email)
        assertEquals("content://owner-avatar", preparedOwner.settingsPreparedState.avatarUri)

        val invited = SettingsPreparedStatePolicy.withPreparedSettingsState(
            preparedOwner.copy(currentDeviceAccessRole = "invited"),
        )
        assertEquals("ow****@example.com", invited.settingsPreparedState.email)
        assertNull(invited.settingsPreparedState.avatarUri)
    }

    @Test
    fun invitedEmptyEmailKeepsTheLocalizedPlaceholder() {
        val state = AppUiState(
            currentDeviceAccessRole = "invited",
            userProfile = UserProfile(email = ""),
            personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
        )
        assertEquals("почта не указана", SettingsPreparedStatePolicy.prepare(state).email)
    }

    @Test
    fun lockedAccountRowShowsAccessDeniedWithoutRunningTheAction() {
        val events = mutableListOf<String>()
        val row = AccountRowSpec("Restricted", 0, 0, 0, enabled = false) {
            events += "action"
        }
        row.performClick { events += "denied" }
        assertEquals(listOf("denied"), events)
    }

    @Test
    fun availableAccountRowStillRunsItsAction() {
        val events = mutableListOf<String>()
        val row = AccountRowSpec("Support", 0, 0, 0) { events += "action" }
        row.performClick { events += "denied" }
        assertEquals(listOf("action"), events)
    }

    @Test
    fun hiddenUnlimitedPlanDoesNotBorrowLimitFromAnOfferWithTheSameTitle() {
        val state = state(
            planCode = PlanCode.PREMIUM, rawCode = "premium-private",
            usedGb = 500.0, limitGb = null,
            plan = plan(code = "premium-monthly", tier = "premium", title = "Premium", color = null, limitGb = 800.0),
        )
        val result = AccountPresentationPolicy.prepare(state.copy(userProfile = state.userProfile.copy(
            selectedPlanName = "Premium", selectedPlanTier = "premium",
        )), today)

        assertEquals("500 / ∞ ГБ", result.usageLabel)
        assertEquals(100, result.remainingPercent)
    }

    @Test
    fun paidPlanShimmerKeepsBackendBaseWithBrightMetallicShoulders() {
        val backendColor = Color(0xFF12AABB)

        val colors = accountPlanTitleShimmerColors(backendColor, AccountPlanTitleStyle.BackendMetallic)

        assertEquals(5, colors.size)
        assertEquals(backendColor, colors[0])
        assertEquals(backendColor, colors[4])
        assertTrue(colors[1].red > backendColor.red)
        assertTrue(colors[1].red < colors[3].red)
        assertTrue(colors[3].red < colors[2].red)
        assertTrue(colors[2].red > 0.9f)
        assertTrue(colors[2].green > 0.9f)
        assertTrue(colors[2].blue > 0.9f)
        assertEquals(backendColor.alpha, colors[2].alpha, 0.0f)
    }

    @Test
    fun hiddenPremiumUsesOriginalSilverPaletteWithoutChangingBackendProfileColor() {
        val state = AppUiState(userProfile = UserProfile(
            selectedPlanCode = PlanCode.FREE,
            selectedPlanCodeRaw = "private-member",
            selectedPlanTier = " PrEmIuM ",
            selectedPlanName = "Private membership",
            selectedPlanBadgeColor = "#12AABB",
        ))

        val result = AccountPresentationPolicy.prepare(state, today)
        val colors = accountPlanTitleShimmerColors(Color(result.planColorArgb), result.planTitleStyle)

        assertEquals("Private membership", result.planTitle)
        assertFalse(result.isFree)
        assertEquals(AccountPlanTitleStyle.PremiumMetallic, result.planTitleStyle)
        assertEquals(0xFF12AABBL, result.planColorArgb)
        assertEquals(listOf(
            Color(0xFF9DA9B4), Color(0xFFAEB9C3), Color(0xFFF7F9FA),
            Color(0xFFC5CDD4), Color(0xFF9DA9B4),
        ), colors)
    }

    @Test
    fun premiumTitleAndLegacyEnumDoNotOverrideAssignedNonPremiumTier() {
        val result = AccountPresentationPolicy.prepare(AppUiState(userProfile = UserProfile(
            selectedPlanCode = PlanCode.PREMIUM,
            selectedPlanCodeRaw = "premium-yearly",
            selectedPlanTier = "pro",
            selectedPlanName = "Premium",
            selectedPlanBadgeColor = "#12AABB",
        )), today)

        assertEquals(AccountPlanTitleStyle.BackendMetallic, result.planTitleStyle)
        val colors = accountPlanTitleShimmerColors(Color(result.planColorArgb), result.planTitleStyle)
        assertEquals(Color(0xFF12AABB), colors.first())
        assertEquals(Color(0xFF12AABB), colors.last())
    }

    @Test
    fun legacyPremiumUsesExactCatalogTierOrKnownBillingSuffix() {
        val catalogState = state(
            planCode = PlanCode.FREE, rawCode = "private-member", usedGb = 0.0, limitGb = null,
            plan = plan("private-member", "premium", "Private", "#12AABB"),
        )
        assertEquals(AccountPlanTitleStyle.PremiumMetallic,
            AccountPresentationPolicy.prepare(catalogState, today).planTitleStyle)

        for (rawCode in listOf("premium", " PREMIUM-monthly ", "premium_yearly")) {
            val result = AccountPresentationPolicy.prepare(AppUiState(userProfile = UserProfile(
                selectedPlanCode = PlanCode.FREE,
                selectedPlanCodeRaw = rawCode,
            )), today)
            assertEquals(rawCode, AccountPlanTitleStyle.PremiumMetallic, result.planTitleStyle)
        }

        val custom = AccountPresentationPolicy.prepare(AppUiState(userProfile = UserProfile(
            selectedPlanCode = PlanCode.PREMIUM,
            selectedPlanCodeRaw = "premium-partner",
        )), today)
        assertEquals(AccountPlanTitleStyle.BackendMetallic, custom.planTitleStyle)
    }

    @Test
    fun assignedFreeTierRemainsStaticEvenWithPremiumCodeAndTitle() {
        val result = AccountPresentationPolicy.prepare(AppUiState(userProfile = UserProfile(
            selectedPlanCode = PlanCode.PREMIUM,
            selectedPlanCodeRaw = "premium",
            selectedPlanTier = "free",
            selectedPlanName = "Premium",
        )), today)

        assertTrue(result.isFree)
        assertEquals(AccountPlanTitleStyle.BackendColor, result.planTitleStyle)
    }

    @Test
    fun paidPlanUsesBackendTitleColorAndClampedRemainingTraffic() {
        val state = state(
            planCode = PlanCode.PLUS,
            rawCode = "plus_yearly",
            usedGb = 25.4,
            limitGb = 100.0,
            plan = plan(code = "plus_yearly", tier = "plus", title = "Plus", color = "#12AABB"),
        )

        val result = AccountPresentationPolicy.prepare(state, today)

        assertFalse(result.isFree)
        assertEquals("Plus", result.planTitle)
        assertEquals(0xFF12AABBL, result.planColorArgb)
        assertEquals(AccountPlanTitleStyle.BackendMetallic, result.planTitleStyle)
        assertEquals(75, result.remainingPercent)
        assertEquals(0.746f, result.remainingFraction, 0.0001f)
        assertEquals("25,4 / 100 ГБ", result.usageLabel)
        assertEquals("Осталось: 75% трафика", result.remainingExplanation)
        assertEquals("Израсходовано: 25,4 ГБ из 100 ГБ", result.usedExplanation)
    }

    @Test
    fun freePlanUsesBackendColorAndFreeSettingsUpgradeVariant() {
        val state = state(
            planCode = PlanCode.FREE,
            rawCode = "free",
            usedGb = 0.3,
            limitGb = 0.5,
            plan = plan(code = "free", tier = "free", title = "FREE", color = "#44CC88"),
        )

        val result = AccountPresentationPolicy.prepare(state, today)

        assertTrue(result.isFree)
        assertEquals("FREE", result.planTitle)
        assertEquals(0xFF44CC88L, result.planColorArgb)
        assertEquals(AccountPlanTitleStyle.BackendColor, result.planTitleStyle)
        assertEquals("--", result.expirationDateLabel)
        val settings = result.settingsTraffic as SettingsTrafficPresentation.FreeUpgrade
        assertEquals("0,3 / 0,5 ГБ", settings.usageLabel)
        assertEquals("Перейти на Plus", settings.actionLabel)
    }

    @Test
    fun paidSettingsAggregatesTodayAndRollingThirtyDays() {
        val state = state(
            planCode = PlanCode.PRO,
            rawCode = "pro_monthly",
            usedGb = null,
            limitGb = null,
            plan = plan(code = "pro_monthly", tier = "pro", title = "Pro", color = null),
            dailyStats = listOf(
                DailyStats(date = "2026-07-12", rxBytes = 1_000_000_000L, txBytes = 670_000_000L),
                DailyStats(date = "2026-06-20", rxBytes = 2_000_000_000L),
                DailyStats(date = "2026-06-12", rxBytes = 99_000_000_000L),
                DailyStats(date = "invalid", rxBytes = 88_000_000_000L),
            ),
        )

        val settings = AccountPresentationPolicy.prepare(state, today).settingsTraffic as
            SettingsTrafficPresentation.PaidStats

        assertEquals("1,56 ГБ", settings.todayLabel)
        assertEquals("3,42 ГБ", settings.lastThirtyDaysLabel)
        assertEquals("Статистика использования", settings.title)
    }

    @Test
    fun missingOrUnlimitedTrafficUsesSafePlaceholders() {
        val state = state(
            planCode = PlanCode.PREMIUM,
            rawCode = "premium",
            usedGb = null,
            limitGb = null,
            plan = plan(code = "premium", tier = "premium", title = "Premium", color = null, limitGb = null),
        ).copy(personalizationSettings = PersonalizationSettings(language = AppLanguage.EN))

        val result = AccountPresentationPolicy.prepare(state, today)

        assertEquals(AccountPlanTitleStyle.PremiumMetallic, result.planTitleStyle)
        assertEquals(100, result.remainingPercent)
        assertEquals("-- / ∞ GB", result.usageLabel)
        assertEquals("Used: -- GB of ∞ GB", result.usedExplanation)
        val settings = result.settingsTraffic as SettingsTrafficPresentation.PaidStats
        assertEquals("0 GB", settings.todayLabel)
        assertEquals("0 GB", settings.lastThirtyDaysLabel)
    }

    @Test
    fun invitedRoleIsExposedToAccountPresentation() {
        val invited = state(
            planCode = PlanCode.FREE,
            rawCode = "free",
            usedGb = 0.0,
            limitGb = 0.5,
            plan = plan(code = "free", tier = "free", title = "FREE", color = null),
        ).copy(currentDeviceAccessRole = "InViTeD")

        val result = AccountPresentationPolicy.prepare(invited, today)

        assertTrue(result.isInvitedDevice)
    }

    @Test
    fun currentPlanIgnoresMonthlyAndYearlyCodeSuffixes() {
        val state = state(
            planCode = PlanCode.PREMIUM,
            rawCode = "premium-yearly",
            usedGb = 0.0,
            limitGb = null,
            plan = plan(
                code = "premium-monthly",
                tier = "premium",
                title = "Premium",
                color = null,
            ),
        )

        assertTrue(isCurrentPlan(state.plans.single(), state))
        assertFalse(
            isCurrentPlan(
                plan(code = "pro-monthly", tier = "pro", title = "Pro", color = null),
                state,
            ),
        )
    }

    @Test
    fun checkoutCycleUsesMatchingTariffAndShowsTotalPrice() {
        val monthly = plan(
            code = "premium-monthly",
            tier = "premium",
            title = "Premium",
            color = null,
        ).copy(monthlyPriceRub = 500)
        val yearly = monthly.copy(
            code = "premium-yearly",
            monthlyPriceRub = 5_100,
            yearlyMonthlyPriceRub = 425,
        )

        assertEquals(
            yearly,
            checkoutPlanForCycle(
                plans = listOf(monthly, yearly),
                selectedCode = monthly.code,
                cycle = BillingCycle.YEARLY,
            ),
        )
        assertEquals("500 ₽", PlanCatalogPolicy.checkoutTotalLabel(monthly, BillingCycle.MONTHLY, AppLanguage.RU))
        assertEquals("5100 ₽", PlanCatalogPolicy.checkoutTotalLabel(yearly, BillingCycle.YEARLY, AppLanguage.RU))
        assertEquals(
            PlanCatalogPolicy.PriceLabel("500 ₽ / месяц", null),
            PlanCatalogPolicy.priceLabel(monthly, BillingCycle.MONTHLY, AppLanguage.RU),
        )
        assertEquals(
            PlanCatalogPolicy.PriceLabel("5100 ₽ / год", "-15%"),
            PlanCatalogPolicy.priceLabel(yearly, BillingCycle.YEARLY, AppLanguage.RU),
        )
        assertEquals(
            PlanCatalogPolicy.PriceLabel("5100 rub / year", "-15%"),
            PlanCatalogPolicy.priceLabel(yearly, BillingCycle.YEARLY, AppLanguage.EN),
        )
        assertEquals(null, PlanCatalogPolicy.priceLabel(
            yearly.copy(monthlyPriceRub = 0), BillingCycle.YEARLY, AppLanguage.RU,
        ).discount)
    }

    private fun state(
        planCode: PlanCode,
        rawCode: String,
        usedGb: Double?,
        limitGb: Double?,
        plan: PlanSummary,
        dailyStats: List<DailyStats> = emptyList(),
    ): AppUiState = AppUiState(
        userProfile = UserProfile(
            username = "ykino",
            selectedPlanCode = planCode,
            selectedPlanCodeRaw = rawCode,
            trafficUsedGb = usedGb,
            trafficLimitGb = limitGb,
            subscriptionExpiresAt = "2026-08-09T00:00:00Z",
        ),
        personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
        plans = listOf(plan),
        dailyStats = dailyStats,
    )

    private fun plan(
        code: String,
        tier: String,
        title: String,
        color: String?,
        limitGb: Double? = 100.0,
    ): PlanSummary = PlanSummary(
        code = code,
        tier = tier,
        title = title,
        devices = 5,
        trafficLimitGb = limitGb,
        trafficLabel = limitGb?.toString() ?: "∞",
        monthlyPriceRub = 0,
        yearlyMonthlyPriceRub = null,
        badgeColor = color,
        features = emptyList(),
    )
}
