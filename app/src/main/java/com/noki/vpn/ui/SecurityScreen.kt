package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.noki.vpn.AppUiState
import com.noki.vpn.AccountSecurityActionState
import com.noki.vpn.AppDialog
import com.noki.vpn.MainViewModel
import com.noki.vpn.TelegramAuthPurpose
import com.noki.vpn.TelegramLoginStateReducer

private val SecurityBgBase = Color(0xFF07111A)
internal val SecurityBgLighter = Color(0xFF0D1B2A)
internal val SecurityBgSoft = Color(0xFF132635)
internal val SecurityStroke = Color(0xFF29404E)
internal val SecurityTextPrimary = Color(0xFFF4FBFF)
internal val SecurityTextSecondary = Color(0xFF9FB6C5)
internal val SecurityTextMuted = Color(0xFF6E8797)
internal val SecurityAccent = Color(0xFF7AE7C7)
private val SecurityNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
internal const val SecurityTermsDocumentPath =
    "M2.031,0.003L2.235,0.002L16.9,0.002L17.825,0.001C18.083,0.001 18.35,-0.007 18.604,0.03C19.039,0.092 19.442,0.295 19.751,0.606C20.053,0.905 20.252,1.292 20.319,1.711C20.371,2.031 20.352,2.726 20.352,3.083L20.351,14.011C20.501,14.037 20.65,14.069 20.798,14.105C22.228,14.456 23.457,15.363 24.214,16.624C24.966,17.878 25.19,19.38 24.836,20.799C24.467,22.284 23.541,23.458 22.238,24.241C21.446,24.698 20.639,24.921 19.732,24.99C19.41,25.014 19.109,24.988 18.787,24.987L2.942,24.986C2.571,24.986 2.202,24.997 1.832,24.965C0.886,24.882 0.071,24.064 0.017,23.112C-0.009,22.644 0.003,22.166 0.003,21.696L0.003,2.964C0.003,2.591 -0.011,2.178 0.023,1.81C0.094,1.03 0.686,0.316 1.44,0.099C1.65,0.038 1.814,0.018 2.031,0.003ZM19.799,23.212C21.87,23.037 23.409,21.22 23.238,19.15C23.067,17.08 21.251,15.54 19.179,15.706C17.101,15.873 15.553,17.694 15.724,19.77C15.896,21.846 17.721,23.388 19.799,23.212ZM15.394,6.392C15.914,6.354 16.318,6.003 16.275,5.453C16.256,5.219 16.143,5.003 15.961,4.854C15.762,4.689 15.593,4.663 15.338,4.659C14.877,4.652 14.416,4.656 13.955,4.656L11.341,4.656C9.229,4.656 7.081,4.635 4.974,4.658C4.688,4.68 4.482,4.726 4.285,4.954C4.132,5.133 4.058,5.366 4.079,5.6C4.1,5.832 4.213,6.046 4.393,6.193C4.529,6.305 4.701,6.376 4.875,6.384C5.27,6.403 5.689,6.395 6.084,6.394L13.042,6.394C13.82,6.394 14.617,6.403 15.394,6.392ZM15.461,11.036C15.731,11.002 15.934,10.908 16.104,10.686C16.241,10.505 16.3,10.278 16.27,10.054C16.238,9.821 16.114,9.61 15.924,9.469C15.631,9.252 15.186,9.306 14.833,9.306L5.59,9.305C5.387,9.305 5.12,9.297 4.923,9.312C4.66,9.33 4.471,9.388 4.29,9.594C4.136,9.772 4.06,10.004 4.078,10.239C4.111,10.66 4.459,11.009 4.879,11.031C5.039,11.039 5.189,11.039 5.349,11.039L13.125,11.039C13.889,11.039 14.701,11.054 15.461,11.036ZM12.438,15.682C12.734,15.675 12.903,15.633 13.122,15.414C13.293,15.242 13.377,15.044 13.371,14.798C13.365,14.559 13.261,14.333 13.084,14.171C12.957,14.053 12.817,13.981 12.645,13.968C12.325,13.942 11.977,13.952 11.655,13.952L6.679,13.952C6.164,13.952 5.56,13.935 5.054,13.955C4.757,13.96 4.541,13.98 4.319,14.209C4.158,14.377 4.071,14.601 4.075,14.834C4.08,15.063 4.176,15.28 4.342,15.438C4.439,15.531 4.616,15.644 4.748,15.66C5.039,15.695 5.364,15.683 5.66,15.683L12.438,15.682ZM11.322,20.33C11.508,20.317 11.655,20.294 11.812,20.187C12.08,20.005 12.232,19.715 12.206,19.39C12.185,19.153 12.069,18.935 11.884,18.785C11.751,18.676 11.645,18.63 11.477,18.614C11.17,18.584 10.846,18.594 10.537,18.594H6.326C5.901,18.594 5.392,18.581 4.972,18.596C4.781,18.611 4.647,18.625 4.48,18.727C4.198,18.899 4.053,19.213 4.08,19.54C4.099,19.77 4.211,19.982 4.391,20.128C4.536,20.247 4.66,20.301 4.847,20.318C5.102,20.341 5.376,20.332 5.633,20.332H7.033C8.447,20.332 9.912,20.351 11.322,20.33Z"
internal const val SecurityTermsCheckPath =
    "M21.143,17.438C21.163,17.437 21.183,17.436 21.203,17.435C21.435,17.43 21.659,17.517 21.826,17.677C21.999,17.842 22.082,18.05 22.087,18.286C22.095,18.595 21.973,18.778 21.76,18.991C21.236,19.515 20.708,20.048 20.183,20.569C20.018,20.732 19.523,21.249 19.371,21.351C19.23,21.446 19.064,21.496 18.894,21.496C18.43,21.497 18.122,21.086 17.813,20.772C17.624,20.584 17.073,20.066 16.966,19.876C16.913,19.779 16.879,19.672 16.867,19.562C16.84,19.325 16.909,19.088 17.059,18.903C17.202,18.727 17.411,18.616 17.637,18.596C18.257,18.535 18.481,19.043 18.893,19.369L18.908,19.381C19.282,18.984 19.691,18.597 20.075,18.207C20.273,18.005 20.514,17.753 20.731,17.576C20.831,17.496 21.015,17.454 21.143,17.438Z"

internal const val SecurityPasswordLockPath =
    "M832 464h-68V240c0-70.7-57.3-128-128-128H388c-70.7 0-128 57.3-128 128v224h-68c-17.7 0-32 14.3-32 32v384c0 17.7 14.3 32 32 32h640c17.7 0 32-14.3 32-32V496c0-17.7-14.3-32-32-32zM332 240c0-30.9 25.1-56 56-56h248c30.9 0 56 25.1 56 56v224H332V240zm460 600H232V536h560v304zM484 701v53c0 4.4 3.6 8 8 8h40c4.4 0 8-3.6 8-8v-53a48.01 48.01 0 10-56 0z"

@Composable
fun SecurityScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onTelegramLinkClick: () -> Unit,
    sharedBackdrop: LayerBackdrop,
    menuRowsBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    CompositionLocalProvider(LocalTextStyle provides SecurityNoFontPaddingTextStyle) {
        BackHandler(
            enabled = state.accountSecurityState.action is AccountSecurityActionState.Username,
            onBack = viewModel::dismissAccountUsernameDialog,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(SecurityBgBase) else Modifier),
        ) {
            val backdrop = if (showBackground || liveGlassEnabled) sharedBackdrop else null
            val rowBackdrop = menuRowsBackdrop ?: backdrop
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val language = state.personalizationSettings.language
            val headerToFirstBlockGap = metrics.dp(3f) + metrics.dp(14.4f) + metrics.dp(20f)
            val loggingEnabled = state.advancedSettings.connectionLogsEnabled ||
                state.advancedSettings.errorLogsEnabled
            val isInvitedDevice = state.currentDeviceAccessRole.equals("invited", ignoreCase = true)
            LaunchedEffect(state.isAuthenticated) {
                if (state.isAuthenticated) {
                    viewModel.refreshAndroidUpdateStatus()
                }
            }
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
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = metrics.contentStart)
                    .padding(top = metrics.dp(58f), bottom = metrics.dp(150f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SecurityText(
                    text = tr(language, "Безопасность", "Security"),
                    fontSize = 24f,
                    lineHeight = 28.8f,
                    letterSpacing = 0f,
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
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
                ) {
                    SecurityGlassActionButton(
                        text = SecurityPresentationPolicy.emailLabel(state.userProfile, language),
                        icon = SecurityActionIcon.Email,
                        animateBeforeClick = true,
                        enabled = true,
                        surfaceStyle = SecurityActionSurfaceStyle.Panel,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(60f)),
                        onClick = viewModel::openAccountEmailChange,
                    )

                    SecurityGlassActionButton(
                        text = SecurityPresentationPolicy.passwordLabel(state.userProfile, language),
                        icon = SecurityActionIcon.Password,
                        animateBeforeClick = true,
                        enabled = true,
                        surfaceStyle = SecurityActionSurfaceStyle.Panel,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(60f)),
                        onClick = viewModel::openAccountPasswordChange,
                    )

                    SecurityGlassActionButton(
                        text = tr(language, "Сменить имя пользователя", "Change username"),
                        icon = SecurityActionIcon.Profile,
                        animateBeforeClick = true,
                        enabled = true,
                        surfaceStyle = SecurityActionSurfaceStyle.Panel,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(60f)),
                        onClick = viewModel::openAccountUsernameDialog,
                    )

                    if (state.userProfile.telegramLinked) {
                        SecurityTelegramLinkedRow(
                            text = SecurityPresentationPolicy.telegramLabel(state.userProfile, language),
                            deleteDescription = tr(language, "Отвязать Telegram", "Unlink Telegram"),
                            backdrop = rowBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.dp(60f)),
                            onDeleteClick = viewModel::requestTelegramUnlink,
                        )
                    } else {
                        SecurityGlassActionButton(
                            text = SecurityPresentationPolicy.telegramLabel(state.userProfile, language),
                            icon = SecurityActionIcon.Telegram,
                            animateBeforeClick = true,
                            enabled = !TelegramLoginStateReducer.isActive(state.telegramLoginState),
                            surfaceStyle = SecurityActionSurfaceStyle.Panel,
                            backdrop = rowBackdrop,
                            liveGlassEnabled = liveGlassEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.dp(60f)),
                            onClick = {
                                if (isInvitedDevice) {
                                    viewModel.showCurrentDeviceAccessDenied()
                                } else {
                                    onTelegramLinkClick()
                                }
                            },
                        )
                    }

                    SecurityLoggingRow(
                        loggingEnabled = loggingEnabled,
                        anonymousLogsEnabled = state.advancedSettings.anonymousLogsEnabled,
                        isUploadingLogs = state.isUploadingLogs,
                        logUploadMessage = state.logUploadMessage,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        language = language,
                        onLoggingEnabledChange = { enabled ->
                            viewModel.setLoggingEnabled(enabled)
                        },
                        onAnonymousLogsChange = viewModel::toggleAnonymousLogs,
                        onUploadLocalLogs = viewModel::uploadLocalLogs,
                    )

                    SecurityGlassActionButton(
                        text = tr(language, "Условия использования", "Terms of use"),
                        icon = SecurityActionIcon.Terms,
                        enabled = true,
                        surfaceStyle = SecurityActionSurfaceStyle.Panel,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(metrics.dp(60f)),
                        onClick = {},
                    )

                    SecurityAndroidVersionBlock(
                        updateState = state.androidUpdate,
                        language = language,
                        backdrop = rowBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .fillMaxWidth(),
                        onClick = viewModel::installAndroidUpdate,
                    )
                }
            }
            if (state.dialog == com.noki.vpn.AppDialog.AccessDenied) {
                SettingsAccessDeniedDialog(
                    language = language,
                    scale = metrics.contentScale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = viewModel::dismissDialog,
                )
            }
            TelegramLoginStateReducer.errorMessage(
                state.telegramLoginState,
                TelegramAuthPurpose.LINK,
            )?.let { message ->
                SettingsConfirmDialog(
                    title = tr(language, "Не удалось привязать Telegram", "Could not link Telegram"),
                    message = message,
                    dismissText = tr(language, "Отмена", "Cancel"),
                    confirmText = tr(language, "Повторить", "Retry"),
                    confirmIsDanger = false,
                    scale = metrics.contentScale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = viewModel::cancelTelegramLoginFlow,
                    onConfirm = onTelegramLinkClick,
                )
            }
            (state.dialog as? AppDialog.UnlinkTelegram)?.let { dialog ->
                SettingsConfirmDialog(
                    title = tr(language, "Отвязать Telegram?", "Unlink Telegram?"),
                    message = dialog.error ?: if (dialog.isUnlinking) {
                        tr(language, "Отвязываем Telegram…", "Unlinking Telegram…")
                    } else {
                        tr(
                            language,
                            "Вход через Telegram станет недоступен. Продолжить?",
                            "Telegram sign-in will become unavailable. Continue?",
                        )
                    },
                    dismissText = tr(language, "Отмена", "Cancel"),
                    confirmText = if (dialog.isUnlinking) {
                        tr(language, "Отвязка…", "Unlinking…")
                    } else {
                        tr(language, "Отвязать", "Unlink")
                    },
                    confirmIsDanger = true,
                    scale = metrics.contentScale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onDismiss = if (dialog.isUnlinking) ({}) else viewModel::dismissDialog,
                    onConfirm = if (dialog.isUnlinking) ({}) else viewModel::confirmDialog,
                )
            }
            (state.accountSecurityState.action as? AccountSecurityActionState.Username)?.let { action ->
                SettingsCompactInputDialog(
                    value = action.username,
                    onValueChange = viewModel::updateAccountUsername,
                    placeholder = tr(language, "Имя пользователя", "Username"),
                    dismissText = tr(language, "Отменить", "Cancel"),
                    confirmText = tr(language, "Сохранить", "Save"),
                    scale = metrics.contentScale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    error = action.error,
                    isLoading = action.isLoading,
                    onDismiss = viewModel::dismissAccountUsernameDialog,
                    onConfirm = viewModel::submitAccountUsername,
                )
            }
        }
    }
}
