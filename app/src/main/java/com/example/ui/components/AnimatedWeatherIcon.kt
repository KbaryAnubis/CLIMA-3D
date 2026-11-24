package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.repeatCount
import coil.size.Precision
import com.example.media.AnimatedWeatherWebp
import com.example.media.WeatherCondition
import com.example.media.WeatherIconSize
import com.example.media.WeatherImageLoader

internal fun buildWeatherWebpRequest(
    context: android.content.Context,
    @DrawableRes drawableRes: Int,
    targetSizePx: Int,
): ImageRequest {
    val clampedPx = targetSizePx.coerceAtLeast(1)
    return ImageRequest.Builder(context)
        .data(drawableRes)
        .size(clampedPx, clampedPx)
        .precision(Precision.INEXACT)
        .repeatCount(-1) // Infinite loop matching V53 animated WebP behavior
        .allowHardware(false) // Required for AnimatedImageDrawable compatibility across GPUs
        .build()
}

/**
 * Displays a V53 3D weather icon (animated VP8X WebP or fallback VectorDrawable)
 * strictly obeying the V47-V50 rendering rules:
 * - Zero artificial container animation (no translation, bobbing, rotation, or scale pulse).
 * - Fixed 1:1 aspect ratio, ContentScale.Fit, and transparent background.
 * - Preserves internal frame-by-frame WebP animation and alpha transparency.
 */
@Composable
fun AnimatedWeatherIcon(
    condition: WeatherCondition,
    modifier: Modifier = Modifier,
    size: Dp = WeatherIconSize.Hero,
    contentDescription: String? = stringResource(condition.labelRes),
) {
    val webp = condition.animatedWebp
    if (webp != null) {
        AnimatedWeatherWebpImage(
            webp = webp,
            size = size,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else if (condition.fallbackVectorRes != null) {
        FallbackWeatherVector(
            drawableRes = condition.fallbackVectorRes,
            size = size,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

@Composable
fun AnimatedWeatherWebpImage(
    webp: AnimatedWeatherWebp,
    modifier: Modifier = Modifier,
    size: Dp = WeatherIconSize.Hero,
    contentDescription: String? = stringResource(webp.labelRes),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetSizePx = with(density) { size.roundToPx().coerceAtLeast(1) }
    val imageLoader = remember(context) { WeatherImageLoader.get(context) }
    val request =
        remember(context, webp.drawableRes, targetSizePx) {
            buildWeatherWebpRequest(
                context = context,
                drawableRes = webp.drawableRes,
                targetSizePx = targetSizePx,
            )
        }

    Box(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FallbackWeatherVector(
    @DrawableRes drawableRes: Int,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
