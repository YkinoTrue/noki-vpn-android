package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol

@Composable
internal fun AdvancedProtocolToggleCard(
    autoEndpointSelection: Boolean,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onAutoEndpointSelectionChanged: (Boolean) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    AdvancedPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.dp(20f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdvancedText(
                text = tr(language, "Автовыбор протокола", "Auto protocol selection"),
                fontSize = 16f,
                lineHeight = 20f,
                color = AdvancedTextPrimary,
                modifier = Modifier.weight(1f),
            )
            NokiLiquidToggle(
                sizeFactor = metrics.contentScale,
                selected = autoEndpointSelection,
                onSelectedChange = onAutoEndpointSelectionChanged,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                modifier = Modifier.padding(start = metrics.dp(14f)),
            )
        }
    }
}

@Composable
internal fun AdvancedManualProtocolCard(
    selectedEndpoint: String,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onChangeProtocol: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val panelBackdrop = rememberLayerBackdrop()
    AdvancedPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        exportedBackdrop = panelBackdrop,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.dp(20f)),
            horizontalArrangement = Arrangement.spacedBy(metrics.dp(16f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                AdvancedFittingText(
                    text = selectedEndpoint,
                    fontSize = 17f,
                    lineHeight = 22f,
                    minFontSize = 11f,
                    color = AdvancedTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(),
                )
            }
            AdvancedSmallButton(
                text = tr(language, "Сменить", "Change"),
                modifier = Modifier
                    .width(metrics.dp(90f))
                    .height(metrics.dp(36f)),
                backdrop = panelBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                glassEnabled = true,
                onClick = onChangeProtocol,
            )
        }
    }
}

@Composable
internal fun AdvancedProtocolSheet(
    selectedProtocol: VpnProtocol,
    endpointOptions: List<VpnEndpointOption>,
    selectedEndpointGroupKey: String,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    bottomNavigationClearance: Dp,
    onDismiss: () -> Unit,
    onProtocolSelected: (VpnEndpointOption?) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val density = LocalDensity.current
    val navigationBottomInset = WindowInsets.navigationBars.getBottom(density)
    val bottomPadding = with(density) { navigationBottomInset.toDp() } + bottomNavigationClearance
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AdvancedPanelSurface(
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            cardBrightness = -0.01f,
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .padding(horizontal = metrics.contentStart)
                .widthIn(max = metrics.dp(370f))
                .fillMaxWidth()
                .heightIn(min = metrics.dp(330f), max = metrics.dp(560f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(metrics.dp(17f)),
                verticalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
            ) {
                AdvancedText(
                    text = tr(language, "Выберите протокол", "Choose protocol"),
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    color = AdvancedTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                AdvancedText(
                    text = tr(
                        language,
                        "Изменение вступит в силу после переподключения.",
                        "The change takes effect after reconnecting.",
                    ),
                    fontSize = 12f,
                    lineHeight = 14.4f,
                    color = AdvancedTextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    endpointOptions.forEach { endpoint ->
                        AdvancedProtocolOption(
                            endpoint = endpoint,
                            selected = EndpointGroupPolicy.groupKey(endpoint) == selectedEndpointGroupKey,
                            onClick = { onProtocolSelected(endpoint) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdvancedProtocolOption(
    endpoint: VpnEndpointOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(24f))
    val borderColor = if (selected) AdvancedAccentPrimary.copy(alpha = 0.75f) else AdvancedStroke.copy(alpha = 0.72f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(46f))
            .clip(shape)
            .background(if (selected) AdvancedAccentPrimary.copy(alpha = 0.12f) else AdvancedBgSoft, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = metrics.dp(16f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            AdvancedText(
                text = endpointDisplayName(endpoint),
                fontSize = 14f,
                lineHeight = 16.8f,
                color = AdvancedTextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (selected) {
            AdvancedText(
                text = "Active",
                fontSize = 11f,
                lineHeight = 13.2f,
                color = AdvancedAccentPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(metrics.dp(70f)),
            )
        }
    }
}

@Composable
internal fun AdvancedProtocolNotice(
    text: String,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(24f))
    Box(
        modifier = modifier
            .clip(shape)
            .background(AdvancedBgLighter.copy(alpha = 0.96f), shape)
            .border(BorderStroke(1.dp, AdvancedStroke.copy(alpha = 0.9f)), shape)
            .padding(horizontal = metrics.dp(16f)),
        contentAlignment = Alignment.Center,
    ) {
        AdvancedText(
            text = text,
            fontSize = 12f,
            lineHeight = 14.4f,
            color = AdvancedTextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
