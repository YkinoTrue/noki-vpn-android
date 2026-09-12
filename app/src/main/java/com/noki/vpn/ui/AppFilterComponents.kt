package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.noki.vpn.data.AppInfo

@Composable
internal fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(13f))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(44f))
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                elevationDp = 6f,
                shadowAlpha = if (liveGlassEnabled) 0.18f else 0.05f,
                saturation = 1.18f,
                contrast = 1.06f,
                brightness = 0.05f,
            )
            .padding(start = metrics.dp(17f), end = metrics.dp(14f)),
        singleLine = true,
        cursorBrush = SolidColor(AppFilterAccentPrimary),
        textStyle = TextStyle(
            color = AppFilterTextPrimary,
            fontSize = metrics.sp(12f),
            lineHeight = metrics.sp(14.4f),
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = AppFilterTextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(metrics.dp(16f)),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        AppFilterText(
                            text = placeholder,
                            fontSize = 12f,
                            lineHeight = 14.4f,
                            color = AppFilterTextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
internal fun AppFilterRow(
    app: AppInfo,
    selected: Boolean,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(19f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(52f))
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                elevationDp = 6f,
                shadowAlpha = if (liveGlassEnabled) 0.16f else 0.05f,
                saturation = 1.18f,
                contrast = 1.06f,
                brightness = 0.05f,
            )
            .clickable(onClick = onClick)
            .padding(start = metrics.dp(14f), end = metrics.dp(26f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, appName = app.appName)
        Spacer(modifier = Modifier.width(metrics.dp(15f)))
        AppFilterText(
            text = app.appName,
            fontSize = 13.5f,
            lineHeight = 16.2f,
            color = AppFilterTextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        SelectionIndicator(selected = selected)
    }
}

@Composable
internal fun AppIcon(
    packageName: String,
    appName: String,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSize = metrics.dp(36f)
    val iconSizePx = with(density) { iconSize.roundToPx() }
    val icon = remember(packageName, iconSizePx) {
        runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(width = iconSizePx, height = iconSizePx)
                .asImageBitmap()
        }.getOrNull()
    }
    val shape = RoundedCornerShape(metrics.dp(12f))
    Box(
        modifier = Modifier
            .size(iconSize)
            .clip(shape)
            .background(AppFilterBgLighter, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = appName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            AppFilterText(
                text = appName.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                fontSize = 16f,
                lineHeight = 19.2f,
                color = AppFilterTextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier,
            )
        }
    }
}

@Composable
internal fun SelectionIndicator(
    selected: Boolean,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(6f))
    Box(
        modifier = Modifier
            .size(metrics.dp(22f))
            .clip(shape)
            .background(AppFilterBgLighter, shape)
            .border(BorderStroke(1.dp, AppFilterStroke), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AppFilterAccentPrimary,
                modifier = Modifier.size(metrics.dp(15f)),
            )
        }
    }
}

@Composable
internal fun AppSystemAppsToggle(
    checked: Boolean,
    language: com.noki.vpn.data.AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(13f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(44f))
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                elevationDp = 6f,
                shadowAlpha = if (liveGlassEnabled) 0.18f else 0.05f,
                saturation = 1.18f,
                contrast = 1.06f,
                brightness = 0.05f,
            )
            .padding(start = metrics.dp(17f), end = metrics.dp(14f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppFilterText(
            text = tr(language, "Скрывать системные приложения", "Hide system apps"),
            fontSize = 11f,
            lineHeight = 13.2f,
            color = AppFilterTextPrimary,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(end = metrics.dp(12f)),
        )
        NokiLiquidToggle(
            sizeFactor = metrics.contentScale,
            selected = checked,
            onSelectedChange = onCheckedChange,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
        )
    }
}

@Composable
internal fun AppFilterApplyButton(
    text: String,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(18f))
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                elevationDp = 8f,
                shadowAlpha = if (liveGlassEnabled) 0.22f else 0.05f,
                highlightAlpha = 0.34f,
                highlightWidthDp = 0.8f,
                highlightBlurDp = 0.4f,
                saturation = 1.18f,
                contrast = 1.06f,
                brightness = 0.05f,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppFilterText(
            text = text,
            fontSize = 16f,
            lineHeight = 19.2f,
            color = AppFilterTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun AppFilterResetButton(
    description: String,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(18f))
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                elevationDp = 8f,
                shadowAlpha = if (liveGlassEnabled) 0.22f else 0.05f,
                highlightAlpha = 0.34f,
                highlightWidthDp = 0.8f,
                highlightBlurDp = 0.4f,
                saturation = 1.18f,
                contrast = 1.06f,
                brightness = 0.05f,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = description,
            tint = AppFilterDanger,
            modifier = Modifier.size(metrics.dp(26f)),
        )
    }
}

@Composable
internal fun AppFilterEmptyState(
    text: String,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(52f)),
        contentAlignment = Alignment.Center,
    ) {
        AppFilterText(
            text = text,
            fontSize = 13f,
            lineHeight = 15.6f,
            color = AppFilterTextSecondary,
            modifier = Modifier,
        )
    }
}

@Composable
internal fun AppFilterText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
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
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}
