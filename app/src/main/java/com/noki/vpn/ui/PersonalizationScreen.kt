package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.AppUiState
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.GlassMode

private val PersonalizationBgBase = Color(0xFF07111A)
internal val PersonalizationBgLighter = Color(0xFF0D1B2A)
internal val PersonalizationBgSoft = Color(0xFF132635)
internal val PersonalizationTextPrimary = Color(0xFFF4FBFF)
internal val PersonalizationTextSecondary = Color(0xFFB5C6D2)
internal val PersonalizationStroke = Color(0xFF29404E)
internal val PersonalizationAccent = Color(0xFF7AE7C7)
internal val PersonalizationNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun PersonalizationScreen(
    state: AppUiState,
    onLanguageChanged: (AppLanguage) -> Unit,
    onGlassModeChanged: (GlassMode) -> Unit,
    onPickAvatarClicked: () -> Unit,
    onDeleteAvatarClicked: () -> Unit,
    onAvatarEditDenied: () -> Unit,
    sharedBackdrop: LayerBackdrop,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    val avatarMenuExpanded = remember { mutableStateOf(false) }
    val avatarMenuBounds = remember { mutableStateOf<Rect?>(null) }
    var deleteAvatarConfirmVisible by rememberSaveable { mutableStateOf(false) }
    val setAvatarMenuExpanded: (Boolean) -> Unit = { expanded ->
        avatarMenuExpanded.value = expanded
        if (!expanded) {
            avatarMenuBounds.value = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (showBackground) Modifier.background(PersonalizationBgBase) else Modifier)
            .pointerInput(avatarMenuExpanded.value, avatarMenuBounds.value) {
                if (!avatarMenuExpanded.value) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                        val bounds = avatarMenuBounds.value
                        if (bounds != null && !bounds.contains(down.position)) {
                            down.consume()
                            setAvatarMenuExpanded(false)
                        }
                    }
                }
            }
    ) {
        val backdrop = if (showBackground || liveGlassEnabled) sharedBackdrop else null
        val modalBackdrop = if (liveGlassEnabled && deleteAvatarConfirmVisible) {
            rememberLayerBackdrop {
                drawRect(PersonalizationBgBase)
                drawContent()
            }
        } else {
            null
        }
        val metrics = nokiAdaptiveMetrics(maxWidth)
        val language = state.personalizationSettings.language
        val preparedProfile = state.settingsPreparedState
        val avatarEditingEnabled = !preparedProfile.isInvitedDevice
        val headerToFirstBlockGap = metrics.dp(3f) + metrics.dp(14.4f) + metrics.dp(20f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (modalBackdrop != null) Modifier.layerBackdrop(modalBackdrop) else Modifier),
        ) {
            if (showBackground) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                ) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = metrics.contentStart)
                    .padding(top = metrics.dp(58f), bottom = metrics.dp(150f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PersonalizationText(
                    text = tr(language, "Персонализация", "Personalization"),
                    fontSize = 24f,
                    lineHeight = 28.8f,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .padding(start = metrics.dp(18f), end = metrics.dp(18f)),
                )

                Spacer(modifier = Modifier.height(headerToFirstBlockGap))

                Column(
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                ) {
                    PersonalizationAvatarBlock(
                        avatarUri = preparedProfile.avatarUri,
                        isUploadingAvatar = state.isUploadingAvatar,
                        language = language,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        avatarMenuExpanded = avatarMenuExpanded.value,
                        avatarEditingEnabled = avatarEditingEnabled,
                        onAvatarMenuExpandedChange = setAvatarMenuExpanded,
                        onAvatarMenuBoundsChanged = { avatarMenuBounds.value = it },
                        onAvatarEditDenied = onAvatarEditDenied,
                        onPickAvatarClicked = onPickAvatarClicked,
                        onDeleteAvatarClicked = {
                            deleteAvatarConfirmVisible = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(PersonalizationAvatarLayoutPolicy.avatarSizeDp)),
                    )
                    Spacer(modifier = Modifier.height(metrics.dp(PersonalizationAvatarLayoutPolicy.avatarToSettingsGapDp)))
                    PersonalizationLanguageRow(
                        language = language,
                        onLanguageChanged = onLanguageChanged,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(80f)),
                    )
                    Spacer(modifier = Modifier.height(metrics.dp(15f)))
                    PersonalizationGlassModeRow(
                        glassMode = state.personalizationSettings.glassMode,
                        onGlassModeChanged = onGlassModeChanged,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(80f)),
                    )
                }
            }
        }

        if (deleteAvatarConfirmVisible) {
            SettingsConfirmDialog(
                title = tr(language, "Удалить аватарку?", "Delete avatar?"),
                message = tr(
                    language,
                    "Аватарка будет удалена из профиля на всех устройствах.",
                    "The avatar will be removed from the profile on all devices.",
                ),
                dismissText = tr(language, "Отмена", "Cancel"),
                confirmText = tr(language, "Удалить", "Delete"),
                confirmIsDanger = true,
                scale = metrics.contentScale,
                backdrop = modalBackdrop ?: backdrop,
                liveGlassEnabled = liveGlassEnabled,
                onDismiss = { deleteAvatarConfirmVisible = false },
                onConfirm = {
                    deleteAvatarConfirmVisible = false
                    onDeleteAvatarClicked()
                },
            )
        }
    }
}
