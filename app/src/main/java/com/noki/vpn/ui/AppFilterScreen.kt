package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.noki.vpn.AppDialog
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.AppFilterPolicy
import kotlinx.coroutines.delay

private val AppFilterBgBase = Color(0xFF07111A)
internal val AppFilterBgLighter = Color(0xFF0D1B2A)
internal val AppFilterTextPrimary = Color(0xFFF4FBFF)
internal val AppFilterTextSecondary = Color(0xFF9FB6C5)
internal val AppFilterAccentPrimary = Color(0xFF7AE7C7)
internal val AppFilterDanger = Color(0xFFFF6B72)
internal val AppFilterStroke = Color(0xFF29404E)
private val AppFilterNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun AppFilterScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    sharedBackdrop: LayerBackdrop,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    val language = state.personalizationSettings.language
    var query by rememberSaveable { mutableStateOf("") }
    var hideSystemApps by rememberSaveable { mutableStateOf(false) }
    var applyNoticeVersion by rememberSaveable { mutableIntStateOf(0) }
    var applyNoticeVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(applyNoticeVersion) {
        if (applyNoticeVersion > 0) {
            applyNoticeVisible = true
            delay(1300)
            applyNoticeVisible = false
        }
    }

    CompositionLocalProvider(LocalTextStyle provides AppFilterNoFontPaddingTextStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(AppFilterBgBase) else Modifier)
                .statusBarsPadding(),
        ) {
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val density = LocalDensity.current
            val contentTop = metrics.dp(58f)
            val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
            val bottomNavTop = maxHeight - navBarBottomInset - metrics.dp(60f) - metrics.dp(20f)
            val contentHeight = (
                bottomNavTop - contentTop - metrics.dp(NokiUiKitPolicy.appFilterApplyBottomClearanceDp)
                    + metrics.dp(NokiUiKitPolicy.appFilterContentBottomExtensionDp)
            ).coerceAtLeast(0.dp)
            val visibleApps = remember(state.installedApps, query, hideSystemApps) {
                AppFilterPolicy.visibleApps(
                    apps = state.installedApps,
                    query = query,
                    hideSystemApps = hideSystemApps,
                )
            }
            val backdrop = if (showBackground || liveGlassEnabled) sharedBackdrop else null

            if (showBackground) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                ) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .offset(x = metrics.contentStart, y = contentTop)
                        .width(metrics.contentWidth)
                        .height(contentHeight),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(17f)),
                ) {
                    AppSearchField(
                        value = query,
                        onValueChange = { query = it },
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        placeholder = tr(language, "Поиск приложений", "Search apps"),
                    )
                    AppSystemAppsToggle(
                        checked = hideSystemApps,
                        language = language,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onCheckedChange = { hideSystemApps = it },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isLoadingInstalledApps) {
                            CircularProgressIndicator(color = AppFilterAccentPrimary)
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds(),
                                verticalArrangement = Arrangement.spacedBy(metrics.dp(9f)),
                            ) {
                                items(
                                    items = visibleApps,
                                    key = { it.packageName },
                                    contentType = { "app-filter-row" },
                                ) { app ->
                                    AppFilterRow(
                                        app = app,
                                        selected = app.packageName in state.selectedPackages,
                                        backdrop = backdrop,
                                        liveGlassEnabled = liveGlassEnabled,
                                        onClick = { viewModel.togglePackageSelection(app.packageName) },
                                    )
                                }
                                if (visibleApps.isEmpty()) {
                                    item(contentType = "app-filter-empty") {
                                        AppFilterEmptyState(
                                            text = tr(language, "Приложения не найдены", "No apps found"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppFilterApplyButton(
                                text = if (applyNoticeVisible) {
                                    tr(language, "Применено", "Applied")
                                } else {
                                    tr(language, "Применить", "Apply")
                                },
                                backdrop = backdrop,
                                liveGlassEnabled = liveGlassEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                                    .height(metrics.dp(60f)),
                                onClick = {
                                    viewModel.applyAppRoutingSettings()
                                    applyNoticeVersion += 1
                                },
                            )
                            AppFilterResetButton(
                                description = tr(language, "Сбросить выбор", "Clear selection"),
                                backdrop = backdrop,
                                liveGlassEnabled = liveGlassEnabled,
                                modifier = Modifier.size(metrics.dp(60f)),
                                onClick = viewModel::requestAppFilterReset,
                            )
                        }
                    }
                }
            }

            if (state.dialog == AppDialog.ResetAppFilter) {
                SettingsConfirmDialog(
                    title = tr(language, "Сбросить выбор?", "Clear selection?"),
                    message = tr(
                        language,
                        "Список выбранных приложений очистится.",
                        "The selected apps list will be cleared.",
                    ),
                    dismissText = tr(language, "Отмена", "Cancel"),
                    confirmText = tr(language, "Сбросить", "Clear"),
                    confirmIsDanger = true,
                    scale = metrics.contentScale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = viewModel::dismissDialog,
                    onConfirm = viewModel::confirmDialog,
                )
            }
        }
    }
}
