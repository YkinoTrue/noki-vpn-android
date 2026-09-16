package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.AppDialog
import com.noki.vpn.AppUiState
import com.noki.vpn.R
import com.noki.vpn.data.BackendAppNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACCOUNT_ACTION_FEEDBACK_DELAY_MS = 180L
private val AccountTextPrimary = Color(0xFFF4FBFF)
private val AccountTextSecondary = Color(0xFF9FB6C5)
private val AccountStroke = Color(0xFF29404E)
private val AccountError = Color(0xFFFF6B6B)
private val AccountTrafficTrack = Color(0xFF0D1B2A)
internal fun accountPlanTitleShimmerColors(
    baseColor: Color,
    style: AccountPlanTitleStyle,
): List<Color> {
    if (style == AccountPlanTitleStyle.PremiumMetallic) {
        return listOf(
            Color(0xFF9DA9B4),
            Color(0xFFAEB9C3),
            Color(0xFFF7F9FA),
            Color(0xFFC5CDD4),
            Color(0xFF9DA9B4),
        )
    }
    fun highlight(amount: Float) = Color(
        red = baseColor.red + (1f - baseColor.red) * amount,
        green = baseColor.green + (1f - baseColor.green) * amount,
        blue = baseColor.blue + (1f - baseColor.blue) * amount,
        alpha = baseColor.alpha,
    )
    return listOf(
        baseColor,
        highlight(0.20f),
        highlight(0.93f),
        highlight(0.42f),
        baseColor,
    )
}

private class AccountPlanTitleShimmerBrush(
    private val position: Float,
    private val baseColor: Color,
    private val style: AccountPlanTitleStyle,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset(
            x = position * size.width,
            y = position * size.height,
        )
        val halfAxis = Offset(
            x = size.width * 0.55f,
            y = size.height * 0.55f,
        )
        return LinearGradientShader(
            from = center - halfAxis,
            to = center + halfAxis,
            colors = accountPlanTitleShimmerColors(baseColor, style),
            colorStops = listOf(0.00f, 0.32f, 0.50f, 0.68f, 1.00f),
            tileMode = TileMode.Clamp,
        )
    }
}

internal data class AccountRowSpec(
    val title: String,
    val iconRes: Int,
    val iconWidth: Int,
    val iconHeight: Int,
    val enabled: Boolean = true,
    val isDanger: Boolean = false,
    val onClick: () -> Unit,
) {
    fun performClick(onAccessDenied: () -> Unit) {
        if (enabled) onClick() else onAccessDenied()
    }
}

@Composable
private fun AccountPaymentHistory(
    state: AppUiState,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val language = state.personalizationSettings.language
    val history = state.paymentCheckout
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var browserError by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 620.dp)
                .accountGlassSurface(RoundedCornerShape(24.dp), backdrop, liveGlassEnabled, scale)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(language, "История платежей", "Payment history"), color = AccountTextPrimary, modifier = Modifier.weight(1f), fontSize = 20.sp)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, tr(language, "Закрыть", "Close"), tint = AccountTextPrimary) }
            }
            androidx.compose.material3.TextButton(onClick = onRefresh, enabled = !history.historyLoading) {
                Text(tr(language, "Обновить", "Refresh"), color = AccountTextPrimary)
            }
            if (history.historyLoading) Text(tr(language, "Загрузка…", "Loading…"), color = AccountTextSecondary)
            history.historyError?.let { Text(it, color = AccountError) }
            if (browserError) Text(tr(language, "Не удалось открыть оплату", "Could not open payment"), color = AccountError)
            if (!history.historyLoading && history.historyError == null && history.history.isEmpty()) {
                Text(tr(language, "Платежей пока нет", "No payments yet"), color = AccountTextSecondary)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(history.history, key = { it.publicId }) { payment ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(payment.description ?: payment.planCode, color = AccountTextPrimary, fontWeight = FontWeight.Medium)
                        Text("${payment.amountRub} ₽ · ${payment.createdAt?.take(10).orEmpty()}", color = AccountTextSecondary)
                        Text(when (payment.status) {
                            "paid" -> tr(language, "Оплачен", "Paid")
                            "pending" -> tr(language, "Ожидает оплаты", "Awaiting payment")
                            "canceled" -> tr(language, "Отменён", "Canceled")
                            "refunded" -> tr(language, "Возврат", "Refunded")
                            "failed" -> tr(language, "Ошибка оплаты", "Payment failed")
                            else -> tr(language, "Создаётся", "Creating")
                        }, color = AccountTextSecondary)
                        if (payment.status == "pending" && payment.paymentUrl?.let { com.noki.vpn.isSafePaymentUrl(it) } == true) {
                            androidx.compose.material3.TextButton(onClick = {
                                browserError = runCatching { uriHandler.openUri(payment.paymentUrl!!) }.isFailure
                            }) { Text(tr(language, "Продолжить оплату", "Continue payment"), color = AccountTextPrimary) }
                        }
                    }
                }
            }
        }
    }
}

private const val ACCOUNT_DELETE_HOLD_MILLIS = 4_000

@Composable
fun AccountScreen(
    state: AppUiState,
    sharedBackdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean = true,
    onPersonalizationClicked: () -> Unit,
    onPlansClicked: () -> Unit,
    onDevicesClicked: () -> Unit,
    onSupportClicked: () -> Unit,
    onSecurityClicked: () -> Unit,
    onNotificationsClicked: () -> Unit,
    onPaymentHistoryClicked: () -> Unit,
    onApplyPromoCode: (String) -> Unit,
    onClearPromoCode: () -> Unit,
    onNotificationDeleted: (String) -> Unit,
    onDeleteAccountClicked: () -> Unit,
    onAccessDenied: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDialog: () -> Unit,
) {
    val language = state.personalizationSettings.language
    val presentation = remember(state) { AccountPresentationPolicy.prepare(state) }
    var showTrafficExplanation by rememberSaveable { mutableStateOf(false) }
    var showPromoDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationHistory by rememberSaveable { mutableStateOf(false) }
    var showPaymentHistory by rememberSaveable { mutableStateOf(false) }
    var promoCode by rememberSaveable { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(state.paymentCheckout.promoMessage) {
        if (state.paymentCheckout.promo?.kind == "days" && state.paymentCheckout.promoMessage != null) showPromoDialog = false
    }

    BackHandler(enabled = showTrafficExplanation || showPromoDialog || showNotificationHistory) {
        when {
            showNotificationHistory -> showNotificationHistory = false
            showPromoDialog -> showPromoDialog = false
            showTrafficExplanation -> showTrafficExplanation = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val metrics = nokiAdaptiveMetrics(
            screenWidth = maxWidth,
            sidePadding = 20.dp,
            designContentWidth = 372.dp,
        )
        val scale = metrics.contentScale
        val accountActionsEnabled = !presentation.isInvitedDevice
        val density = LocalDensity.current
        val navigationBottomInset = with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val contentBottomReserve = navigationBottomInset +
            metrics.dp(60f) +
            metrics.dp(20f) +
            NokiUiKitPolicy.primaryNavigationContentGapDp.dp
        Column(
            modifier = Modifier
                .width(metrics.contentWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .padding(top = metrics.dp(20f), bottom = contentBottomReserve)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr(language, "Профиль", "Profile"),
                    color = AccountTextPrimary,
                    fontFamily = ManropeFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = metrics.sp(24f),
                    lineHeight = metrics.sp(29f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(
                    onClick = {
                        onNotificationsClicked()
                        showNotificationHistory = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier = Modifier.size(30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = tr(language, "Уведомления", "Notifications"),
                            tint = AccountTextPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                        if (state.hasUnreadAppNotifications) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(7.dp)
                                    .background(AccountError, CircleShape),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(metrics.dp(20f)))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(metrics.dp(124f))
                        .clip(CircleShape)
                        .clickable(
                            onClick = if (accountActionsEnabled) onPersonalizationClicked else onAccessDenied,
                        ),
                ) {
                    Avatar(
                        avatarUri = presentation.avatarUri,
                        size = metrics.dp(124f),
                    )
                }
                Spacer(Modifier.width(metrics.dp(16f)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = presentation.username,
                        color = AccountTextPrimary,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Medium,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = metrics.sp(10f),
                            maxFontSize = metrics.sp(24f),
                            stepSize = metrics.sp(1f),
                        ),
                        lineHeight = metrics.sp(29f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = presentation.email,
                        color = AccountTextSecondary,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Medium,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = metrics.sp(10f),
                            maxFontSize = metrics.sp(16f),
                            stepSize = metrics.sp(1f),
                        ),
                        lineHeight = metrics.sp(20f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(25.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(20f)),
            ) {
                AccountPlanCard(
                    presentation = presentation,
                    currentPlanLabel = tr(language, "Текущий тариф", "Current plan"),
                    validUntilLabel = tr(language, "действует до", "valid until"),
                    scale = scale,
                    backdrop = sharedBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .width(metrics.dp(177f))
                        .height(metrics.dp(157f)),
                )
                AccountTrafficCard(
                    presentation = presentation,
                    showExplanation = showTrafficExplanation,
                    scale = scale,
                    backdrop = sharedBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .width(metrics.dp(175f))
                        .height(metrics.dp(157f)),
                    onClick = {
                        showTrafficExplanation = !showTrafficExplanation
                    },
                )
            }
            Spacer(Modifier.height(metrics.dp(20f)))
            val rows = listOf(
                AccountRowSpec(
                    title = tr(language, "Тарифы", "Plans"),
                    iconRes = R.raw.account_plans,
                    iconWidth = 26,
                    iconHeight = 20,
                    enabled = accountActionsEnabled,
                    onClick = onPlansClicked,
                ),
                AccountRowSpec(
                    title = tr(language, "История платежей", "Payment history"),
                    iconRes = R.raw.account_history,
                    iconWidth = 18,
                    iconHeight = 22,
                    enabled = accountActionsEnabled,
                    onClick = { onPaymentHistoryClicked(); showPaymentHistory = true },
                ),
                AccountRowSpec(
                    title = tr(language, "Промокод", "Promo code"),
                    iconRes = R.raw.account_promo,
                    iconWidth = 26,
                    iconHeight = 17,
                    enabled = accountActionsEnabled,
                    onClick = { onClearPromoCode(); promoCode = ""; showPromoDialog = true },
                ),
                AccountRowSpec(
                    title = tr(language, "Устройства", "Devices"),
                    iconRes = R.drawable.settings_devices_icon,
                    iconWidth = 25,
                    iconHeight = 25,
                    enabled = accountActionsEnabled,
                    onClick = onDevicesClicked,
                ),
                AccountRowSpec(
                    title = tr(language, "Поддержка", "Support"),
                    iconRes = R.raw.account_support,
                    iconWidth = 22,
                    iconHeight = 24,
                    onClick = onSupportClicked,
                ),
                AccountRowSpec(
                    title = tr(language, "Сменить пароль/e-mail", "Change password/e-mail"),
                    iconRes = R.raw.account_security,
                    iconWidth = 22,
                    iconHeight = 25,
                    enabled = accountActionsEnabled,
                    onClick = onSecurityClicked,
                ),
                AccountRowSpec(
                    title = tr(language, "Удалить аккаунт", "Delete account"),
                    iconRes = R.raw.account_delete_icon,
                    iconWidth = 22,
                    iconHeight = 23,
                    enabled = accountActionsEnabled,
                    isDanger = true,
                    onClick = onDeleteAccountClicked,
                ),
            )
            AccountActionsPanel(
                rows = rows,
                onAccessDenied = onAccessDenied,
                scale = scale,
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                modifier = Modifier
                    .width(metrics.dp(370f))
                    .height(metrics.dp(383f)),
            )
        }

        state.paymentCheckout.promoMessage?.let { message ->
            Text(message, color = AccountTextPrimary, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
        }

        if (showPaymentHistory) {
            AccountPaymentHistory(state, scale, sharedBackdrop, liveGlassEnabled, onPaymentHistoryClicked) {
                showPaymentHistory = false
            }
        }

        if (showPromoDialog) {
            AccountPromoDialog(
                language = language,
                value = promoCode,
                scale = scale,
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onValueChanged = { promoCode = it },
                onDismiss = { showPromoDialog = false },
                onConfirm = { onApplyPromoCode(promoCode) },
                error = state.paymentCheckout.promoError,
                isLoading = state.paymentCheckout.promoBusy,
            )
        }

        if (showNotificationHistory) {
            AccountNotificationHistory(
                notifications = state.appNotificationHistory,
                language = language,
                scale = scale,
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onNotificationDeleted = onNotificationDeleted,
                onDismiss = { showNotificationHistory = false },
            )
        }

        (state.dialog as? AppDialog.DeleteAccount)?.let { dialog ->
            val defaultMessage = tr(
                language,
                "Аккаунт, устройства, подписки и история будут удалены без возможности восстановления.",
                "Your account, devices, subscriptions and history will be permanently deleted.",
            )
            SettingsConfirmDialog(
                title = tr(language, "Удалить аккаунт?", "Delete account?"),
                message = dialog.error ?: if (dialog.isDeleting) {
                    tr(language, "Удаляем аккаунт…", "Deleting account…")
                } else {
                    defaultMessage
                },
                dismissText = tr(language, "Отмена", "Cancel"),
                confirmText = if (dialog.isDeleting) {
                    tr(language, "Удаление…", "Deleting…")
                } else {
                    tr(language, "Удалить", "Delete")
                },
                confirmIsDanger = true,
                confirmHoldDurationMillis = ACCOUNT_DELETE_HOLD_MILLIS,
                scale = scale,
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onDismiss = if (dialog.isDeleting) ({}) else onDismissDialog,
                onConfirm = if (dialog.isDeleting) ({}) else onConfirmDialog,
            )
        }
    }
}

@Composable
private fun AccountPlanCard(
    presentation: AccountPresentation,
    currentPlanLabel: String,
    validUntilLabel: String,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(accountDp(15f, scale))
    Column(
        modifier = modifier
            .accountGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = scale,
            )
            .padding(accountDp(20f, scale)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = currentPlanLabel,
                color = AccountTextSecondary,
                fontFamily = ManropeFontFamily,
                fontSize = accountSp(14f, scale),
                lineHeight = accountSp(17f, scale),
            )
            AccountPlanTitle(
                presentation = presentation,
                scale = scale,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FigmaSvgAsset(
                resId = R.raw.account_calendar,
                viewportWidth = 15,
                viewportHeight = 15,
                modifier = Modifier.size(accountDp(22f, scale)),
            )
            Spacer(Modifier.width(accountDp(7f, scale)))
            Column {
                Text(
                    text = validUntilLabel,
                    color = AccountTextSecondary,
                    fontFamily = ManropeFontFamily,
                    fontSize = accountSp(10f, scale),
                    lineHeight = accountSp(12f, scale),
                )
                Text(
                    text = presentation.expirationDateLabel,
                    color = AccountTextPrimary,
                    fontFamily = ManropeFontFamily,
                    fontSize = accountSp(14f, scale),
                    lineHeight = accountSp(18f, scale),
                )
            }
        }
    }
}

@Composable
private fun AccountPlanTitle(
    presentation: AccountPresentation,
    scale: Float,
) {
    val density = LocalDensity.current
    val planColor = Color(presentation.planColorArgb)
    val titleStyle = if (presentation.planTitleStyle != AccountPlanTitleStyle.BackendColor) {
        val transition = rememberInfiniteTransition(label = "accountPremiumTitleShimmer")
        val shimmerPosition by transition.animateFloat(
            initialValue = -0.45f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 2800
                    -0.45f at 0
                    -0.45f at 350 using LinearEasing
                    1.45f at 2050
                    1.45f at 2800
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "accountPremiumTitleShimmerPosition",
        )
        TextStyle(
            brush = remember(shimmerPosition, presentation.planColorArgb, presentation.planTitleStyle) {
                AccountPlanTitleShimmerBrush(
                    position = shimmerPosition,
                    baseColor = planColor,
                    style = presentation.planTitleStyle,
                )
            },
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.18f),
                offset = Offset(0f, with(density) { accountDp(0.6f, scale).toPx() }),
                blurRadius = with(density) { accountDp(1.2f, scale).toPx() },
            ),
        )
    } else {
        TextStyle(color = planColor)
    }
    Text(
        text = presentation.planTitle,
        style = titleStyle,
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = accountSp(27f, scale),
        lineHeight = accountSp(32f, scale),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AccountTrafficCard(
    presentation: AccountPresentation,
    showExplanation: Boolean,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(accountDp(15f, scale))
    val trafficContentBackdrop = rememberLayerBackdrop()
    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (liveGlassEnabled) {
                        Modifier.layerBackdrop(trafficContentBackdrop)
                    } else {
                        Modifier
                    },
                )
                .accountGlassSurface(
                    shape = shape,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    scale = scale,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(accountDp(127f, scale)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = accountDp(12f, scale).toPx()
                    drawCircle(
                        color = AccountTrafficTrack,
                        radius = size.minDimension / 2f - stroke / 2f,
                        style = Stroke(width = stroke),
                    )
                    inset(stroke / 2f) {
                        drawArc(
                            color = Color(presentation.planColorArgb),
                            startAngle = -90f,
                            sweepAngle = 360f * presentation.remainingFraction,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${presentation.remainingPercent}%",
                        color = AccountTextPrimary,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = accountSp(24f, scale),
                        lineHeight = accountSp(29f, scale),
                    )
                    Text(
                        text = presentation.usageLabel,
                        color = AccountTextSecondary,
                        fontFamily = ManropeFontFamily,
                        fontSize = accountSp(10f, scale),
                        lineHeight = accountSp(12f, scale),
                        maxLines = 1,
                    )
                }
            }
        }
        if (showExplanation) {
            Column(
                modifier = Modifier
                    .width(accountDp(147f, scale))
                    .height(accountDp(91f, scale))
                    .nokiSettingsPanelGlassSurface(
                        shape = RoundedCornerShape(accountDp(15f, scale)),
                        backdrop = if (liveGlassEnabled) trafficContentBackdrop else backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = scale,
                        elevationDp = 8f,
                        shadowAlpha = 0.25f,
                        blurRadiusDp = 28f,
                        lensRadiusDp = 6f,
                        lensRefractionDp = 7f,
                        chromaticAberration = true,
                        surfaceColor = Color(0xFF0D1B2A).copy(alpha = 0.80f),
                    )
                    .padding(
                        horizontal = accountDp(12f, scale),
                        vertical = accountDp(14f, scale),
                    ),
                verticalArrangement = Arrangement.spacedBy(
                    accountDp(6f, scale),
                    Alignment.CenterVertically,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = presentation.remainingExplanation.replace(": ", ":\n"),
                    color = AccountTextPrimary,
                    fontFamily = ManropeFontFamily,
                    fontSize = accountSp(10f, scale),
                    lineHeight = accountSp(12f, scale),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = presentation.usedExplanation.replace(": ", ":\n"),
                    color = AccountTextPrimary,
                    fontFamily = ManropeFontFamily,
                    fontSize = accountSp(10f, scale),
                    lineHeight = accountSp(12f, scale),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AccountActionsPanel(
    rows: List<AccountRowSpec>,
    onAccessDenied: () -> Unit,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(accountDp(15f, scale))
    val actionScope = rememberCoroutineScope()
    var actionPending by remember { mutableStateOf(false) }
    fun runActionAfterFeedback(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        actionScope.launch {
            delay(ACCOUNT_ACTION_FEEDBACK_DELAY_MS)
            try {
                action()
            } finally {
                actionPending = false
            }
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .accountGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = scale,
            ),
    ) {
        if (rows.isNotEmpty()) {
            val edgeInset = accountDp(4f, scale)
            val separatorHeight = accountDp(1f, scale)
            val separatorsHeight = separatorHeight * (rows.size - 1).toFloat()
            val contentRowHeight =
                (maxHeight - edgeInset * 2f - separatorsHeight) / rows.size.toFloat()
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rows.forEachIndexed { index, row ->
                    val isFirst = index == 0
                    val isLast = index == rows.lastIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(contentRowHeight + if (isFirst || isLast) edgeInset else 0.dp)
                            .clickable(
                                onClick = { runActionAfterFeedback { row.performClick(onAccessDenied) } },
                            )
                            .padding(
                                start = accountDp(40f, scale),
                                top = if (isFirst) edgeInset else 0.dp,
                                end = accountDp(40f, scale),
                                bottom = if (isLast) edgeInset else 0.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.width(accountDp(26f, scale)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (LocalResources.current.getResourceTypeName(row.iconRes) == "drawable") {
                                Icon(
                                    painter = painterResource(row.iconRes),
                                    contentDescription = null,
                                    tint = AccountTextPrimary,
                                    modifier = Modifier.size(
                                        accountDp(row.iconWidth.toFloat(), scale),
                                        accountDp(row.iconHeight.toFloat(), scale),
                                    ),
                                )
                            } else FigmaSvgAsset(
                                resId = row.iconRes,
                                viewportWidth = row.iconWidth,
                                viewportHeight = row.iconHeight,
                                modifier = Modifier
                                    .width(accountDp(row.iconWidth.toFloat(), scale))
                                    .height(accountDp(row.iconHeight.toFloat(), scale)),
                            )
                        }
                        Spacer(Modifier.width(accountDp(30f, scale)))
                        Text(
                            text = row.title,
                            color = if (row.isDanger) AccountError else AccountTextPrimary,
                            fontFamily = ManropeFontFamily,
                            fontSize = accountSp(14f, scale),
                            lineHeight = accountSp(19f, scale),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (!row.isDanger) {
                            FigmaSvgAsset(
                                resId = R.raw.account_arrow,
                                viewportWidth = 6,
                                viewportHeight = 10,
                                modifier = Modifier
                                    .width(accountDp(6f, scale))
                                    .height(accountDp(10f, scale)),
                            )
                        }
                    }
                    if (!isLast) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(separatorHeight)
                                .background(AccountStroke.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}

internal data class AccountNotificationHistoryState(
    val selectedNotificationId: String? = null,
) {
    fun open(notificationId: String): AccountNotificationHistoryState =
        copy(selectedNotificationId = notificationId)

    fun closeDetail(): AccountNotificationHistoryState =
        copy(selectedNotificationId = null)

    fun selectedNotification(
        notifications: List<BackendAppNotification>,
    ): BackendAppNotification? = selectedNotificationId?.let { selectedId ->
        notifications.firstOrNull { notification -> notification.id == selectedId }
    }
}

@Composable
private fun AccountNotificationHistory(
    notifications: List<BackendAppNotification>,
    language: com.noki.vpn.data.AppLanguage,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onNotificationDeleted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val outsideInteraction = remember { MutableInteractionSource() }
    val contentInteraction = remember { MutableInteractionSource() }
    var selectedNotificationId by rememberSaveable { mutableStateOf<String?>(null) }
    val historyState = AccountNotificationHistoryState(selectedNotificationId)
    val selectedNotification = historyState.selectedNotification(notifications)
    val closeDetail = {
        selectedNotificationId = historyState.closeDetail().selectedNotificationId
    }

    BackHandler(enabled = selectedNotification != null, onBack = closeDetail)
    Dialog(
        onDismissRequest = {
            if (selectedNotification != null) closeDetail() else onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = outsideInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(settingsDp(360f, scale))
                    .heightIn(max = settingsDp(560f, scale))
                    .nokiSettingsPanelGlassSurface(
                        shape = RoundedCornerShape(settingsDp(24f, scale)),
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = scale,
                        surfaceColor = SettingsBgLighter.copy(alpha = 0.94f),
                        blurAndLensEnabled = false,
                    )
                    .clickable(
                        interactionSource = contentInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(settingsDp(20f, scale)),
                verticalArrangement = Arrangement.spacedBy(settingsDp(16f, scale)),
            ) {
                if (selectedNotification != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(settingsDp(8f, scale)),
                    ) {
                        IconButton(
                            onClick = closeDetail,
                            modifier = Modifier.size(settingsDp(32f, scale)),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = tr(
                                    language,
                                    "Назад к уведомлениям",
                                    "Back to notifications",
                                ),
                                tint = AccountTextPrimary,
                                modifier = Modifier.size(settingsDp(20f, scale)),
                            )
                        }
                        SettingsText(
                            text = tr(language, "Уведомление", "Notification"),
                            color = AccountTextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22f,
                            lineHeight = 27f,
                            letterSpacing = 0f,
                            scale = scale,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AccountNotificationDetail(
                        notification = selectedNotification,
                        scale = scale,
                    )
                } else {
                    SettingsText(
                        text = tr(language, "Уведомления", "Notifications"),
                        color = AccountTextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 22f,
                        lineHeight = 27f,
                        letterSpacing = 0f,
                        scale = scale,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (notifications.isEmpty()) {
                        SettingsText(
                            text = tr(
                                language,
                                "Пока тут пусто",
                                "Nothing here yet",
                            ),
                            color = AccountTextSecondary,
                            fontSize = 14f,
                            lineHeight = 20f,
                            letterSpacing = 0f,
                            scale = scale,
                            textAlign = TextAlign.Start,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(settingsDp(12f, scale)),
                        ) {
                            items(notifications, key = BackendAppNotification::id) { notification ->
                                val shape = RoundedCornerShape(settingsDp(16f, scale))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .background(
                                            SettingsBgSoft.copy(
                                                alpha = if (liveGlassEnabled) 0.48f else 1f,
                                            ),
                                        )
                                        .clickable {
                                            selectedNotificationId = historyState
                                                .open(notification.id)
                                                .selectedNotificationId
                                        }
                                        .padding(settingsDp(14f, scale)),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = settingsDp(28f, scale)),
                                        verticalArrangement = Arrangement.spacedBy(settingsDp(4f, scale)),
                                    ) {
                                        SettingsText(
                                            text = notification.title,
                                            color = AccountTextPrimary,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14f,
                                            lineHeight = 18f,
                                            letterSpacing = 0f,
                                            scale = scale,
                                            textAlign = TextAlign.Start,
                                            maxLines = 2,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        SettingsText(
                                            text = notification.message,
                                            color = AccountTextSecondary,
                                            fontSize = 13f,
                                            lineHeight = 18f,
                                            letterSpacing = 0f,
                                            scale = scale,
                                            textAlign = TextAlign.Start,
                                            maxLines = 4,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        notification.createdAt.takeIf(String::isNotBlank)?.let { createdAt ->
                                            SettingsText(
                                                text = createdAt.replace('T', ' ').take(16),
                                                color = AccountTextSecondary.copy(alpha = 0.72f),
                                                fontSize = 10f,
                                                lineHeight = 13f,
                                                letterSpacing = 0f,
                                                scale = scale,
                                                textAlign = TextAlign.Start,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onNotificationDeleted(notification.id) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(settingsDp(28f, scale)),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = tr(
                                                language,
                                                "Удалить уведомление",
                                                "Delete notification",
                                            ),
                                            tint = AccountTextSecondary,
                                            modifier = Modifier.size(settingsDp(16f, scale)),
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
}

@Composable
private fun AccountNotificationDetail(
    notification: BackendAppNotification,
    scale: Float,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(settingsDp(10f, scale)),
    ) {
        item {
            SettingsText(
                text = notification.title,
                color = AccountTextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 17f,
                lineHeight = 22f,
                letterSpacing = 0f,
                scale = scale,
                textAlign = TextAlign.Start,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SettingsText(
                text = notification.message,
                color = AccountTextSecondary,
                fontSize = 14f,
                lineHeight = 20f,
                letterSpacing = 0f,
                scale = scale,
                textAlign = TextAlign.Start,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        notification.createdAt.takeIf(String::isNotBlank)?.let { createdAt ->
            item {
                SettingsText(
                    text = createdAt.replace('T', ' ').take(16),
                    color = AccountTextSecondary.copy(alpha = 0.72f),
                    fontSize = 10f,
                    lineHeight = 13f,
                    letterSpacing = 0f,
                    scale = scale,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AccountPromoDialog(
    language: com.noki.vpn.data.AppLanguage,
    value: String,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onValueChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    error: String?,
    isLoading: Boolean,
) {
    SettingsCompactInputDialog(
        value = value,
        onValueChange = onValueChanged,
        placeholder = tr(language, "Введите промокод", "Enter promo code"),
        dismissText = tr(language, "Отменить", "Cancel"),
        confirmText = tr(language, "Применить", "Apply"),
        scale = scale,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        error = error,
        isLoading = isLoading,
        maxLength = 32,
    )
}

private fun accountDp(value: Float, scale: Float): Dp = (value * scale).dp

private fun accountSp(value: Float, scale: Float): TextUnit = (value * scale).sp

private fun Modifier.accountGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
): Modifier = nokiGlassSurface(
    shape = shape,
    backdrop = backdrop,
    liveGlassEnabled = liveGlassEnabled,
    scale = scale,
    surfaceColor = AccountTextSecondary.copy(alpha = 0.05f),
    simpleSurfaceColor = SettingsBgSoft,
    blurAndLensEnabled = false,
)
