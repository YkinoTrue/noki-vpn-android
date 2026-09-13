package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.PaymentCheckoutResult
import com.noki.vpn.data.PlanCatalogPolicy
import com.noki.vpn.data.PlanCode

private val PlansNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun PlansScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    sharedBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
    onCheckoutVisibilityChanged: (Boolean) -> Unit,
) {
    val language = state.personalizationSettings.language
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.preparePaymentCheckout() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPaymentStatus()
        onPauseOrDispose { viewModel.stopPaymentStatusChecks() }
    }
    LaunchedEffect(state.paymentCheckout.launchUrl) {
        val url = state.paymentCheckout.launchUrl ?: return@LaunchedEffect
        viewModel.consumePaymentUrl()
        try { uriHandler.openUri(url) } catch (_: Exception) { viewModel.paymentBrowserFailed() }
    }
    val cycle = state.billingCycle
    val plans = remember(state.plans, cycle) {
        PlanCatalogPolicy.visiblePlans(state.plans, cycle)
    }
    val initialPlanIndex = plans.indexOfFirst {
        knownPlanCodeFromBackend(it.code) == PlanCode.PLUS || knownPlanCodeFromBackend(it.tier) == PlanCode.PLUS
    }.takeIf { it >= 0 } ?: if (plans.size > 1) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPlanIndex, pageCount = { plans.size })
    var initialPlanSelected by remember { mutableStateOf(false) }
    var checkoutPlanCode by rememberSaveable { mutableStateOf<String?>(null) }
    var checkoutVisible by rememberSaveable { mutableStateOf(false) }
    val checkoutPlan = remember(state.plans, checkoutPlanCode, cycle) {
        checkoutPlanCode?.let { checkoutPlanForCycle(state.plans, it, cycle) }
    }
    val currentPlan = remember(state.plans, state.userProfile.selectedPlanCodeRaw) {
        val rawCode = state.userProfile.selectedPlanCodeRaw
        state.plans.firstOrNull { it.code.equals(rawCode, ignoreCase = true) }
            ?: state.plans.firstOrNull { isCurrentPlan(it, state) }
    }
    val currentPlanTitle = currentPlan?.title
        ?: state.userProfile.selectedPlanCodeRaw.ifBlank { state.userProfile.selectedPlanCode.code }

    BackHandler(enabled = checkoutVisible) {
        if (!state.paymentCheckout.isSubmitting) checkoutVisible = false
    }
    LaunchedEffect(checkoutVisible) {
        onCheckoutVisibilityChanged(checkoutVisible)
    }

    LaunchedEffect(plans) {
        if (plans.isNotEmpty() && !initialPlanSelected) {
            pagerState.scrollToPage(initialPlanIndex)
            initialPlanSelected = true
        } else if (plans.isNotEmpty() && pagerState.currentPage > plans.lastIndex) {
            pagerState.scrollToPage(plans.lastIndex)
        }
    }

    CompositionLocalProvider(LocalTextStyle provides PlansNoFontPaddingTextStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(SettingsBgBase) else Modifier)
                .statusBarsPadding(),
        ) {
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val scale = metrics.contentScale
            val contentWidth = metrics.contentWidth
            val cardWidth = contentWidth
            val cardHeight = settingsDp(454f, scale)
            val indicatorGap = settingsDp(15f, scale)
            val indicatorHeight = settingsDp(10f, scale)
            val containerGap = settingsDp(40f, scale)
            val density = LocalDensity.current
            val navigationBottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
            val bottomNavigationReserved = navigationBottomInset + metrics.dp(60f) + metrics.dp(20f)
            val centeredContentHeight = if (plans.isEmpty()) {
                settingsDp(260f, scale)
            } else {
                cardHeight + indicatorGap + indicatorHeight
            }
            val topPadding = PlansLayoutPolicy.centeredCardTopPaddingDp(
                viewportHeightDp = maxHeight.value,
                cardHeightDp = centeredContentHeight.value,
                bottomReservedDp = bottomNavigationReserved.value,
                minimumTopDp = 15f * scale,
            ).dp
            val pagePadding = 0.dp
            val localBackdrop = if (showBackground) {
                rememberLayerBackdrop {
                    drawRect(SettingsBgBase)
                    drawContent()
                }
            } else {
                null
            }
            val cardBackdrop = sharedBackdrop ?: localBackdrop

            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showBackground) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (localBackdrop != null) Modifier.layerBackdrop(localBackdrop) else Modifier),
                    ) {
                        HomeBackground(liveGlassEnabled = liveGlassEnabled)
                    }
                }

                if (checkoutVisible && checkoutPlan != null) {
                    Box(Modifier.fillMaxSize()) {
                        PlanCheckoutScreen(
                            plan = checkoutPlan,
                            currentPlanTitle = currentPlanTitle,
                            renewsCurrentPlan = canRenewPlan(checkoutPlan, state),
                            cycle = cycle,
                            language = language,
                            onCycleChanged = viewModel::setBillingCycle,
                            backdrop = cardBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            scale = scale,
                            checkout = state.paymentCheckout,
                            onMethodChanged = viewModel::selectPaymentMethod,
                            onPay = { viewModel.createPayment(checkoutPlan.code) },
                            onRetryConfig = viewModel::preparePaymentCheckout,
                            onCheckStatus = viewModel::refreshPaymentStatus,
                            onReopen = viewModel::reopenPayment,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(contentWidth)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(top = settingsDp(20f, scale), bottom = navigationBottomInset + settingsDp(24f, scale)),
                        )
                        state.paymentCheckout.result?.let { result ->
                            PaymentResultDialog(
                                result = result,
                                language = language,
                                scale = scale,
                                backdrop = cardBackdrop,
                                liveGlassEnabled = liveGlassEnabled,
                                onDismiss = {
                                    viewModel.dismissPaymentResult()
                                    if (result == PaymentCheckoutResult.SUCCESS) checkoutVisible = false
                                },
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .width(contentWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                top = topPadding,
                                bottom = bottomNavigationReserved + settingsDp(20f, scale),
                            ),
                        verticalArrangement = Arrangement.spacedBy(containerGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (plans.isEmpty()) {
                            PlansEmptyCard(
                                language = language,
                                scale = scale,
                                modifier = Modifier.width(cardWidth),
                            )
                        } else {
                            val selectedPlan = plans[pagerState.currentPage.coerceIn(plans.indices)]
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(indicatorGap),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(cardHeight),
                                    contentPadding = PaddingValues(horizontal = pagePadding),
                                    pageSpacing = settingsDp(16f, scale),
                                ) { page ->
                                    PlanTariffCard(
                                        plan = plans[page],
                                        cycle = cycle,
                                        language = language,
                                        scale = scale,
                                        backdrop = cardBackdrop,
                                        liveGlassEnabled = liveGlassEnabled,
                                        modifier = Modifier
                                            .width(cardWidth)
                                            .height(cardHeight),
                                        onCycleChanged = viewModel::setBillingCycle,
                                    )
                                }
                                PlanIndicatorDots(
                                    count = plans.size,
                                    selectedIndex = pagerState.currentPage.coerceIn(plans.indices),
                                    activeColor = settingsPlanColor(selectedPlan) ?: SettingsAccentPrimary,
                                    scale = scale,
                                )
                            }

                            if (selectedPlan.monthlyPriceRub > 0 && !state.currentDeviceAccessRole.equals("invited", true)) {
                                PlanActionButton(
                                    text = if (canRenewPlan(selectedPlan, state)) {
                                        tr(language, "Продлить", "Renew")
                                    } else {
                                        tr(language, "Подключить", "Subscribe")
                                    },
                                    scale = scale,
                                    modifier = Modifier.width(cardWidth),
                                    backdrop = cardBackdrop,
                                    liveGlassEnabled = liveGlassEnabled,
                                    onClick = {
                                        checkoutPlanCode = selectedPlan.code
                                        checkoutVisible = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
