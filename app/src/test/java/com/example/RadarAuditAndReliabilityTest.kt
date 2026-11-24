package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.CachePolicy
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.RainViewerRadarFrame
import com.example.data.model.SavedLocation
import com.example.data.model.TimeFormatPreference
import com.example.data.model.WeatherError
import com.example.data.model.WeatherResult
import com.example.data.model.WeatherTimeUtils
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.data.remote.OpenMeteoAirQualityApi
import com.example.data.remote.OpenMeteoAirQualityCurrentDto
import com.example.data.remote.OpenMeteoAirQualityResponseDto
import com.example.data.remote.OpenMeteoCurrentDto
import com.example.data.remote.OpenMeteoDailyDto
import com.example.data.remote.OpenMeteoForecastApi
import com.example.data.remote.OpenMeteoForecastResponseDto
import com.example.data.remote.OpenMeteoGeocodingApi
import com.example.data.remote.OpenMeteoGeocodingResponseDto
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapper
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import com.example.data.repository.RadarRepository
import com.example.maps.MapLoadStateMachine
import com.example.maps.MapLibreInitializer
import com.example.maps.MapSurfaceLoadState
import com.example.maps.MeteorologicalRadarTileProvider
import com.example.maps.RadarBaseStyle
import com.example.maps.RainViewerPaletteSpec
import com.example.maps.TileTransportDiagnostics
import com.example.maps.TileTransportFailureKind
import com.example.ui.screens.RadarScreen
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaRadarRampColors
import com.example.ui.theme.ClimaTab
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Etapa 3E — Auditable verification suite covering all 14 mandatory requirements for
 * RainViewer integration, Unix timestamp localization, chronological frame sorting,
 * XYZ tile geometry, playback & index preservation, HTTP cancellation vs transport error
 * classification, cache preservation, and color scheme / legend alignment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RadarAuditAndReliabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val moshi = NetworkClientFactory.createMoshi()

    @Before
    fun resetDiagnostics() {
        TileTransportDiagnostics.reset()
    }

    // 1. Conversão correta de timestamps Unix para data/hora local
    @Test
    fun `test 01 unix timestamp converts accurately to local time across timezones and 12h-24h formats`() {
        // 2026-11-24T15:30:00Z = 1795534200L -> America/Sao_Paulo (UTC-3) = 12:30
        val epochSec = 1795534200L
        val saoPaulo24 = WeatherTimeUtils.formatLocalTime(
            epochMillis = epochSec * 1000L,
            timezoneId = "America/Sao_Paulo",
            utcOffsetSeconds = -10800,
            timeFormat = TimeFormatPreference.H24,
        )
        val utc24 = WeatherTimeUtils.formatLocalTime(
            epochMillis = epochSec * 1000L,
            timezoneId = "UTC",
            utcOffsetSeconds = 0,
            timeFormat = TimeFormatPreference.H24,
        )
        val tokyo24 = WeatherTimeUtils.formatLocalTime(
            epochMillis = epochSec * 1000L,
            timezoneId = "Asia/Tokyo",
            utcOffsetSeconds = 32400,
            timeFormat = TimeFormatPreference.H24,
        )
        val saoPaulo12 = WeatherTimeUtils.formatLocalTime(
            epochMillis = epochSec * 1000L,
            timezoneId = "America/Sao_Paulo",
            utcOffsetSeconds = -10800,
            timeFormat = TimeFormatPreference.H12,
        )

        assertEquals("12:30", saoPaulo24)
        assertEquals("15:30", utc24)
        assertEquals("00:30", tokyo24)
        assertTrue("Expected 12h formatted afternoon time, got $saoPaulo12", saoPaulo12.contains("12:30"))
    }

    // 2. Ordenação cronológica de frames + deduplicação + intervalos irregulares
    @Test
    fun `test 02 rainviewer mapper sorts frames chronologically deduplicates duplicates and handles irregular intervals`() {
        val dto = RainViewerMapsResponseDto(
            version = "2.0",
            generated = 1795536000L,
            host = "https://tilecache.rainviewer.com/",
            radar = RainViewerRadarSectionDto(
                past = listOf(
                    RainViewerFrameDto(time = 1795534800L, path = "/v2/radar/1795534800"),
                    RainViewerFrameDto(time = 1795531200L, path = "/v2/radar/1795531200"),
                    RainViewerFrameDto(time = 1795532400L, path = "/v2/radar/1795532400"), // 20m gap
                    RainViewerFrameDto(time = 1795532400L, path = "/v2/radar/1795532400_dup"), // duplicate timestamp
                    RainViewerFrameDto(time = 1795533300L, path = "/v2/radar/1795533300"), // 15m gap
                ),
                nowcast = null,
            ),
        )

        val snapshot = RainViewerMapper.toDomain(
            dto = dto,
            fetchedAtEpochMillis = 1_000L,
            displayTimezoneId = "UTC",
            timeFormat = TimeFormatPreference.H24,
        )

        assertNotNull(snapshot)
        assertEquals(
            listOf(1795531200L, 1795532400L, 1795533300L, 1795534800L),
            snapshot!!.frames.map { it.timestampSeconds },
        )
        assertEquals("/v2/radar/1795532400", snapshot.frames[1].path)
        assertEquals(4, snapshot.totalPastFramesAvailable)
    }

    // 3. Montagem correta das URLs de tiles + limites XYZ
    @Test
    fun `test 03 rainviewer tile urls and slippy map XYZ bounds assemble accurately`() {
        val urlTemplate = RainViewerMapper.buildTileUrlTemplate(
            host = "https://tilecache.rainviewer.com/",
            path = "/v2/radar/1795534200",
            tileSizePx = RainViewerRadarFrame.DEFAULT_TILE_SIZE_PX,
            colorScheme = RainViewerRadarFrame.UNIVERSAL_BLUE_COLOR_SCHEME,
            options = RainViewerRadarFrame.DEFAULT_OPTIONS,
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/1795534200/512/{z}/{x}/{y}/2/1_1.png",
            urlTemplate,
        )

        val provider = MeteorologicalRadarTileProvider(
            tileUrlTemplate = urlTemplate,
            tileWidthPx = 512,
            tileHeightPx = 512,
        )
        assertTrue(provider.isValidTemplate())

        // Zoom 6 -> valid x,y in 0..63
        val validUrl = provider.getTileUrl(x = 23, y = 36, zoom = 6)
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/1795534200/512/6/23/36/2/1_1.png",
            validUrl.toString(),
        )
        // Out of XYZ slippy-map bounds at zoom 6 (max index is 63)
        assertNull(provider.getTileUrl(x = 64, y = 36, zoom = 6))
        assertNull(provider.getTileUrl(x = 23, y = 64, zoom = 6))
        assertNull(provider.getTileUrl(x = -1, y = 0, zoom = 6))
        assertNull(provider.getTileUrl(x = 0, y = 0, zoom = -1))
    }

    // 4. Ausência de frames inventados (nowcast / timestamps inválidos)
    @Test
    fun `test 04 mapper never invents nowcast frames and rejects invalid non-positive timestamps`() {
        val dto = RainViewerMapsResponseDto(
            version = "2.0",
            generated = 1795534200L,
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadarSectionDto(
                past = listOf(
                    RainViewerFrameDto(time = 0L, path = "/v2/radar/invalid_zero"),
                    RainViewerFrameDto(time = -100L, path = "/v2/radar/invalid_neg"),
                    RainViewerFrameDto(time = 1795533600L, path = "/v2/radar/1795533600"),
                    RainViewerFrameDto(time = 1795534200L, path = "/v2/radar/1795534200"),
                ),
                nowcast = null,
            ),
        )

        val snapshot = RainViewerMapper.toDomain(dto, fetchedAtEpochMillis = 500L)
        assertNotNull(snapshot)
        assertFalse(snapshot!!.nowcastAvailable)
        assertEquals(2, snapshot.frames.size)
        assertEquals(listOf(1795533600L, 1795534200L), snapshot.frames.map { it.timestampSeconds })
    }

    // 5. Resposta vazia da API RainViewer
    @Test
    fun `test 05 empty RainViewer response returns EmptyResponseError cleanly without breaking base OSM`() = runBlocking {
        val db = createInMemoryDb()
        try {
            val api = object : RainViewerApi {
                override suspend fun getWeatherMaps(): RainViewerMapsResponseDto = RainViewerMapsResponseDto(
                    version = "2.0",
                    generated = 1795534200L,
                    host = "https://tilecache.rainviewer.com",
                    radar = RainViewerRadarSectionDto(past = emptyList(), nowcast = null),
                )
            }
            val repo = RadarRepository(
                rainViewerApi = api,
                cacheDao = db.weatherCacheDao(),
                connectivityObserver = alwaysOnlineObserver(),
                moshi = moshi,
            )

            val result = repo.getRadarTimeline(policy = CachePolicy.NetworkFirst)
            assertTrue(result is WeatherResult.Failure)
            assertTrue((result as WeatherResult.Failure).error is WeatherError.EmptyResponseError)
        } finally {
            db.close()
        }
    }

    // 6. JSON inválido ou malformado
    @Test
    fun `test 06 malformed RainViewer JSON produces SerializationError cleanly and preserves existing cache`() = runBlocking {
        val db = createInMemoryDb()
        try {
            var returnBrokenJson = false
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val body =
                        if (returnBrokenJson) {
                            "{broken_json:::"
                        } else {
                            """
                            {
                              "version": "2.0",
                              "generated": 1795534200,
                              "host": "https://tilecache.rainviewer.com",
                              "radar": {
                                "past": [
                                  { "time": 1795533600, "path": "/v2/radar/1795533600" },
                                  { "time": 1795534200, "path": "/v2/radar/1795534200" }
                                ]
                              }
                            }
                            """.trimIndent()
                        }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                .build()
            val api = NetworkClientFactory.createRainViewerApi(client, moshi)

            // Direct SafeApiExecutor check on broken JSON
            returnBrokenJson = true
            val rawOutcome = SafeApiExecutor.execute(RainViewerApi.PROVIDER_NAME) { api.getWeatherMaps() }
            assertTrue(rawOutcome is RemoteCallOutcome.Failure)
            assertTrue((rawOutcome as RemoteCallOutcome.Failure).error is WeatherError.SerializationError)

            // Seed cache then retry with malformed JSON
            returnBrokenJson = false
            val repo = RadarRepository(
                rainViewerApi = api,
                cacheDao = db.weatherCacheDao(),
                connectivityObserver = alwaysOnlineObserver(),
                moshi = moshi,
                clock = { 10_000L },
            )
            val seeded = repo.getRadarTimeline(displayTimezoneId = "UTC", policy = CachePolicy.NetworkFirst)
            assertTrue(seeded is WeatherResult.Success)

            returnBrokenJson = true
            val afterMalformed = repo.getRadarTimeline(displayTimezoneId = "UTC", policy = CachePolicy.NetworkFirst)
            assertTrue(afterMalformed is WeatherResult.Success)
            afterMalformed as WeatherResult.Success
            assertEquals(DataSourceOrigin.LOCAL_CACHE, afterMalformed.origin)
            assertTrue(afterMalformed.staleReason is WeatherError.SerializationError)
            assertEquals(2, afterMalformed.data.frames.size)
        } finally {
            db.close()
        }
    }

    // 7. Correspondência entre frame selecionado e horário exibido
    @Test
    fun `test 07 selected radar frame matches exact displayed time and latest badge in RadarScreen`() {
        val frames = listOf(
            RainViewerRadarFrame(
                timestampSeconds = 1795530600L,
                path = "/v2/radar/1795530600",
                tileUrlTemplate = "https://tilecache.rainviewer.com/v2/radar/1795530600/512/{z}/{x}/{y}/2/1_1.png",
                formattedLocalTime = "11:30",
            ),
            RainViewerRadarFrame(
                timestampSeconds = 1795531200L,
                path = "/v2/radar/1795531200",
                tileUrlTemplate = "https://tilecache.rainviewer.com/v2/radar/1795531200/512/{z}/{x}/{y}/2/1_1.png",
                formattedLocalTime = "11:40",
            ),
            RainViewerRadarFrame(
                timestampSeconds = 1795531800L,
                path = "/v2/radar/1795531800",
                tileUrlTemplate = "https://tilecache.rainviewer.com/v2/radar/1795531800/512/{z}/{x}/{y}/2/1_1.png",
                formattedLocalTime = "11:50",
            ),
        )
        val snapshot = RadarTimelineSnapshot(
            apiVersion = "2.0",
            generatedEpochSeconds = 1795531800L,
            host = "https://tilecache.rainviewer.com",
            frames = frames,
            totalPastFramesAvailable = 3,
            fetchedAtEpochMillis = 50_000L,
        )

        val selectedMidState = ClimaUiState(
            activeTab = ClimaTab.RADAR,
            radarTimeline = snapshot,
            radarOrigin = DataSourceOrigin.LIVE_NETWORK,
            radarSelectedFrameIndex = 1,
        )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = selectedMidState,
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {},
                    onSelectLayer = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSelectFrame = {},
                    onTogglePlay = {},
                    onRefreshRadar = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("radar_frame_counter")
            .performScrollTo()
            .assertTextContains("Quadro 2 de 3 • 11:40", substring = true)
        composeTestRule.onNodeWithTag("radar_frame_badge")
            .performScrollTo()
            .assertTextContains("11:40", substring = false)
        // Must label historical RainViewer frames honestly, never as live real-time radar
        composeTestRule.onNodeWithText("RainViewer (histórico)", substring = true).performScrollTo().assertIsDisplayed()
    }

    // 8. Preservação do índice/frame selecionado durante atualização de frames
    @Test
    fun `test 08 selected frame index and timestamp are preserved across timeline updates when available`() = runBlocking {
        val harness = createVmHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            // Initial frames [t0..t8] at 600s intervals
            val base = 1795530000L
            harness.radarTimestamps = List(9) { i -> base + i * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    (harness.vm.uiState.value.radarTimeline?.frames?.size ?: 0) == 9
            }

            // Pause at frame index 4 (timestamp base + 2400)
            val chosenTs = base + 2400L
            harness.vm.selectRadarFrame(4)
            assertEquals(4, harness.vm.uiState.value.radarSelectedFrameIndex)

            // Refresh where timeline shifts forward by 1 frame ([t1..t9]):
            // chosenTs moved from index 4 -> index 3; ViewModel must preserve the chosen frame
            harness.radarTimestamps = List(9) { i -> base + (i + 1) * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.lastOrNull()?.timestampSeconds == base + 5400L
            }

            assertEquals(3, harness.vm.uiState.value.radarSelectedFrameIndex)
            assertEquals(
                chosenTs,
                harness.vm.uiState.value.radarTimeline?.frameAt(harness.vm.uiState.value.radarSelectedFrameIndex)?.timestampSeconds,
            )
        } finally {
            harness.db.close()
        }
    }

    // 9. Tratamento de índice inválido e expiração do frame selecionado
    @Test
    fun `test 09 out-of-bounds indices clamp safely and expired selected frames fall back cleanly after update`() = runBlocking {
        val harness = createVmHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            val base = 1795530000L
            harness.radarTimestamps = List(5) { i -> base + i * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.size == 5
            }

            // Out-of-bounds frame indices must clamp cleanly to 0..lastIndex
            harness.vm.selectRadarFrame(-99)
            assertEquals(0, harness.vm.uiState.value.radarSelectedFrameIndex)
            harness.vm.selectRadarFrame(999)
            assertEquals(4, harness.vm.uiState.value.radarSelectedFrameIndex)

            // Safe property handles externally constructed out-of-range state too
            val invalidUiState = harness.vm.uiState.value.copy(radarSelectedFrameIndex = 42)
            assertEquals(4, invalidUiState.currentRadarFrameIndexSafe)

            // Select earliest frame (index 0) and refresh with completely newer frames where it expired
            harness.vm.selectRadarFrame(0)
            harness.radarTimestamps = List(5) { i -> base + 10_000L + i * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.firstOrNull()?.timestampSeconds == base + 10_000L
            }

            val indexAfterExpiry = harness.vm.uiState.value.radarSelectedFrameIndex
            assertTrue(indexAfterExpiry in 0..4)
            assertNotNull(harness.vm.uiState.value.radarTimeline?.frameAt(indexAfterExpiry))
        } finally {
            harness.db.close()
        }
    }

    // 10. Classificação correta de cancelamentos HTTP
    @Test
    fun `test 10 HTTP tile cancellations classify as CANCELED and never falsely trigger ConnectionError`() {
        val interceptor = MapLibreInitializer.createTileTransportInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { _ -> throw IOException("Canceled") }
            .build()

        val machine = MapLoadStateMachine("Radar")
        machine.beginAttempt(isOnline = true, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        machine.onStyleLoaded()

        val req = Request.Builder().url("https://tile.openstreetmap.org/6/23/36.png").build()
        repeat(5) {
            runCatching { client.newCall(req).execute().close() }
        }

        val snap = TileTransportDiagnostics.snapshot.value
        assertEquals(5, snap.osmCanceledCount)
        assertEquals(0, snap.osmErrorCount)
        assertEquals(TileTransportFailureKind.NONE, snap.lastOsmFailureKind)
        assertNull(snap.lastOsmError)
        assertEquals(MapSurfaceLoadState.LoadingMap, machine.onTileTransportUpdated(snap, isOnline = true))
    }

    // 11. Diferenciação entre cancelamento, timeout, DNS e TLS
    @Test
    fun `test 11 transport diagnostics strictly differentiate cancellation vs timeout vs DNS vs TLS`() {
        assertEquals(
            TileTransportFailureKind.CANCELED,
            TileTransportDiagnostics.classifyThrowable(IOException("Canceled")).first,
        )
        assertEquals(
            TileTransportFailureKind.CANCELED,
            TileTransportDiagnostics.classifyThrowable(IOException("stream was reset: CANCEL")).first,
        )
        assertEquals(
            TileTransportFailureKind.TIMEOUT,
            TileTransportDiagnostics.classifyThrowable(SocketTimeoutException("connect timed out")).first,
        )
        assertEquals(
            TileTransportFailureKind.DNS_OR_NETWORK,
            TileTransportDiagnostics.classifyThrowable(UnknownHostException("tilecache.rainviewer.com")).first,
        )
        assertEquals(
            TileTransportFailureKind.TLS,
            TileTransportDiagnostics.classifyThrowable(SSLException("SSL peer shut down incorrectly")).first,
        )
    }

    // 12. Preservação de cache em falha de atualização
    @Test
    fun `test 12 radar cache is preserved on refresh failure or offline state with LOCAL_CACHE origin`() = runBlocking {
        val db = createInMemoryDb()
        try {
            val shouldFail = AtomicBoolean(false)
            val online = MutableStateFlow(true)
            val api = object : RainViewerApi {
                override suspend fun getWeatherMaps(): RainViewerMapsResponseDto {
                    if (shouldFail.get()) throw IOException("Remote 503 / socket drop")
                    return RainViewerMapsResponseDto(
                        version = "2.0",
                        generated = 1795534200L,
                        host = "https://tilecache.rainviewer.com",
                        radar = RainViewerRadarSectionDto(
                            past = listOf(
                                RainViewerFrameDto(time = 1795533600L, path = "/v2/radar/1795533600"),
                                RainViewerFrameDto(time = 1795534200L, path = "/v2/radar/1795534200"),
                            ),
                        ),
                    )
                }
            }
            val repo = RadarRepository(
                rainViewerApi = api,
                cacheDao = db.weatherCacheDao(),
                connectivityObserver = object : ConnectivityObserver {
                    override fun isOnline(): Boolean = online.value
                    override fun observeOnlineStatus(): Flow<Boolean> = online
                },
                moshi = moshi,
                clock = { 100_000L },
            )

            // Seed Room cache
            val initial = repo.getRadarTimeline(displayTimezoneId = "UTC", policy = CachePolicy.NetworkFirst)
            assertTrue(initial is WeatherResult.Success)
            assertEquals(DataSourceOrigin.LIVE_NETWORK, (initial as WeatherResult.Success).origin)

            // Failure while online preserves cache with staleReason
            shouldFail.set(true)
            val failedRefresh = repo.getRadarTimeline(displayTimezoneId = "UTC", policy = CachePolicy.NetworkFirst)
            assertTrue(failedRefresh is WeatherResult.Success)
            failedRefresh as WeatherResult.Success
            assertEquals(DataSourceOrigin.LOCAL_CACHE, failedRefresh.origin)
            assertTrue(failedRefresh.staleReason is WeatherError.NetworkUnavailableError)
            assertEquals(listOf(1795533600L, 1795534200L), failedRefresh.data.frames.map { it.timestampSeconds })

            // Offline refresh also preserves cache without throwing away prior frames
            online.value = false
            val offlineRefresh = repo.getRadarTimeline(displayTimezoneId = "UTC", policy = CachePolicy.NetworkFirst)
            assertTrue(offlineRefresh is WeatherResult.Success)
            assertEquals(DataSourceOrigin.LOCAL_CACHE, (offlineRefresh as WeatherResult.Success).origin)
            assertEquals(2, offlineRefresh.data.frames.size)
        } finally {
            db.close()
        }
    }

    // 13. Atualização durante animação ativa
    @Test
    fun `test 13 active radar playback continues uninterrupted and advances chronologically across refresh`() = runBlocking {
        val harness = createVmHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            val base = 1795530000L
            harness.radarTimestamps = List(9) { i -> base + i * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.size == 9
            }

            harness.vm.selectRadarFrame(2)
            harness.vm.toggleRadarPlayback()
            assertTrue(harness.vm.uiState.value.isRadarPlaying)

            // Refresh while playback is actively running
            harness.radarTimestamps = List(9) { i -> base + (i + 1) * 600L }
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.lastOrNull()?.timestampSeconds == base + 5400L
            }

            assertTrue("Playback must stay active after refresh", harness.vm.uiState.value.isRadarPlaying)
            val indexRightAfterRefresh = harness.vm.uiState.value.radarSelectedFrameIndex

            // Advance Robolectric main looper past playback interval (~650ms) and assert chronological progression
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(700L, TimeUnit.MILLISECONDS)
            harness.awaitCondition(timeoutMs = 3_000L) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
                harness.vm.uiState.value.radarSelectedFrameIndex != indexRightAfterRefresh
            }
            assertEquals((indexRightAfterRefresh + 1) % 9, harness.vm.uiState.value.radarSelectedFrameIndex)
            assertTrue(harness.vm.uiState.value.isRadarPlaying)
            harness.vm.stopRadarPlayback()
        } finally {
            harness.db.close()
        }
    }

    // 14. Correspondência entre esquema de cores solicitado e legenda
    @Test
    fun `test 14 requested RainViewer Universal Blue scheme opacity and V53 8-stop intensity legend stay synchronized`() {
        val frame = RainViewerRadarFrame(
            timestampSeconds = 1795534200L,
            path = "/v2/radar/1795534200",
            tileUrlTemplate = RainViewerMapper.buildTileUrlTemplate(
                host = "https://tilecache.rainviewer.com",
                path = "/v2/radar/1795534200",
            ),
            formattedLocalTime = "12:30",
        )
        val layerSpec = frame.toLayerSpec(
            transparency = RainViewerPaletteSpec.DEFAULT_OVERLAY_TRANSPARENCY,
            baseStyle = RadarBaseStyle.PRECIPITATION_MAP,
        )

        assertEquals(RainViewerPaletteSpec.REQUESTED_COLOR_SCHEME_ID, frame.colorScheme)
        assertTrue(frame.tileUrlTemplate.endsWith("/2/1_1.png"))
        assertEquals(RainViewerPaletteSpec.DEFAULT_OVERLAY_OPACITY, layerSpec.opacity, 0.001f)
        assertEquals(8, RainViewerPaletteSpec.RAMP_STOP_COUNT)
        assertEquals(ClimaRadarRampColors, RainViewerPaletteSpec.rampColors)
        assertEquals(listOf("Fraca", "Moderada", "Forte", "Muito forte"), RainViewerPaletteSpec.legendBandsPt)

        // Verify all 4 legend bands are displayed in RadarScreen UI
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = ClimaUiState(activeTab = ClimaTab.RADAR),
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {},
                    onSelectLayer = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSelectFrame = {},
                    onTogglePlay = {},
                    onRefreshRadar = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        RainViewerPaletteSpec.legendBandsPt.forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    private fun createInMemoryDb(): Clima3DDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun alwaysOnlineObserver(): ConnectivityObserver =
        object : ConnectivityObserver {
            private val flow = MutableStateFlow(true)
            override fun isOnline(): Boolean = true
            override fun observeOnlineStatus(): Flow<Boolean> = flow
        }

    private fun saoPauloSearch() =
        CitySearchResult(
            providerId = 3448439L,
            name = "São Paulo",
            admin1 = "São Paulo",
            admin2 = null,
            country = "Brasil",
            countryCode = "BR",
            latitude = -23.5505,
            longitude = -46.6333,
            elevationMeters = 760.0,
            timezoneId = "America/Sao_Paulo",
            population = 12325232,
        )

    private class VmHarness(
        val db: Clima3DDatabase,
        val container: ClimaDataContainer,
        val vm: ClimaMainViewModel,
    ) {
        @Volatile
        var radarTimestamps: List<Long> = emptyList()

        suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(20L)
                }
            }
        }
    }

    private fun createVmHarness(): VmHarness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = createInMemoryDb()
        val prefsFile = File(context.cacheDir, "radar_audit_prefs_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { prefsFile },
            ),
        )
        lateinit var harnessRef: VmHarness

        val forecastApi = object : OpenMeteoForecastApi {
            override suspend fun getForecast(
                latitude: Double,
                longitude: Double,
                current: String,
                hourly: String,
                daily: String,
                forecastDays: Int,
                timezone: String,
                temperatureUnit: String,
                windSpeedUnit: String,
                precipitationUnit: String,
            ): OpenMeteoForecastResponseDto = OpenMeteoForecastResponseDto(
                latitude = latitude,
                longitude = longitude,
                elevation = 760.0,
                utcOffsetSeconds = -10800,
                timezone = "America/Sao_Paulo",
                timezoneAbbreviation = "BRT",
                current = OpenMeteoCurrentDto(
                    time = "2026-11-24T12:00",
                    temperature2m = 25.0,
                    relativeHumidity2m = 68,
                    apparentTemperature = 26.0,
                    isDay = 1,
                    precipitation = 0.0,
                    rain = 0.0,
                    showers = 0.0,
                    snowfall = 0.0,
                    weatherCode = 2,
                    cloudCover = 40,
                    pressureMsl = 1014.0,
                    surfacePressure = 930.0,
                    windSpeed10m = 10.0,
                    windDirection10m = 90,
                    windGusts10m = 18.0,
                ),
                hourly = OpenMeteoHourlyDto(
                    time = List(24) { idx -> "2026-11-24T%02d:00".format(idx) },
                    temperature2m = List(24) { 25.0 },
                    relativeHumidity2m = List(24) { 68 },
                    dewPoint2m = List(24) { 18.0 },
                    apparentTemperature = List(24) { 26.0 },
                    precipitationProbability = List(24) { 10 },
                    precipitation = List(24) { 0.0 },
                    weatherCode = List(24) { 2 },
                    pressureMsl = List(24) { 1014.0 },
                    visibility = List(24) { 10000.0 },
                    windSpeed10m = List(24) { 10.0 },
                    windDirection10m = List(24) { 90 },
                    windGusts10m = List(24) { 18.0 },
                    uvIndex = List(24) { 5.0 },
                    isDay = List(24) { 1 },
                ),
                daily = OpenMeteoDailyDto(
                    time = List(16) { d -> "2026-11-%02d".format(1 + d) },
                    weatherCode = List(16) { 2 },
                    temperature2mMax = List(16) { 28.0 },
                    temperature2mMin = List(16) { 19.0 },
                    apparentTemperatureMax = List(16) { 29.0 },
                    apparentTemperatureMin = List(16) { 19.0 },
                    sunrise = List(16) { "2026-11-24T05:18" },
                    sunset = List(16) { "2026-11-24T18:34" },
                    uvIndexMax = List(16) { 6.0 },
                    precipitationSum = List(16) { 0.0 },
                    precipitationProbabilityMax = List(16) { 15 },
                    windSpeed10mMax = List(16) { 14.0 },
                    windGusts10mMax = List(16) { 22.0 },
                    windDirection10mDominant = List(16) { 90 },
                ),
            )
        }

        val airApi = object : OpenMeteoAirQualityApi {
            override suspend fun getAirQuality(
                latitude: Double,
                longitude: Double,
                current: String,
                timezone: String,
            ): OpenMeteoAirQualityResponseDto = OpenMeteoAirQualityResponseDto(
                latitude = latitude,
                longitude = longitude,
                utcOffsetSeconds = -10800,
                timezone = "America/Sao_Paulo",
                current = OpenMeteoAirQualityCurrentDto(
                    time = "2026-11-24T12:00",
                    europeanAqi = 25,
                    usAqi = 32,
                    pm25 = 8.0,
                    pm10 = 14.0,
                    nitrogenDioxide = 10.0,
                    ozone = 40.0,
                    carbonMonoxide = 180.0,
                    sulphurDioxide = 2.0,
                    dust = 1.2,
                    aerosolOpticalDepth = 0.14,
                    uvIndex = 5.0,
                ),
            )
        }

        val geocodingApi = object : OpenMeteoGeocodingApi {
            override suspend fun searchCities(
                name: String,
                count: Int,
                language: String,
                format: String,
            ): OpenMeteoGeocodingResponseDto = OpenMeteoGeocodingResponseDto(results = emptyList())
        }

        val rainViewerApi = object : RainViewerApi {
            override suspend fun getWeatherMaps(): RainViewerMapsResponseDto {
                val timestamps = harnessRef.radarTimestamps
                return RainViewerMapsResponseDto(
                    version = "2.0",
                    generated = timestamps.lastOrNull() ?: 1795534200L,
                    host = "https://tilecache.rainviewer.com",
                    radar = RainViewerRadarSectionDto(
                        past = timestamps.map { ts -> RainViewerFrameDto(time = ts, path = "/v2/radar/$ts") },
                        nowcast = null,
                    ),
                )
            }
        }

        val container = ClimaDataContainer(
            database = db,
            preferencesRepository = prefsRepo,
            connectivityObserver = alwaysOnlineObserver(),
            moshi = moshi,
            okHttpClient = NetworkClientFactory.createOkHttpClient(),
            forecastApi = forecastApi,
            airQualityApi = airApi,
            geocodingApi = geocodingApi,
            rainViewerApi = rainViewerApi,
        )
        val vm = ClimaMainViewModel(container = container, autoBootstrapOnInit = false)
        harnessRef = VmHarness(db = db, container = container, vm = vm)
        return harnessRef
    }
}
