package com.noki.vpn.ui

import com.noki.vpn.AppDialog
import com.noki.vpn.AppDestination
import com.noki.vpn.confirmedServerCode
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.loadInstalledAppsOrEmpty
import com.noki.vpn.navigationStackAfterOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryNavigationPolicyTest {
    @Test
    fun selectionModeIsPartOfIdentityAndOfflineNodeCannotBeConfirmed() {
        val profile = com.noki.vpn.data.UserProfile(selectedCountryCode = "DE")
        assertEquals(true, com.noki.vpn.isCurrentServerSelection(profile, "de", com.noki.vpn.data.ServerSelectionMode.COUNTRY))
        assertEquals(false, com.noki.vpn.isCurrentServerSelection(profile, "", com.noki.vpn.data.ServerSelectionMode.AUTO))
        val node = com.noki.vpn.data.VpnServer(
            id = "node-a", name = "Frankfurt 1", countryCode = "DE", locationCode = "de-1",
            host = "probe.example", probePort = 443, isOnline = false,
        )
        val locations = listOf(ServerLocation(code = "DE", country = "Germany", city = "", host = "", isOnline = true, servers = listOf(node)))
        assertNull(confirmedServerCode(AppDialog.ChangeServer("node-a", com.noki.vpn.data.ServerSelectionMode.SERVER), locations))
        assertEquals("", confirmedServerCode(AppDialog.ChangeServer("", com.noki.vpn.data.ServerSelectionMode.AUTO), locations))
        assertEquals("node-a", confirmedServerCode(
            AppDialog.ChangeServer("node-a", com.noki.vpn.data.ServerSelectionMode.SERVER),
            locations.map { it.copy(servers = listOf(node.copy(isOnline = true))) },
        ))
    }

    @Test
    fun devicesPullRefreshLoadsBothNativeAndIncyDataWithoutProbingLatency() {
        val requests = mutableListOf<String>()

        refreshVisibleScreenData(
            destination = AppDestination.DEVICES,
            refreshAllData = { latency -> requests += "bootstrap:$latency" },
            refreshIncyDevices = { requests += "incy" },
        )

        assertEquals(listOf("bootstrap:false", "incy"), requests)
    }

    @Test
    fun homePullRefreshStillProbesLatencyWithoutLoadingIncy() {
        val requests = mutableListOf<String>()

        refreshVisibleScreenData(
            destination = AppDestination.HOME,
            refreshAllData = { latency -> requests += "bootstrap:$latency" },
            refreshIncyDevices = { requests += "incy" },
        )

        assertEquals(listOf("bootstrap:true"), requests)
    }

    @Test
    fun otherScreensPullRefreshOnlyCommonBackendData() {
        for (destination in listOf(AppDestination.ACCOUNT, AppDestination.SETTINGS, AppDestination.STATS)) {
            val requests = mutableListOf<String>()
            refreshVisibleScreenData(
                destination = destination,
                refreshAllData = { latency -> requests += "bootstrap:$latency" },
                refreshIncyDevices = { requests += "incy" },
            )
            assertEquals(destination.name, listOf("bootstrap:false"), requests)
        }
    }

    @Test
    fun attentionIndicatorsMapNotificationsToAccountAndUpdatesToSettings() {
        assertEquals(
            setOf(1),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = true,
                isAndroidUpdateAvailable = false,
            ),
        )
        assertEquals(
            setOf(2),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = false,
                isAndroidUpdateAvailable = true,
            ),
        )
        assertEquals(
            setOf(1, 2),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = true,
                isAndroidUpdateAvailable = true,
            ),
        )
    }

    @Test
    fun middleTabTargetsAccount() {
        assertEquals(AppDestination.ACCOUNT, PrimaryNavigationPolicy.destinationForTab(1))
    }

    @Test
    fun accountSelectsMiddleTabWhileStatsRemainsUnderSettings() {
        assertEquals(1, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.ACCOUNT))
        assertEquals(2, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.STATS))
        assertEquals(2, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.SETTINGS))
    }

    @Test
    fun plansSelectsMiddleTab() {
        assertEquals(1, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.PLANS))
    }

    @Test
    fun anonymousSupportDoesNotExposeAuthenticatedBottomNavigation() {
        assertFalse(
            PrimaryNavigationPolicy.showsSharedNavigation(
                isAuthenticated = false,
                destination = AppDestination.SUPPORT,
            ),
        )
        assertTrue(
            PrimaryNavigationPolicy.showsSharedNavigation(
                isAuthenticated = true,
                destination = AppDestination.SUPPORT,
            ),
        )
    }

    @Test
    fun topLevelNavigationReplacesTheWholeDetailStack() {
        assertEquals(
            listOf(AppDestination.ACCOUNT),
            navigationStackAfterOpen(
                currentStack = listOf(
                    AppDestination.HOME,
                    AppDestination.SETTINGS,
                    AppDestination.APP_FILTER,
                ),
                destination = AppDestination.ACCOUNT,
                replaceStack = true,
            ),
        )
    }

    @Test
    fun repeatedTopLevelNavigationCannotGrowTheStack() {
        var stack = listOf(AppDestination.HOME)
        repeat(1_000) { index ->
            stack = navigationStackAfterOpen(
                currentStack = stack,
                destination = if (index % 2 == 0) AppDestination.ACCOUNT else AppDestination.SETTINGS,
                replaceStack = true,
            )
        }

        assertEquals(1, stack.size)
        assertEquals(AppDestination.SETTINGS, stack.single())
    }

    @Test
    fun detailNavigationStillPushesOnce() {
        assertEquals(
            listOf(AppDestination.SETTINGS, AppDestination.APP_FILTER),
            navigationStackAfterOpen(
                currentStack = listOf(AppDestination.SETTINGS),
                destination = AppDestination.APP_FILTER,
                replaceStack = false,
            ),
        )
        assertEquals(
            listOf(AppDestination.SETTINGS),
            navigationStackAfterOpen(
                currentStack = listOf(AppDestination.SETTINGS),
                destination = AppDestination.SETTINGS,
                replaceStack = false,
            ),
        )
    }

    @Test
    fun modalAndCheckoutHideBottomNavigation() {
        assertTrue(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.APP_FILTER,
                dialogVisible = false,
                plansCheckoutVisible = false,
            ),
        )
        assertFalse(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.APP_FILTER,
                dialogVisible = true,
                plansCheckoutVisible = false,
            ),
        )
        assertFalse(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.PLANS,
                dialogVisible = false,
                plansCheckoutVisible = true,
            ),
        )
    }

    @Test
    fun staleServerDialogCannotSelectADeletedLocation() {
        val dialog = AppDialog.ChangeServer("pl")
        val availableLocations = listOf(
            ServerLocation(
                code = "lv",
                country = "Latvia",
                city = "Riga",
                host = "lv.example",
                isOnline = true,
            ),
        )

        assertNull(confirmedServerCode(dialog, availableLocations))
    }

    @Test(expected = CancellationException::class)
    fun installedAppsLoadDoesNotSwallowScopeCancellation() {
        runBlocking {
            loadInstalledAppsOrEmpty {
                throw CancellationException("view model cleared")
            }
        }
    }
}
