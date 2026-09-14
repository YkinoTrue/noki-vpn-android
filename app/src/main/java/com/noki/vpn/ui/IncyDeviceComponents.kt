package com.noki.vpn.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.noki.vpn.IncyDevicesUiState
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendIncyDevice
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

private const val INCY_CLIP_LABEL = "INCY"
private const val INCY_CLIP_OWNER_MARKER_KEY = "com.noki.vpn.INCY_CLIP_OWNER_MARKER"
internal const val INCY_CLIPBOARD_CLEAR_DELAY_MILLIS = 60_000L
private val incyClipboardGeneration = AtomicLong()

@Composable
internal fun IncyDevicesSection(
    state: IncyDevicesUiState,
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onAddClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onRetryClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
    ) {
        DevicesText(
            text = "INCY",
            fontSize = 12f,
            lineHeight = 14.4f,
            color = DevicesTextSecondary,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
        IncyAddDeviceCard(
            language = language,
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            onClick = onAddClick,
        )
        state.devices.forEach { device ->
            IncyDeviceRow(device, backdrop, liveGlassEnabled, scale) { onDeviceClick(device.id) }
        }
        if (state.isLoading && state.devices.isEmpty()) {
            DevicesText(
                text = tr(language, "Загрузка…", "Loading…"),
                fontSize = 11f,
                lineHeight = 13f,
                color = DevicesTextMuted,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        if (!state.error.isNullOrBlank() && !state.isCreateDialogVisible && !state.isManageDialogVisible) {
            DevicesText(
                text = state.error,
                fontSize = 11f,
                lineHeight = 13f,
                color = DevicesError,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
            AuthSecondaryButton(
                text = tr(language, "Повторить", "Retry"),
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(devicesDp(42f, scale)),
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = onRetryClick,
            )
        }
    }
}

@Composable
private fun IncyAddDeviceCard(
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    DeviceActionCardSurface(
        enabled = true,
        scale = scale,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        contentAlignment = Alignment.Center,
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevicesText("+", 22f, 22f, DevicesAccentPrimary, scale, Modifier)
            DevicesText(
                tr(language, "Добавить устройство", "Add device"),
                15f,
                18f,
                DevicesTextPrimary,
                scale,
                Modifier,
            )
        }
    }
}

@Composable
private fun IncyDeviceRow(
    device: BackendIncyDevice,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val subtitle = device.deviceModel
        ?: device.deviceOs
        ?: if (device.status.equals("waiting", true)) "INCY · ожидает подключения" else "INCY"
    val shape = RoundedCornerShape(devicesDp(24f, scale))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(devicesDp(72f, scale))
            .nokiSettingsPanelGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = scale,
                elevationDp = 8f,
                shadowAlpha = 0.25f,
                surfaceColor = DevicesBgLighter.copy(alpha = 0.80f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = devicesDp(20f, scale)),
        horizontalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIconTile(platformHint = device.deviceOs ?: "INCY", scale = scale)
        DeviceInfo(
            title = device.name,
            subtitle = subtitle,
            titleFontSize = 18f,
            scale = scale,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun IncyDeviceDialogs(
    state: IncyDevicesUiState,
    language: AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onNameChanged: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: () -> Unit,
    onReissue: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedLinkIndex by remember(state.selectedDeviceId, state.v2raynSubscriptionUrl) {
        mutableIntStateOf(0)
    }
    val activeConnectionLink = when (selectedLinkIndex) {
        1 -> state.v2raynSubscriptionUrl?.value
        else -> state.importLink?.value
    }
    if (state.isCreateDialogVisible) {
        IncyDeviceDialogSurface(
            title = tr(language, "Новое устройство INCY", "New INCY device"),
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            onDismiss = onDismiss,
        ) {
            SettingsCompactInputField(
                value = state.nameInput,
                onValueChange = onNameChanged,
                placeholder = tr(language, "Название устройства", "Device name"),
                scale = scale,
                backgroundColor = DevicesBgSoft,
                enabled = !state.isLoading,
            )
            IncyDialogError(state.error, scale)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
            ) {
                DevicesDialogButton(
                    text = tr(language, "Отмена", "Cancel"),
                    scale = scale,
                    isPrimary = false,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                DevicesDialogButton(
                    text = tr(language, "Создать", "Create"),
                    scale = scale,
                    isPrimary = false,
                    enabled = state.nameInput.isNotBlank() && !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onCreate,
                )
            }
        }
    }
    if (state.isManageDialogVisible) {
        IncyDeviceDialogSurface(
            title = "INCY",
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
            onDismiss = onDismiss,
        ) {
            SettingsCompactInputField(
                value = state.nameInput,
                onValueChange = onNameChanged,
                placeholder = tr(language, "Название устройства", "Device name"),
                scale = scale,
                backgroundColor = DevicesBgSoft,
                enabled = !state.isLoading,
            )
            if (state.v2raynSubscriptionUrl != null) {
                GlassSegmentedControl(
                    labels = listOf("INCY", "v2rayN"),
                    selectedIndex = selectedLinkIndex,
                    onSelectedIndexChanged = { selectedLinkIndex = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    containerHeight = devicesDp(42f, scale),
                    capsulePadding = devicesDp(4f, scale),
                    lensRefractionHeight = devicesDp(4f, scale),
                    lensRefractionAmount = devicesDp(6f, scale),
                    chromaticAberration = false,
                    liveGlassEnabled = false,
                    depthEffectEnabled = false,
                )
            }
            activeConnectionLink?.let { link ->
                ConnectionQrBlock(
                    link = link,
                    label = if (selectedLinkIndex == 1) {
                        tr(language, "Ссылка подписки v2rayN", "v2rayN subscription link")
                    } else {
                        tr(language, "Ссылка подключения INCY", "INCY connection link")
                    },
                    scale = scale,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
            ) {
                DevicesDialogButton(
                    text = tr(language, "Сохранить", "Save"),
                    scale = scale,
                    isPrimary = false,
                    enabled = state.nameInput.isNotBlank() && !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onRename,
                )
                DevicesDialogButton(
                    text = tr(language, "Копировать", "Copy"),
                    scale = scale,
                    isPrimary = false,
                    enabled = activeConnectionLink != null && !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = { activeConnectionLink?.let { copyIncy(context, it) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
            ) {
                DevicesDialogButton(
                    text = tr(language, "Перевыпустить", "Reissue"),
                    scale = scale,
                    isPrimary = false,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onReissue,
                )
                DevicesDialogButton(
                    text = tr(language, "Удалить", "Delete"),
                    scale = scale,
                    isPrimary = true,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete,
                )
            }
            IncyDialogError(state.error, scale)
        }
    }
}

@Composable
private fun ConnectionQrBlock(
    link: String,
    label: String,
    scale: Float,
) {
    val bitmap = remember(link) { qrPayloadBitmap(link, 512) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(devicesDp(8f, scale)),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            modifier = Modifier
                .size(devicesDp(176f, scale))
                .background(Color.White)
                .padding(devicesDp(8f, scale)),
        )
        DevicesText(
            text = label,
            fontSize = 11f,
            lineHeight = 14f,
            color = DevicesTextSecondary,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun IncyDeviceDialogSurface(
    title: String,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(devicesDp(24f, scale))
        Column(
            modifier = Modifier
                .width(devicesDp(330f, scale))
                .nokiSettingsPanelGlassSurface(
                    shape = shape,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    scale = scale,
                    elevationDp = 16f,
                    shadowAlpha = 0.32f,
                    surfaceColor = DevicesBgLighter.copy(alpha = 0.90f),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(devicesDp(20f, scale)),
            verticalArrangement = Arrangement.spacedBy(devicesDp(18f, scale)),
            horizontalAlignment = Alignment.Start,
        ) {
            DevicesText(
                text = title,
                fontSize = 18f,
                lineHeight = 22f,
                color = DevicesTextPrimary,
                scale = scale,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            content()
        }
    }
}

@Composable
private fun IncyDialogError(error: String?, scale: Float) {
    error?.takeIf { it.isNotBlank() }?.let { message ->
        DevicesText(
            text = message,
            fontSize = 12f,
            lineHeight = 15f,
            color = DevicesError,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
    }
}

private fun copyIncy(context: Context, link: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(INCY_CLIP_LABEL, link)
    val ownerMarker = UUID.randomUUID().toString()
    clip.description.extras = PersistableBundle().apply {
        putString(INCY_CLIP_OWNER_MARKER_KEY, ownerMarker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)

    val generation = incyClipboardGeneration.incrementAndGet()
    scheduleIncyClipboardCleanup(
        context = context,
        clipboard = clipboard,
        expectedLink = link,
        expectedOwnerMarker = ownerMarker,
        generation = generation,
    )
}

internal fun shouldClearIncyClipboard(
    expectedLink: String,
    expectedOwnerMarker: String,
    itemCount: Int,
    label: CharSequence?,
    text: CharSequence?,
    ownerMarker: String?,
): Boolean =
    itemCount == 1 &&
        label?.toString() == INCY_CLIP_LABEL &&
        text?.toString() == expectedLink &&
        ownerMarker == expectedOwnerMarker

private fun scheduleIncyClipboardCleanup(
    context: Context,
    clipboard: ClipboardManager,
    expectedLink: String,
    expectedOwnerMarker: String,
    generation: Long,
) {
    val lifecycle = context.findComponentActivity()?.lifecycle
    var expired = false
    val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (expired && tryClearIncyClipboard(
                    clipboard = clipboard,
                    expectedLink = expectedLink,
                    expectedOwnerMarker = expectedOwnerMarker,
                    generation = generation,
                    clipboardReadIsReliable = true,
                )
            ) {
                owner.lifecycle.removeObserver(this)
            }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
        }
    }
    lifecycle?.addObserver(observer)

    Handler(Looper.getMainLooper()).postDelayed({
        expired = true
        val clipboardReadIsReliable = lifecycle
            ?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (tryClearIncyClipboard(
                clipboard = clipboard,
                expectedLink = expectedLink,
                expectedOwnerMarker = expectedOwnerMarker,
                generation = generation,
                clipboardReadIsReliable = clipboardReadIsReliable,
            )
        ) {
            lifecycle?.removeObserver(observer)
        }
    }, INCY_CLIPBOARD_CLEAR_DELAY_MILLIS)
}

private fun tryClearIncyClipboard(
    clipboard: ClipboardManager,
    expectedLink: String,
    expectedOwnerMarker: String,
    generation: Long,
    clipboardReadIsReliable: Boolean,
): Boolean {
    if (incyClipboardGeneration.get() != generation) return true
    val current = runCatching { clipboard.primaryClip }.getOrNull()
        ?: return clipboardReadIsReliable
    if (!shouldClearIncyClipboard(
            expectedLink = expectedLink,
            expectedOwnerMarker = expectedOwnerMarker,
            itemCount = current.itemCount,
            label = current.description.label,
            text = current.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text,
            ownerMarker = current.description.extras?.getString(INCY_CLIP_OWNER_MARKER_KEY),
        )
    ) {
        return true
    }
    val cleared = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }.isSuccess
    if (cleared) incyClipboardGeneration.incrementAndGet()
    return cleared
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
