package com.example.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.media.ClimaUiIcons
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.WeatherGlassSpecs
import java.net.URL

enum class RadarBaseStyle {
    PRECIPITATION_MAP,
    SATELLITE,
}

/**
 * Cartography-independent specification for the meteorological radar overlay layer.
 * Preserved from Etapa 2A and decoupled from any proprietary map SDK.
 */
@Immutable
data class WeatherRadarLayerSpec(
    val tileUrlTemplate: String?,
    val transparency: Float = 0.35f,
    val zIndex: Float = 10f,
    val baseStyle: RadarBaseStyle = RadarBaseStyle.PRECIPITATION_MAP,
    val attribution: String = "Camada meteorológica de radar",
) {
    val opacity: Float
        get() = (1f - transparency).coerceIn(0f, 1f)
}

/**
 * Correspondence metadata tying the RainViewer tile color scheme requested in URLs
 * (`colorScheme = 2`, Universal Blue) and radar overlay opacity (`0.72f`, transparency `0.28f`)
 * to the 8-stop visual intensity scale rendered in Clima 3D V53 (`ClimaRadarRampColors`).
 */
object RainViewerPaletteSpec {
    const val REQUESTED_COLOR_SCHEME_ID: Int = 2
    const val REQUESTED_COLOR_SCHEME_NAME: String = "Universal Blue"
    const val DEFAULT_OVERLAY_TRANSPARENCY: Float = 0.28f
    const val DEFAULT_OVERLAY_OPACITY: Float = 0.72f
    const val RAMP_STOP_COUNT: Int = 8
    val legendBandsPt: List<String> = listOf("Fraca", "Moderada", "Forte", "Muito forte")
    val rampColors: List<androidx.compose.ui.graphics.Color>
        get() = com.example.ui.theme.ClimaRadarRampColors
}

/**
 * Independent meteorological tile URL provider for precipitation and satellite radar frames.
 * Works completely independently of the cartographic map engine (zero Google Maps dependency).
 */
class MeteorologicalRadarTileProvider(
    val tileUrlTemplate: String,
    val tileWidthPx: Int = 256,
    val tileHeightPx: Int = 256,
) {
    fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (!isValidTemplate() || zoom !in 0..30) return null
        val maxCoord = (1L shl zoom) - 1L
        if (x.toLong() !in 0L..maxCoord || y.toLong() !in 0L..maxCoord) return null
        val resolved =
            tileUrlTemplate
                .replace("{z}", zoom.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
        return runCatching { URL(resolved) }.getOrNull()
    }

    fun isValidTemplate(): Boolean {
        val hasHttpScheme =
            tileUrlTemplate.startsWith("https://", ignoreCase = true) ||
                tileUrlTemplate.startsWith("http://", ignoreCase = true)
        return hasHttpScheme &&
            tileUrlTemplate.contains("{z}") &&
            tileUrlTemplate.contains("{x}") &&
            tileUrlTemplate.contains("{y}")
    }
}

@Composable
fun RadarLayerStatusCard(modifier: Modifier = Modifier) {
    val spec = remember { WeatherGlassSpecs.forTab(ClimaTab.RADAR) }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("radar_layer_status_card"),
        shape = RoundedCornerShape(22.dp),
        color = WeatherGlassSpecs.glassContainerColor(spec.cardAlpha),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(ClimaUiIcons.Layers),
                    contentDescription = null,
                    tint = ClimaToastEmerald,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.radar_layer_title),
                    color = ClimaTextOnDark,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.radar_layer_note),
                color = ClimaTextMutedOnDark,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(ClimaUiIcons.Radar),
                    contentDescription = null,
                    tint = ClimaAccentCyan,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Provedor meteorológico desacoplado e ativo sem chave de mapa.",
                    color = ClimaAccentCyan,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
