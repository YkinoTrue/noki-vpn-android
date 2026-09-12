package com.noki.vpn.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.AvatarBitmapDecoder
import com.noki.vpn.AvatarCropRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
fun AvatarCropScreen(
    sourceUri: String,
    language: AppLanguage,
    isUploading: Boolean,
    message: String?,
    onCancel: () -> Unit,
    onConfirm: (
        previewWidthPx: Float,
        previewHeightPx: Float,
        cropCircleSizePx: Float,
        cropScale: Float,
        cropOffsetX: Float,
        cropOffsetY: Float,
        rotationQuarterTurns: Int,
    ) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val context = LocalContext.current
    val density = LocalDensity.current
    val preview by produceState<AvatarPreviewState>(initialValue = AvatarPreviewState.Loading, sourceUri) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                AvatarBitmapDecoder.decode {
                    context.contentResolver.openInputStream(sourceUri.toUri())
                }.also { decoded = it }
            }
            value = AvatarPreviewState.Ready(checkNotNull(decoded))
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = AvatarPreviewState.Failed
        } finally {
            decoded?.recycle()
        }
    }
    val bitmap = (preview as? AvatarPreviewState.Ready)?.bitmap
    var cropScale by remember(sourceUri) { mutableFloatStateOf(1f) }
    var cropOffset by remember(sourceUri) { mutableStateOf(Offset.Zero) }
    var rotationQuarterTurns by remember(sourceUri) { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = metrics.dp(18f)),
        contentAlignment = Alignment.Center,
    ) {
        val previewWidth = maxWidth
        val availablePreviewHeight = (maxHeight - metrics.dp(230f)).coerceAtLeast(1.dp)
        val previewHeight = when {
            availablePreviewHeight < metrics.dp(360f) -> availablePreviewHeight
            availablePreviewHeight > metrics.dp(590f) -> metrics.dp(590f)
            else -> availablePreviewHeight
        }
        val cropCircleSize = minOf(previewWidth, previewHeight) * 0.78f
        val previewWidthPx = with(density) { previewWidth.toPx() }
        val previewHeightPx = with(density) { previewHeight.toPx() }
        val cropCircleSizePx = with(density) { cropCircleSize.toPx() }
        val crop = AvatarCropRequest(
            sourceUri = sourceUri,
            previewWidthPx = previewWidthPx,
            previewHeightPx = previewHeightPx,
            cropCircleSizePx = cropCircleSizePx,
            cropScale = cropScale,
            cropOffsetX = cropOffset.x,
            cropOffsetY = cropOffset.y,
            rotationQuarterTurns = rotationQuarterTurns,
        )
        val rotate: (Int) -> Unit = { direction ->
            rotationQuarterTurns = Math.floorMod(rotationQuarterTurns + direction, 4)
            cropScale = 1f
            cropOffset = Offset.Zero
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(width = previewWidth, height = previewHeight)
                    .clip(RoundedCornerShape(metrics.dp(2f)))
                    .background(Color.Black)
                    .pointerInput(bitmap, previewWidthPx, previewHeightPx, cropCircleSizePx, rotationQuarterTurns, isUploading) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (isUploading || bitmap == null) return@detectTransformGestures
                            val nextScale = (cropScale * zoom).coerceIn(1f, 5f)
                            val nextOffset = cropOffset + pan
                            cropScale = nextScale
                            val clamped = crop.copy(
                                cropScale = nextScale,
                                cropOffsetX = nextOffset.x,
                                cropOffsetY = nextOffset.y,
                            ).clampOffsets(bitmap.width, bitmap.height)
                            cropOffset = Offset(clamped.cropOffsetX, clamped.cropOffsetY)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image == null) {
                    AvatarCropText(
                        text = if (preview == AvatarPreviewState.Failed) {
                            tr(language, "Не удалось открыть изображение", "Could not open the image")
                        } else {
                            tr(language, "Загрузка...", "Loading...")
                        },
                        color = Color(0xFFB5C6D2),
                        fontSize = 13f,
                        lineHeight = 16f,
                    )
                } else {
                    AvatarCropCanvas(
                        image = image,
                        crop = crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = metrics.dp(12f)),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AvatarCropRotationButton(
                    icon = Icons.AutoMirrored.Filled.RotateLeft,
                    description = tr(language, "Повернуть влево на 90°", "Rotate left 90°"),
                    enabled = bitmap != null && !isUploading,
                    onClick = { rotate(-1) },
                )
                AvatarCropRotationButton(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    description = tr(language, "Повернуть вправо на 90°", "Rotate right 90°"),
                    enabled = bitmap != null && !isUploading,
                    onClick = { rotate(1) },
                )
            }
            Spacer(modifier = Modifier.height(metrics.dp(16f)))
            message?.takeIf { it.isNotBlank() }?.let {
                AvatarCropText(
                    text = it,
                    color = Color(0xFFB5C6D2),
                    fontSize = 12f,
                    lineHeight = 15f,
                    modifier = Modifier.padding(bottom = metrics.dp(12f)),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(14f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCropActionButton(
                    text = tr(language, "Отмена", "Cancel"),
                    enabled = !isUploading,
                    onClick = onCancel,
                )
                AvatarCropActionButton(
                    text = if (isUploading) "..." else tr(language, "Сохранить", "Save"),
                    enabled = !isUploading && bitmap != null,
                    primary = true,
                    onClick = {
                        onConfirm(
                            previewWidthPx,
                            previewHeightPx,
                            cropCircleSizePx,
                            cropScale,
                            cropOffset.x,
                            cropOffset.y,
                            rotationQuarterTurns,
                        )
                    },
                )
            }
        }
    }
}

private sealed interface AvatarPreviewState {
    data object Loading : AvatarPreviewState
    data object Failed : AvatarPreviewState
    data class Ready(val bitmap: Bitmap) : AvatarPreviewState
}

@Composable
private fun AvatarCropCanvas(
    image: Bitmap,
    crop: AvatarCropRequest,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier = modifier) {
        val matrix = crop.imageMatrix(image.width, image.height)
        val cropCircleSizePx = crop.cropCircleSizePx
        val circleRect = Rect(
            left = (size.width - cropCircleSizePx) / 2f,
            top = (size.height - cropCircleSizePx) / 2f,
            right = (size.width + cropCircleSizePx) / 2f,
            bottom = (size.height + cropCircleSizePx) / 2f,
        )
        val circlePath = Path().apply { addOval(circleRect) }

        drawIntoCanvas { canvas ->
            paint.alpha = 92
            canvas.nativeCanvas.drawBitmap(image, matrix, paint)
        }
        clipPath(circlePath) {
            drawIntoCanvas { canvas ->
                paint.alpha = 255
                canvas.nativeCanvas.drawBitmap(image, matrix, paint)
            }
        }
        drawPath(
            path = circlePath,
            color = Color(0xCCF4FBFF),
            style = Stroke(width = metrics.dp(2f).toPx()),
        )
    }
}

@Composable
private fun AvatarCropRotationButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(metrics.dp(48f)).nokiSettingsActionGlassSurface(
            shape = CircleShape,
            backdrop = null,
            liveGlassEnabled = false,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = SettingsTextPrimary.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(metrics.dp(24f)),
        )
    }
}

@Composable
private fun AvatarCropActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val background = if (primary) Color(0xFF75E7C3) else Color(0xFF132635)
    val content = if (primary) Color(0xFF07111A) else Color(0xFFF4FBFF)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(metrics.dp(18f)))
            .background(if (enabled) background else background.copy(alpha = 0.35f))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = metrics.dp(26f), vertical = metrics.dp(14f)),
        contentAlignment = Alignment.Center,
    ) {
        AvatarCropText(
            text = text,
            color = if (enabled) content else content.copy(alpha = 0.5f),
            fontSize = 15f,
            lineHeight = 18f,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AvatarCropText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF4FBFF),
    fontSize: Float,
    lineHeight: Float,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Center,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    Text(
        text = text,
        color = color,
        fontSize = metrics.sp(fontSize),
        lineHeight = metrics.sp(lineHeight),
        fontWeight = fontWeight,
        textAlign = textAlign,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}
