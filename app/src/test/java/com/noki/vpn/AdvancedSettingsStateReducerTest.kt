package com.noki.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.ui.siteRuleValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AdvancedSettingsStateReducerTest {
    @Test
    fun ruRelayKeepsNormalProtocolAndRequiresExplicitManualMultiHopSwitch() {
        val manual = MultiHopSettings(enabled = true)
        val current = AppUiState(advancedSettings = AdvancedSettings(
            protocol = VpnProtocol.REALITY,
            multiHop = manual,
        ))

        assertEquals(VpnProtocol.REALITY, current.advancedSettings.effectiveProtocol)
        assertEquals(current, AdvancedSettingsStateReducer.setRuRelayEnabled(current, true))
        val enabled = AdvancedSettingsStateReducer.setRuRelayEnabled(current, true, disableManualMultiHop = true)
        assertEquals(VpnProtocol.WIREGUARD, enabled.advancedSettings.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, enabled.advancedSettings.protocol)
        assertEquals(false, enabled.advancedSettings.multiHop.enabled)
        assertEquals(manual.entry, enabled.advancedSettings.multiHop.entry)

        val disabled = AdvancedSettingsStateReducer.setRuRelayEnabled(enabled, false)
        assertEquals(VpnProtocol.REALITY, disabled.advancedSettings.effectiveProtocol)
        assertEquals(false, disabled.advancedSettings.multiHop.enabled)
    }

    @Test
    fun ruRelayAlwaysUsesAutomaticSelectionAndKeepsNormalPreference() {
        val initial = AppUiState(
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.REALITY),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER,
                selectedNodeId = "exit-a"),
        )
        val ru = AdvancedSettingsStateReducer.setRuRelayEnabled(initial, true)
        assertEquals(com.noki.vpn.data.RuRelaySelection.Auto, ru.advancedSettings.ruRelaySelection)
        assertEquals(VpnProtocol.WIREGUARD, ru.advancedSettings.effectiveProtocol)
        assertEquals(initial.userProfile, ru.userProfile)
        assertEquals(VpnProtocol.REALITY, ru.advancedSettings.protocol)
        assertEquals(false, AdvancedSettingsStateReducer.setRuRelayEnabled(ru, false).advancedSettings.ruRelayEnabled)
    }

    @Test
    fun packageSelectionTogglesMembership() {
        val current = AppUiState(selectedPackages = setOf("com.a"))

        val added = AdvancedSettingsStateReducer.togglePackageSelection(current, "com.b")
        val removed = AdvancedSettingsStateReducer.togglePackageSelection(added, "com.a")

        assertEquals(setOf("com.a", "com.b"), added.selectedPackages)
        assertEquals(setOf("com.b"), removed.selectedPackages)
    }

    @Test
    fun packageSelectionCanBeCleared() {
        val current = AppUiState(selectedPackages = setOf("com.a", "com.b"))

        val cleared = AdvancedSettingsStateReducer.clearPackageSelection(current)

        assertEquals(emptySet<String>(), cleared.selectedPackages)
    }

    @Test
    fun domainRulesTrimDeduplicateAndIgnoreBlankValues() {
        val current = AppUiState(
            alwaysOnInput = " pending.example ",
            bypassInput = " bypass.example ",
            advancedSettings = AdvancedSettings(
                alwaysOnDomains = listOf("a.example"),
                bypassDomains = listOf("b.example"),
            ),
        )

        val withAlwaysOn = AdvancedSettingsStateReducer.addAlwaysOnDomain(current, current.alwaysOnInput)
        val withDuplicateAlwaysOn = AdvancedSettingsStateReducer.addAlwaysOnDomain(withAlwaysOn, "a.example")
        val withBypass = AdvancedSettingsStateReducer.addBypassDomain(withDuplicateAlwaysOn, current.bypassInput)
        val blankIgnored = AdvancedSettingsStateReducer.addBypassDomain(withBypass, "   ")

        assertEquals(listOf("domain:a.example", "domain:pending.example"), withAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals("", withAlwaysOn.alwaysOnInput)
        assertEquals(listOf("domain:a.example", "domain:pending.example"), withDuplicateAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(listOf("domain:b.example", "domain:bypass.example"), withBypass.advancedSettings.bypassDomains)
        assertEquals("", withBypass.bypassInput)
        assertSame(withBypass, blankIgnored)
    }

    @Test
    fun updatingDomainRulesTrimsDeduplicatesAndKeepsStateOnBlank() {
        val current = AppUiState(
            advancedSettings = AdvancedSettings(
                alwaysOnDomains = listOf("a.example", "b.example"),
                bypassDomains = listOf("x.example", "y.example"),
            ),
        )

        val updatedAlwaysOn = AdvancedSettingsStateReducer.updateAlwaysOnDomain(current, "b.example", " a.example ")
        val removedAlwaysOn = AdvancedSettingsStateReducer.removeAlwaysOnDomain(updatedAlwaysOn, "a.example")
        val updatedBypass = AdvancedSettingsStateReducer.updateBypassDomain(current, "y.example", " z.example ")
        val removedBypass = AdvancedSettingsStateReducer.removeBypassDomain(updatedBypass, "x.example")
        val blankIgnored = AdvancedSettingsStateReducer.updateBypassDomain(removedBypass, "z.example", " ")

        assertEquals(listOf("domain:a.example"), updatedAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(emptyList<String>(), removedAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(listOf("domain:x.example", "domain:z.example"), updatedBypass.advancedSettings.bypassDomains)
        assertEquals(listOf("domain:z.example"), removedBypass.advancedSettings.bypassDomains)
        assertSame(removedBypass, blankIgnored)
    }

    @Test
    fun domainEditorExplainsInvalidAndDuplicateValues() {
        val domains = listOf("domain:example.com")

        assertEquals("Enter a domain", siteRuleValidationError("", domains, null, AppLanguage.EN))
        assertEquals("Enter a valid domain", siteRuleValidationError("bad host", domains, null, AppLanguage.EN))
        assertEquals(
            "This domain is already in the list",
            siteRuleValidationError("example.com", domains, null, AppLanguage.EN),
        )
        assertNull(siteRuleValidationError("valid.example", domains, null, AppLanguage.EN))
        assertNull(
            siteRuleValidationError(
                "example.com",
                domains,
                "domain:example.com",
                AppLanguage.EN,
            ),
        )
    }

    @Test
    fun loggingTogglesOnlyChangeRequestedFields() {
        val current = AppUiState(
            advancedSettings = AdvancedSettings(
                connectionLogsEnabled = true,
                errorLogsEnabled = false,
                anonymousLogsEnabled = true,
            ),
        )

        val allOn = AdvancedSettingsStateReducer.setAllLogging(current, true)
        val allOnWithoutAutoSend = AdvancedSettingsStateReducer.setAllLogging(
            current.copy(
                advancedSettings = current.advancedSettings.copy(anonymousLogsEnabled = false),
            ),
            true,
        )
        val anonymousOff = AdvancedSettingsStateReducer.setAnonymousLogs(current, false)

        assertEquals(true, allOn.advancedSettings.connectionLogsEnabled)
        assertEquals(true, allOn.advancedSettings.errorLogsEnabled)
        assertEquals(true, allOn.advancedSettings.anonymousLogsEnabled)
        assertEquals(false, allOnWithoutAutoSend.advancedSettings.anonymousLogsEnabled)
        assertEquals(false, anonymousOff.advancedSettings.anonymousLogsEnabled)
    }

    @Test
    fun youtubeDirectDpiToggleOnlyChangesItsAdvancedField() {
        val current = AppUiState(
            advancedSettings = AdvancedSettings(
                connectionLogsEnabled = false,
                alwaysOnDomains = listOf("domain:secure.example"),
            ),
        )

        val next = AdvancedSettingsStateReducer.setYoutubeDirectDpiEnabled(
            current = current,
            enabled = true,
        )

        assertEquals(
            current.copy(
                advancedSettings = current.advancedSettings.copy(
                    youtubeDirectDpiEnabled = true,
                ),
            ),
            next,
        )
    }

    @Test
    fun autoEndpointSelectionSwitchesBetweenAutoAndManualState() {
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "tcp-a", uuid = "runtime-user"),
            advancedSettings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.AUTO),
            endpointOptions = listOf(
                endpointOption("tcp-a", security = "reality", transport = "tcp", mode = null),
                endpointOption("tls-a", security = "tls", transport = "tcp", mode = null),
            ),
        )

        val manual = AdvancedSettingsStateReducer.setAutoEndpointSelection(current, false)
        val auto = AdvancedSettingsStateReducer.setAutoEndpointSelection(manual, true)

        assertEquals(EndpointSelectionMode.MANUAL, manual.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.REALITY, manual.advancedSettings.protocol)
        assertEquals("tcp-a", manual.advancedSettings.manualEndpointCode)
        assertEquals("lv1|reality|tcp||100", manual.advancedSettings.manualEndpointGroupKey)
        assertEquals("", manual.profile.uuid)
        assertEquals(EndpointSelectionMode.AUTO, auto.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.AUTO, auto.advancedSettings.protocol)
        assertEquals("", auto.advancedSettings.manualEndpointCode)
        assertEquals("", auto.advancedSettings.manualEndpointGroupKey)
    }

    @Test
    fun manualEndpointSelectionUpdatesSettingsAndClearsRuntimeCredentials() {
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "old", uuid = "runtime-user"),
            advancedSettings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.AUTO),
        )
        val option = endpointOption("tls-a", security = "tls", transport = "tcp", mode = null)

        val selected = AdvancedSettingsStateReducer.selectManualEndpoint(current, option)

        assertEquals(EndpointSelectionMode.MANUAL, selected.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.TLS, selected.advancedSettings.protocol)
        assertEquals("tls-a", selected.advancedSettings.manualEndpointCode)
        assertEquals("lv1|tls|tcp||100", selected.advancedSettings.manualEndpointGroupKey)
        assertEquals("tls-a", selected.profile.endpointCode)
        assertEquals("", selected.profile.uuid)
    }

    @Test
    fun filterModeAndSecurityTogglesArePureStateUpdates() {
        val current = AppUiState()

        val onlySelected = AdvancedSettingsStateReducer.setFilterMode(current, AppFilterMode.ONLY_SELECTED)
        val biometricOff = AdvancedSettingsStateReducer.setBiometric(current, false)
        val loginAlertsOff = AdvancedSettingsStateReducer.setLoginAlerts(current, false)
        val protectNewDevicesOff = AdvancedSettingsStateReducer.setProtectNewDevices(current, false)

        assertEquals(AppFilterMode.ONLY_SELECTED, onlySelected.filterMode)
        assertEquals(false, biometricOff.securitySettings.biometricEnabled)
        assertEquals(false, loginAlertsOff.securitySettings.loginAlertsEnabled)
        assertEquals(false, protectNewDevicesOff.securitySettings.protectNewDevices)
    }

    private fun endpointOption(
        code: String,
        security: String,
        transport: String,
        mode: String?,
    ): VpnEndpointOption =
        VpnEndpointOption(
            code = code,
            locationCode = "lv1",
            host = "$code.example.com",
            port = 443,
            proxyType = "vless",
            transport = transport,
            transportMode = mode.orEmpty(),
            security = security,
        )
}
