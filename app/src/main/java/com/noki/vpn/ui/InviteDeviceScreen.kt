package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.AppDestination
import com.noki.vpn.AppUiState
import com.noki.vpn.InviteCodeFormatter
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private enum class InviteInputMode {
    Code,
    Qr,
}

@Composable
fun InviteDeviceScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    liveGlassEnabled: Boolean = true,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val language = state.personalizationSettings.language
    val form = state.inviteDeviceForm
    var mode by rememberSaveable { mutableStateOf(InviteInputMode.Code) }
    val authBackdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07111A)),
    ) {
        Image(
            painter = painterResource(R.drawable.login_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (liveGlassEnabled) Modifier.layerBackdrop(authBackdrop) else Modifier,
                ),
            contentScale = ContentScale.FillBounds,
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = metrics.contentStart),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(14f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = tr(language, "Подключить устройство", "Connect device"),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = metrics.sp(24f),
                lineHeight = metrics.sp(29f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = tr(
                    language,
                    "Введите код приглашения или отсканируйте QR-код от владельца подписки",
                    "Enter an invite code or scan the subscription owner's QR code",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = metrics.sp(14f),
                lineHeight = metrics.sp(20f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(metrics.dp(4f)))
            InviteModeSelector(
                mode = mode,
                enabled = !form.isLoading,
                language = language,
                liveGlassEnabled = liveGlassEnabled,
                onModeChanged = { mode = it },
            )

            if (mode == InviteInputMode.Code) {
                InviteCodeInputField(
                    value = form.inviteCode,
                    enabled = !form.isLoading,
                    onValueChange = viewModel::updateInviteDeviceCode,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = tr(language, "Код приглашения", "Invite code"),
                )
                AuthPrimaryButton(
                    text = tr(language, "Подключить", "Connect"),
                    loading = form.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.dp(56f)),
                    backdrop = authBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onClick = viewModel::acceptDeviceInvite,
                )
            } else {
                QrInviteHint(language = language)
                AuthPrimaryButton(
                    text = tr(language, "Сканировать QR", "Scan QR"),
                    loading = false,
                    enabled = !form.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.dp(56f)),
                    backdrop = authBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    onClick = { viewModel.openScreen(AppDestination.INVITE_QR_SCANNER) },
                )
            }

            AuthSecondaryButton(
                text = tr(language, "Назад ко входу", "Back"),
                enabled = !form.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(42f)),
                backdrop = authBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = { viewModel.openScreen(AppDestination.LOGIN) },
            )
            if (!form.error.isNullOrBlank()) {
                Text(
                    text = form.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                fontSize = metrics.sp(12f),
                lineHeight = metrics.sp(16f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun InviteCodeInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(20f))
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }

    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    BasicTextField(
        value = textFieldValue,
        enabled = enabled,
        onValueChange = { editedValue ->
            if (editedValue.text == textFieldValue.text) {
                textFieldValue = editedValue
                return@BasicTextField
            }

            val previousText = textFieldValue.text
            val formattedText = InviteCodeFormatter.format(editedValue.text)
            fun mapSelectionOffset(offset: Int): Int {
                val rawPrefix = editedValue.text.take(offset)
                var rawValidCharacterCount = 0
                var fifthValidCharacterIndex = -1
                rawPrefix.forEachIndexed { index, character ->
                    val isValidCharacter = character in 'A'..'Z' ||
                        character in 'a'..'z' ||
                        character in '0'..'9'
                    if (isValidCharacter) {
                        rawValidCharacterCount += 1
                        if (rawValidCharacterCount == 5) {
                            fifthValidCharacterIndex = index
                        }
                    }
                }
                val validCharacterCount = rawValidCharacterCount.coerceAtMost(10)
                val isAfterFormatHyphen = validCharacterCount > 5 ||
                    (
                        validCharacterCount == 5 &&
                            formattedText.length > 6 &&
                            fifthValidCharacterIndex < rawPrefix.lastIndex
                    )
                return (
                    validCharacterCount + if (isAfterFormatHyphen) 1 else 0
                ).coerceAtMost(formattedText.length)
            }

            textFieldValue = TextFieldValue(
                text = formattedText,
                selection = TextRange(
                    start = mapSelectionOffset(editedValue.selection.start),
                    end = mapSelectionOffset(editedValue.selection.end),
                ),
            )
            if (formattedText != previousText) {
                onValueChange(formattedText)
            }
        },
        modifier = modifier
            .height(metrics.dp(56f))
            .background(Color(0xFF0D1B2A), shape)
            .border(1.dp, Color(0xFF29404E), shape)
            .padding(horizontal = metrics.dp(19f)),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.Characters,
        ),
        cursorBrush = SolidColor(Color(0xFF42D6A4)),
        textStyle = TextStyle(
            color = Color(0xFFF4FBFF),
            fontSize = metrics.sp(14f),
            lineHeight = metrics.sp(12f),
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            letterSpacing = metrics.sp(0.14f),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF6E8797),
                        fontSize = metrics.sp(14f),
                        lineHeight = metrics.sp(12f),
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = metrics.sp(0.14f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun InviteModeSelector(
    mode: InviteInputMode,
    enabled: Boolean,
    language: com.noki.vpn.data.AppLanguage,
    liveGlassEnabled: Boolean,
    onModeChanged: (InviteInputMode) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    GlassSegmentedControl(
        labelFontSize = metrics.sp(14f),
        labelLineHeight = metrics.sp(17f),
        capsulePadding = metrics.dp(5f),
        labels = listOf(
            tr(language, "Ввести код", "Enter code"),
            tr(language, "QR-код", "QR code"),
        ),
        selectedIndex = when (mode) {
            InviteInputMode.Code -> 0
            InviteInputMode.Qr -> 1
        },
        enabled = enabled,
        onSelectedIndexChanged = { index ->
            onModeChanged(
                when (index) {
                    0 -> InviteInputMode.Code
                    else -> InviteInputMode.Qr
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(60f)),
        activeBlur = 0.dp,
        lensRefractionHeight = metrics.dp(24f),
        lensRefractionAmount = metrics.dp(24f),
        maxPressedScaleX = 1.1f,
        liveGlassEnabled = liveGlassEnabled,
        depthEffectEnabled = true,
    )
}

@Composable
private fun QrInviteHint(language: com.noki.vpn.data.AppLanguage) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(104f))
            .background(Color(0xFF0D1B2A), RoundedCornerShape(metrics.dp(20f)))
            .border(BorderStroke(1.dp, Color(0xFF29404E)), RoundedCornerShape(metrics.dp(20f)))
            .padding(metrics.dp(18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tr(
                language,
                "Наведи камеру на QR-код приглашения. После распознавания устройство подключится автоматически.",
                "Point the camera at the invite QR code. The device will connect automatically after recognition.",
            ),
            color = Color(0xFF9FB6C5),
            style = MaterialTheme.typography.bodyMedium,
                fontSize = metrics.sp(14f),
                lineHeight = metrics.sp(20f),
            textAlign = TextAlign.Center,
        )
    }
}
