package com.noki.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage

@Composable
internal fun PersonalizationAvatarBlock(
    avatarUri: String?,
    isUploadingAvatar: Boolean,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    avatarMenuExpanded: Boolean,
    avatarEditingEnabled: Boolean,
    onAvatarMenuExpandedChange: (Boolean) -> Unit,
    onAvatarMenuBoundsChanged: (Rect?) -> Unit,
    onAvatarEditDenied: () -> Unit,
    onPickAvatarClicked: () -> Unit,
    onDeleteAvatarClicked: () -> Unit,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val avatarBackdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(metrics.dp(PersonalizationAvatarLayoutPolicy.menuWidthDp))
                .height(metrics.dp(PersonalizationAvatarLayoutPolicy.avatarSizeDp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (liveGlassEnabled) Modifier.layerBackdrop(avatarBackdrop) else Modifier),
                contentAlignment = Alignment.TopCenter,
            ) {
                PersonalizationAvatarStack(
                    avatarUri = avatarUri,
                    isUploadingAvatar = isUploadingAvatar,
                    language = language,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    avatarEditingEnabled = avatarEditingEnabled,
                    onClick = {
                        if (!isUploadingAvatar) {
                            if (avatarEditingEnabled) {
                                onAvatarMenuExpandedChange(!avatarMenuExpanded)
                            } else {
                                onAvatarEditDenied()
                            }
                        }
                    },
                )
            }

            if (avatarMenuExpanded && avatarEditingEnabled) {
                PersonalizationAvatarMenu(
                    language = language,
                    backdrop = if (liveGlassEnabled) avatarBackdrop else backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    enabled = !isUploadingAvatar,
                    onPickAvatarClicked = {
                        onAvatarMenuExpandedChange(false)
                        onPickAvatarClicked()
                    },
                    onDeleteAvatarClicked = {
                        onAvatarMenuExpandedChange(false)
                        onDeleteAvatarClicked()
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = metrics.dp(PersonalizationAvatarLayoutPolicy.menuTopOffsetDp))
                        .onGloballyPositioned { coordinates ->
                            onAvatarMenuBoundsChanged(coordinates.boundsInRoot())
                        }
                        .zIndex(2f),
                )
            }
        }
    }
}

@Composable
internal fun PersonalizationAvatarStack(
    avatarUri: String?,
    isUploadingAvatar: Boolean,
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    avatarEditingEnabled: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val editButtonShape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(metrics.dp(PersonalizationAvatarLayoutPolicy.avatarSizeDp))
            .clickable(
                enabled = !isUploadingAvatar,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        Avatar(
            avatarUri = avatarUri,
            size = metrics.dp(PersonalizationAvatarLayoutPolicy.avatarSizeDp),
        )
        Box(
            modifier = Modifier
                .offset(
                    x = metrics.dp(PersonalizationAvatarLayoutPolicy.editButtonOffsetXDp),
                    y = metrics.dp(PersonalizationAvatarLayoutPolicy.editButtonOffsetYDp),
                )
                .size(metrics.dp(PersonalizationAvatarLayoutPolicy.editButtonSizeDp))
                .graphicsLayer {
                    alpha = if (avatarEditingEnabled) 1f else 0.45f
                }
                .nokiGlassSurface(
                    shape = editButtonShape,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    elevationDp = 6f,
                    shadowAlpha = if (liveGlassEnabled) 0.18f else 0.05f,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.personalization_avatar_edit_icon),
                contentDescription = tr(language, "Изменить аватарку", "Change avatar"),
                modifier = Modifier.size(metrics.dp(PersonalizationAvatarLayoutPolicy.editIconSizeDp)),
            )
        }
    }
}

@Composable
internal fun PersonalizationAvatarMenu(
    language: AppLanguage,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    enabled: Boolean,
    onPickAvatarClicked: () -> Unit,
    onDeleteAvatarClicked: () -> Unit,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(PersonalizationAvatarLayoutPolicy.menuCornerRadiusDp))
    Box(
        modifier = modifier
            .width(metrics.dp(PersonalizationAvatarLayoutPolicy.menuWidthDp))
            .height(metrics.dp(PersonalizationAvatarLayoutPolicy.menuHeightDp))
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = metrics.contentScale,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                blurRadiusDp = 3f,
                lensRadiusDp = 28f,
                lensRefractionDp = 42f,
                chromaticAberration = true,
                surfaceColor = PersonalizationBgLighter.copy(alpha = 0.80f),
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            PersonalizationAvatarMenuRow(
                iconRes = R.drawable.personalization_avatar_gallery_icon,
                text = tr(language, "Выбрать из галереи", "Choose from gallery"),
                textColor = PersonalizationTextPrimary,
                iconTextGap = metrics.dp(PersonalizationAvatarLayoutPolicy.pickRowIconTextGapDp),
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(PersonalizationAvatarLayoutPolicy.menuRowHeightDp)),
                onClick = onPickAvatarClicked,
            )
            PersonalizationAvatarMenuRow(
                iconRes = R.drawable.personalization_avatar_delete_icon,
                text = tr(language, "Удалить", "Delete"),
                textColor = Color(0xFFFF6B6B),
                iconTextGap = metrics.dp(PersonalizationAvatarLayoutPolicy.deleteRowIconTextGapDp),
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(PersonalizationAvatarLayoutPolicy.menuRowHeightDp)),
                onClick = onDeleteAvatarClicked,
            )
        }
    }
}

@Composable
internal fun PersonalizationAvatarMenuRow(
    iconRes: Int,
    text: String,
    textColor: Color,
    iconTextGap: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Row(
        modifier = modifier
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = metrics.dp(PersonalizationAvatarLayoutPolicy.menuHorizontalPaddingDp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(metrics.dp(PersonalizationAvatarLayoutPolicy.menuIconSizeDp)),
        )
        Spacer(modifier = Modifier.width(iconTextGap))
        PersonalizationText(
            text = text,
            color = textColor,
            fontSize = 12f,
            lineHeight = 16f,
            fontWeight = FontWeight.Normal,
            modifier = Modifier,
        )
    }
}
