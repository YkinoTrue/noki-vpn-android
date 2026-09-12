package com.noki.vpn.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.noki.vpn.AppUiState
import com.noki.vpn.R

private val SupportBgBase = Color(0xFF07111A)
private val SupportBgSoft = Color(0xFF132635)
private val SupportTextPrimary = Color(0xFFF4FBFF)
private val SupportAccentSecondary = Color(0xFF8CC8FF)
private val SupportStroke = Color(0xFF29404E)
private val SupportNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun SupportScreen(
    state: AppUiState,
    sharedBackdrop: LayerBackdrop,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    CompositionLocalProvider(LocalTextStyle provides SupportNoFontPaddingTextStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(SupportBgBase) else Modifier)
                .statusBarsPadding(),
        ) {
            val backdrop = sharedBackdrop
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val language = state.personalizationSettings.language

            if (showBackground) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liveGlassEnabled) Modifier.layerBackdrop(backdrop) else Modifier,
                        ),
                ) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.contentStart)
                    .padding(top = metrics.dp(58f), bottom = metrics.dp(150f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SupportText(
                    text = tr(language, "Поддержка", "Support"),
                    fontSize = 24f * metrics.contentScale,
                    lineHeight = 28.8f * metrics.contentScale,
                    letterSpacing = 0f,
                    fontWeight = FontWeight.Normal,
                    color = SupportTextPrimary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .width(metrics.contentWidth)
                        .fillMaxWidth()
                        .padding(horizontal = metrics.dp(18f)),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    SupportContactContent(
                        language = language,
                        metrics = metrics,
                        modifier = Modifier
                            .width(metrics.contentWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportContactContent(
    language: com.noki.vpn.data.AppLanguage,
    metrics: NokiAdaptiveMetrics,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val email = "noki_support@ykino.tech"

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(metrics.dp(20f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildAnnotatedString {
                append(tr(language, "По любым вопросам и предложениям\nпишите в сообщения канала в ", "For any questions and suggestions\nmessage us in "))
                withStyle(SpanStyle(color = SupportAccentSecondary)) {
                    append("Telegram")
                }
            },
            color = SupportTextPrimary,
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = supportSp(18f * metrics.contentScale),
            lineHeight = supportSp(22f * metrics.contentScale),
            textAlign = TextAlign.Center,
            style = SupportNoFontPaddingTextStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        TelegramButton(
            metrics = metrics,
            onClick = {
                openTelegramChannel(context)
            },
        )

        SupportText(
            text = tr(language, "Или на почту", "Or email us"),
            fontSize = 18f * metrics.contentScale,
            lineHeight = 22f * metrics.contentScale,
            letterSpacing = 0f,
            fontWeight = FontWeight.Normal,
            color = SupportTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            SupportText(
                text = email,
                fontSize = 18f * metrics.contentScale,
                lineHeight = 22f * metrics.contentScale,
                letterSpacing = 0f,
                fontWeight = FontWeight.Normal,
                color = SupportAccentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri()))
                    },
            )
        }
    }
}

@Composable
private fun TelegramButton(
    metrics: NokiAdaptiveMetrics,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(metrics.dp(16f))
    Row(
        modifier = Modifier
            .width(metrics.dp(170f))
            .height(metrics.dp(50f))
            .clip(shape)
            .background(SupportBgSoft, shape)
            .border(BorderStroke(1.dp, SupportStroke.copy(alpha = 0.35f)), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(metrics.dp(10f), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.support_telegram),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(metrics.dp(30f)),
        )
        SupportText(
            text = "NokiVpn",
            fontSize = 18f * metrics.contentScale,
            lineHeight = 22f * metrics.contentScale,
            letterSpacing = 0f,
            fontWeight = FontWeight.Normal,
            color = SupportTextPrimary,
            textAlign = TextAlign.Start,
            modifier = Modifier,
        )
    }
}

@Composable
private fun SupportText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    letterSpacing: Float,
    fontWeight: FontWeight,
    color: Color,
    textAlign: TextAlign,
    modifier: Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val density = LocalDensity.current
    Text(
        text = text,
        color = color,
        fontFamily = ManropeFontFamily,
        fontWeight = fontWeight,
        fontSize = (fontSize / density.fontScale).sp,
        lineHeight = (lineHeight / density.fontScale).sp,
        letterSpacing = (letterSpacing / density.fontScale).sp,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = SupportNoFontPaddingTextStyle,
        modifier = modifier,
    )
}

@Composable
private fun supportSp(value: Float) = (value / LocalDensity.current.fontScale).sp

private fun openTelegramChannel(context: Context) {
    val telegramUri = "tg://resolve?domain=nokivpn&direct".toUri()
    val webUri = "https://t.me/nokivpn?direct".toUri()
    val telegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    )

    for (packageName in telegramPackages) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
    }

    val telegramIntent = Intent(Intent.ACTION_VIEW, telegramUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (telegramIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(telegramIntent)
        return
    }

    context.startActivity(
        Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
