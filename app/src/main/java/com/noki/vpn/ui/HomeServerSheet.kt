package com.noki.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
@Composable
internal fun HomeServerDropdownOverlay(
    modifier: Modifier,
    visible: Boolean,
    scale: Float,
    language: AppLanguage,
    locations: List<ServerLocation>,
    backdrop: Backdrop?,
    serverRowsBackdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    animateRows: Boolean,
    onCollapse: () -> Unit,
    onLocationSelected: (String, ServerSelectionMode) -> Unit,
    userProfile: UserProfile,
) {
    val menuLocations = remember(locations) { serverMenuLocations(locations) }
    var expandedCountry by remember { mutableStateOf<String?>(null) }
    NokiGlassSheetOverlay(
        modifier = modifier,
        visible = visible,
        scale = scale,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onCollapse = onCollapse,
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier
                .fillMaxWidth()
                .padding(horizontal = designDp(NokiUiKitPolicy.homeServerItemHorizontalPaddingDp, scale))
                .padding(top = designDp(HOME_SERVER_DROPDOWN_FIRST_ITEM_TOP_DP, scale)),
            verticalArrangement = Arrangement.spacedBy(designDp(NokiUiKitPolicy.homeServerItemGapDp, scale)),
            contentPadding = PaddingValues(bottom = designDp(0f, scale)),
        ) {
            item(key = "auto") {
                Box(
                    Modifier.fillMaxWidth().homeServerItemGlassSurface(
                        shape = RoundedCornerShape(designDp(24f, scale)),
                        backdrop = serverRowsBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = scale,
                        selected = userProfile.serverSelectionMode == ServerSelectionMode.AUTO,
                    ),
                ) {
                    HomeServerMenuItem(
                        location = null,
                        language = language,
                        scale = scale,
                        animateOnlineIndicator = animateRows,
                        title = tr(language, "Автовыбор", "Automatic"),
                        selected = userProfile.serverSelectionMode == ServerSelectionMode.AUTO,
                        onClick = { onLocationSelected("", ServerSelectionMode.AUTO) },
                    )
                }
            }
            item(key = "countries-header") {
                Text(
                    text = tr(language, "Страны", "Countries"),
                    color = HomeTextSecondary,
                    fontFamily = ManropeFontFamily,
                    fontSize = designSp(12f, scale),
                    modifier = Modifier.padding(horizontal = designDp(4f, scale)),
                )
            }
            items(
                items = menuLocations,
                key = { it.key },
                contentType = { "home-server-row" },
            ) { entry ->
                val countrySelected = userProfile.serverSelectionMode == ServerSelectionMode.COUNTRY &&
                    (entry.location.code.equals(userProfile.selectedCountryCode, true) ||
                        entry.location.countryCode.equals(userProfile.selectedCountryCode, true))
                val expanded = expandedCountry == entry.location.code
                val shape = RoundedCornerShape(designDp(24f, scale))
                Column(
                    Modifier.fillMaxWidth()
                        .homeServerItemGlassSurface(
                            shape = shape,
                            backdrop = serverRowsBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            scale = scale,
                            selected = countrySelected,
                        )
                        // Resize once: animating the glass bounds reallocates blur/lens layers every frame.
                        .clip(shape),
                ) {
                    HomeServerMenuItem(
                        location = entry.location,
                        language = language,
                        scale = scale,
                        animateOnlineIndicator = animateRows,
                        selected = countrySelected,
                        expanded = expanded,
                        onExpand = if (entry.location.servers.isEmpty()) null else {
                            { expandedCountry = if (expandedCountry == entry.location.code) null else entry.location.code }
                        },
                        onClick = { onLocationSelected(entry.location.code, ServerSelectionMode.COUNTRY) },
                    )
                    if (expanded) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = designDp(18f, scale)),
                            color = HomeTextSecondary.copy(alpha = 0.12f),
                        )
                        Column(Modifier.padding(designDp(8f, scale))) {
                            entry.location.servers.forEach { server ->
                                val serverSelected = userProfile.serverSelectionMode == ServerSelectionMode.SERVER &&
                                    userProfile.selectedNodeId == server.id
                                Box(
                                    Modifier.clip(RoundedCornerShape(designDp(16f, scale)))
                                        .background(if (serverSelected) HomeAccentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                                ) {
                                    HomeServerMenuItem(
                                        location = entry.location,
                                        language = language,
                                        scale = scale,
                                        animateOnlineIndicator = animateRows,
                                        title = server.name,
                                        latencyMs = server.latencyMs,
                                        online = server.isOnline,
                                        selected = serverSelected,
                                        onClick = { onLocationSelected(server.id, ServerSelectionMode.SERVER) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NokiGlassSheetOverlay(
    modifier: Modifier,
    visible: Boolean,
    scale: Float,
    backdrop: Backdrop?,
    liveGlassEnabled: Boolean,
    onCollapse: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val sheetDismissThresholdPx = with(density) { 96.dp.toPx() }
    val dismissDirection = -1f
    val firstServerItemTopPx = with(density) {
        designDp(HOME_SERVER_DROPDOWN_FIRST_ITEM_TOP_DP, scale).toPx()
    }
    val sheetDragOffset = remember { Animatable(0f) }
    val sheetDragScope = rememberCoroutineScope()
    var sheetHeightPx by remember(visible) { mutableFloatStateOf(0f) }
    val passthroughBlockerInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(visible, sheetDragOffset) {
        if (visible) {
            sheetDragOffset.snapTo(0f)
        }
    }

    fun animateSheetBack() {
        sheetDragScope.launch {
            sheetDragOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
        }
    }

    fun sheetExitDistancePx(sheetHeightPx: Float): Float {
        return sheetHeightPx.coerceAtLeast(sheetDismissThresholdPx * 2f)
    }

    fun handleFadeEndDistancePx(sheetHeightPx: Float): Float {
        val exitDistancePx = sheetExitDistancePx(sheetHeightPx)
        val firstItemBoundaryPx = firstServerItemTopPx.coerceIn(0f, exitDistancePx)
        return (exitDistancePx - firstItemBoundaryPx)
            .coerceAtLeast(exitDistancePx * HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION)
    }

    fun handleAlphaForSheetOffset(offsetPx: Float): Float {
        val fadeEndDistancePx = handleFadeEndDistancePx(sheetHeightPx)
        val fadeStartDistancePx = fadeEndDistancePx * HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION
        val closeDistancePx = (offsetPx * dismissDirection).coerceAtLeast(0f)
        if (closeDistancePx <= fadeStartDistancePx) {
            return HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA
        }
        val fadeRangePx = (fadeEndDistancePx - fadeStartDistancePx).coerceAtLeast(1f)
        val fadeProgress = ((closeDistancePx - fadeStartDistancePx) / fadeRangePx)
            .coerceIn(0f, 1f)
        return HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA * (1f - fadeProgress)
    }

    fun handleFadeEndMillis(sheetHeightPx: Float): Int {
        val exitDistancePx = sheetExitDistancePx(sheetHeightPx)
        val fadeEndDistancePx = handleFadeEndDistancePx(sheetHeightPx)
        val fadeEndFraction = (fadeEndDistancePx / exitDistancePx).coerceIn(
            HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION,
            1f,
        )
        return (HOME_SERVER_DROPDOWN_EXIT_DURATION_MS * fadeEndFraction)
            .roundToInt()
            .coerceAtLeast((HOME_SERVER_DROPDOWN_EXIT_DURATION_MS *
                HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION).roundToInt() + 1)
    }

    fun settleSheetDrag() {
        sheetDragScope.launch {
            if (sheetDragOffset.value * dismissDirection >= sheetDismissThresholdPx) {
                sheetDragOffset.animateTo(
                    targetValue = dismissDirection * sheetExitDistancePx(sheetHeightPx),
                    animationSpec = tween(
                        durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                onCollapse()
            } else {
                sheetDragOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        ) + slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(
                durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = HOME_SERVER_DROPDOWN_EXIT_FADE_DURATION_MS,
                delayMillis = HOME_SERVER_DROPDOWN_EXIT_FADE_DELAY_MS,
                easing = FastOutSlowInEasing,
            ),
        ) + slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(
                durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        ),
    ) {
        val transitionHandleAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.PostExit) {
                    keyframes {
                        durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS
                        val fadeStartMillis = (HOME_SERVER_DROPDOWN_EXIT_DURATION_MS *
                            HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION).roundToInt()
                        val fadeEndMillis = handleFadeEndMillis(sheetHeightPx)
                            .coerceAtMost(HOME_SERVER_DROPDOWN_EXIT_DURATION_MS)
                        HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA at 0
                        HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA at fadeStartMillis
                        0f at fadeEndMillis
                        0f at HOME_SERVER_DROPDOWN_EXIT_DURATION_MS
                    }
                } else {
                    tween(durationMillis = 180, easing = FastOutSlowInEasing)
                }
            },
            label = "homeServerDropdownHandleAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) {
                HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA
            } else {
                0f
            }
        }
        val dragHandleAlpha = handleAlphaForSheetOffset(sheetDragOffset.value)
        val handleAlpha = transitionHandleAlpha.coerceAtMost(dragHandleAlpha)
        val sheetRadius = designDp(NokiUiKitPolicy.homeServerDropdownSheetBottomRadiusDp, scale)
        val dropdownSheetShape = RoundedCornerShape(bottomStart = sheetRadius, bottomEnd = sheetRadius)
        val sheetModifier = Modifier
            .fillMaxSize()
            .padding(
                bottom = designDp(NokiUiKitPolicy.homeServerDropdownSheetBottomSpaceDp, scale),
            )
        val sheetVisualModifier = sheetModifier
            .onSizeChanged { sheetHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, sheetDragOffset.value.roundToInt()) }
        val contentClipInset = designDp(
            NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp +
                NokiUiKitPolicy.homeServerDropdownHandleHeightDp -
                NokiUiKitPolicy.homeServerDropdownSheetBottomSpaceDp,
            scale,
        ) + NokiUiKitPolicy.homeServerDropdownContentHandleGapDp.dp
        val sheetContentClipModifier = sheetVisualModifier.padding(bottom = contentClipInset)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(visible, sheetDismissThresholdPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var position = down.position
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            position = change.position
                        } while (event.changes.any { it.pressed })
                        val displacement = position - down.position
                        // Observe the whole sheet, including drags consumed by its list.
                        // The handle keeps its interactive drag/settle animation.
                        if (sheetDragOffset.value == 0f &&
                            -displacement.y >= sheetDismissThresholdPx &&
                            -displacement.y > kotlin.math.abs(displacement.x)
                        ) onCollapse()
                    }
                }
                .clickable(
                    interactionSource = passthroughBlockerInteractionSource,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Box(
                modifier = sheetVisualModifier.homeServerDropdownSheetShadowLayer(
                    shape = dropdownSheetShape,
                    scale = scale,
                ),
            )
            Box(
                modifier = sheetVisualModifier.homeServerDropdownSheetGlassSurface(
                    shape = dropdownSheetShape,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    scale = scale,
                ),
            )
            Box(
                modifier = sheetContentClipModifier.clip(dropdownSheetShape),
            ) {
                val contentModifier = Modifier
                    .offset { IntOffset(0, -sheetDragOffset.value.roundToInt()) }
                    .fillMaxSize()
                content(contentModifier)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(designDp(NokiUiKitPolicy.homeServerDropdownHandleTouchHeightDp, scale))
                    .offset { IntOffset(0, sheetDragOffset.value.roundToInt()) }
                    .pointerInput(visible, sheetDismissThresholdPx, sheetHeightPx) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                sheetDragScope.launch {
                                    sheetDragOffset.stop()
                                }
                            },
                            onDragEnd = {
                                settleSheetDrag()
                            },
                            onDragCancel = {
                                animateSheetBack()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val targetHeightPx = sheetHeightPx
                                .coerceAtLeast(sheetDismissThresholdPx * 2f)
                            val proposedOffset = sheetDragOffset.value + dragAmount
                            val nextOffset = proposedOffset.coerceIn(-targetHeightPx, 0f)
                            sheetDragScope.launch {
                                sheetDragOffset.snapTo(nextOffset)
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = designDp(
                                NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp,
                                scale,
                            ),
                        )
                        .width(designDp(NokiUiKitPolicy.homeServerDropdownHandleWidthDp, scale))
                        .height(designDp(NokiUiKitPolicy.homeServerDropdownHandleHeightDp, scale))
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = handleAlpha)),
                )
            }
        }
    }
}
