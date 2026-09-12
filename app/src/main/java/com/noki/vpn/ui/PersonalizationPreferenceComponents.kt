package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.GlassMode

@Composable
internal fun PersonalizationLanguageRow(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    PersonalizationPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = modifier,
        onClick = {
            onLanguageChanged(if (language == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.dp(20f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = metrics.dp(12f)),
                verticalArrangement = Arrangement.spacedBy(metrics.dp(3f)),
            ) {
                PersonalizationText(
                    text = tr(language, "Язык приложения", "App language"),
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                )
                PersonalizationText(
                    text = if (language == AppLanguage.RU) "Русский" else "English",
                    color = PersonalizationTextSecondary.copy(alpha = 0.8f),
                    fontSize = 11f,
                    lineHeight = 13.2f,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PersonalizationLanguageToggle(
                selectedLanguage = language,
                onLanguageChanged = onLanguageChanged,
            )
        }
    }
}

@Composable
internal fun PersonalizationLanguageToggle(
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Row(
        modifier = Modifier
            .height(metrics.dp(34f))
            .clip(RoundedCornerShape(metrics.dp(24f)))
            .background(PersonalizationBgSoft.copy(alpha = 0.72f))
            .border(BorderStroke(1.dp, PersonalizationStroke.copy(alpha = 0.82f)), RoundedCornerShape(metrics.dp(24f)))
            .padding(metrics.dp(3f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonalizationLanguageChip(
            text = "RU",
            selected = selectedLanguage == AppLanguage.RU,
            onClick = { onLanguageChanged(AppLanguage.RU) },
        )
        Spacer(modifier = Modifier.width(metrics.dp(4f)))
        PersonalizationLanguageChip(
            text = "EN",
            selected = selectedLanguage == AppLanguage.EN,
            onClick = { onLanguageChanged(AppLanguage.EN) },
        )
    }
}

@Composable
internal fun PersonalizationLanguageChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(metrics.dp(42f))
            .fillMaxHeight()
            .clip(RoundedCornerShape(metrics.dp(24f)))
            .background(
                if (selected) PersonalizationAccent.copy(alpha = 0.24f) else Color.Transparent,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PersonalizationText(
            text = text,
            color = if (selected) PersonalizationTextPrimary else PersonalizationTextSecondary.copy(alpha = 0.72f),
            fontSize = 12f,
            lineHeight = 14.4f,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun fullGlassEnabled(mode: GlassMode): Boolean = mode == GlassMode.FULL

internal fun glassModeForFullGlassEnabled(enabled: Boolean): GlassMode =
    if (enabled) GlassMode.FULL else GlassMode.SIMPLE

@Composable
internal fun PersonalizationGlassModeRow(
    glassMode: GlassMode,
    onGlassModeChanged: (GlassMode) -> Unit,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    PersonalizationPanelSurface(
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
            PersonalizationText(
                text = "Full Glass",
                fontSize = 18f,
                lineHeight = 21.6f,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            NokiLiquidToggle(
                sizeFactor = metrics.contentScale,
                selected = fullGlassEnabled(glassMode),
                onSelectedChange = { onGlassModeChanged(glassModeForFullGlassEnabled(it)) },
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                modifier = Modifier.padding(start = metrics.dp(14f)),
            )
        }
    }
}

@Composable
internal fun PersonalizationPanelSurface(
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(NokiUiKitPolicy.panelCornerRadiusDp.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = 1f,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                surfaceColor = PersonalizationBgLighter.copy(alpha = 0.80f),
                blurAndLensEnabled = false,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
internal fun PersonalizationText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    fontWeight: FontWeight,
    modifier: Modifier,
    color: Color = PersonalizationTextPrimary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val contentScale = nokiAdaptiveMetrics(configuration.screenWidthDp.dp).contentScale
    Text(
        text = text,
        color = color,
        fontFamily = ManropeFontFamily,
        fontWeight = fontWeight,
        fontSize = (fontSize * contentScale / density.fontScale).sp,
        lineHeight = (lineHeight * contentScale / density.fontScale).sp,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = PersonalizationNoFontPaddingTextStyle,
        modifier = modifier,
    )
}
