package com.noki.vpn.ui

import com.noki.vpn.AppDestination

internal object PrimaryNavigationPolicy {
    fun isTopLevelDestination(destination: AppDestination): Boolean = when (destination) {
        AppDestination.HOME,
        AppDestination.ACCOUNT,
        AppDestination.SETTINGS,
        -> true
        else -> false
    }

    fun showsSharedNavigation(
        isAuthenticated: Boolean,
        destination: AppDestination,
    ): Boolean = isAuthenticated && when (destination) {
        AppDestination.HOME,
        AppDestination.ACCOUNT,
        AppDestination.STATS,
        AppDestination.SETTINGS,
        AppDestination.PLANS,
        AppDestination.ADVANCED_SETTINGS,
        AppDestination.MULTIHOP,
        AppDestination.APP_FILTER,
        AppDestination.SITE_RULES_ALWAYS_ON,
        AppDestination.SITE_RULES_BYPASS,
        AppDestination.SECURITY,
        AppDestination.SUPPORT,
        AppDestination.PERSONALIZATION,
        AppDestination.DEVICES -> true
        else -> false
    }

    fun showsBottomNavigation(
        isAuthenticated: Boolean,
        destination: AppDestination,
        dialogVisible: Boolean,
        plansCheckoutVisible: Boolean,
    ): Boolean = showsSharedNavigation(isAuthenticated, destination) &&
        !dialogVisible &&
        !(destination == AppDestination.PLANS && plansCheckoutVisible)

    fun destinationForTab(index: Int): AppDestination = when (index.coerceIn(0, 2)) {
        0 -> AppDestination.HOME
        1 -> AppDestination.ACCOUNT
        else -> AppDestination.SETTINGS
    }

    fun selectedTabIndex(destination: AppDestination): Int = when (destination) {
        AppDestination.HOME -> 0
        AppDestination.ACCOUNT,
        AppDestination.PLANS -> 1
        else -> 2
    }

    fun attentionIndicatorTabs(
        hasUnreadAppNotifications: Boolean,
        isAndroidUpdateAvailable: Boolean,
    ): Set<Int> = buildSet {
        if (hasUnreadAppNotifications) add(1)
        if (isAndroidUpdateAvailable) add(2)
    }
}
