package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapGeoJsonBuilder
import com.example.maps.CityMapLibreSection
import com.example.maps.CityMapMarker
import com.example.maps.CityMapUiState
import com.example.maps.LocationPermissionState
import com.example.maps.MeteorologicalRadarTileProvider
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.maps.RadarLayerStatusCard
import com.example.maps.WeatherRadarLayerSpec
import com.example.ui.theme.Clima3DTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapLibreAndRadarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `BuildConfig does not expose MAPS_API_KEY`() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse("MAPS_API_KEY must not exist in BuildConfig", fields.contains("MAPS_API_KEY"))
    }

    @Test
    fun `OpenStreetMap config includes required attribution and compliant User-Agent`() {
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", OpenStreetMapTileConfig.TILE_URL)
        assertEquals("© OpenStreetMap contributors", OpenStreetMapTileConfig.ATTRIBUTION)
        assertTrue(OpenStreetMapTileConfig.userAgent.startsWith("Clima3D-Android/"))
    }

    @Test
    fun `CityMapUiState handles zoom clamping, camera movement, markers, and GPS location`() {
        val initial = CityMapUiState(zoom = 10.0)

        val zoomedIn = initial.zoomIn(2.0)
        assertEquals(12.0, zoomedIn.zoom, 0.001)

        val zoomedOut = zoomedIn.zoomOut(20.0)
        assertEquals(OpenStreetMapTileConfig.MIN_ZOOM, zoomedOut.zoom, 0.001)

        val rio = CityMapCoordinate(-22.9068, -43.1729)
        val moved = initial.moveCamera(rio, 14.0)
        assertEquals(-22.9068, moved.cameraCenter.latitude, 0.0001)
        assertEquals(-43.1729, moved.cameraCenter.longitude, 0.0001)
        assertEquals(14.0, moved.zoom, 0.001)

        val marked = moved.selectMarkerAt(rio, label = "Rio de Janeiro")
        assertEquals("Rio de Janeiro", marked.selectedMarker.label)
        assertEquals("-22.9068, -43.1729", marked.selectedMarker.coordinate.formatted())

        val located = marked.withCurrentLocation(CityMapCoordinate(-15.7939, -47.8828), label = "Brasília")
        assertEquals(LocationPermissionState.GRANTED, located.permissionState)
        assertNotNull(located.currentUserLocation)
        assertTrue(located.selectedMarker.isCurrentLocation)
        assertEquals(-15.7939, located.cameraCenter.latitude, 0.0001)
    }

    @Test
    fun `GeoJsonBuilder serializes markers in RFC 7946 lon-lat order`() {
        val marker =
            CityMapMarker(
                id = "sp",
                label = "São Paulo",
                coordinate = CityMapCoordinate(latitude = -23.5505, longitude = -46.6333),
            )
        val json = CityMapGeoJsonBuilder.buildFeatureCollection(listOf(marker))
        assertTrue(json.contains("\"type\":\"FeatureCollection\""))
        assertTrue(json.contains("[-46.633300,-23.550500]"))
        assertTrue(json.contains("\"label\":\"São Paulo\""))
    }

    @Test
    fun `Cities map section renders MapLibre + OSM UI and responds to zoom controls`() {
        var latestState = CityMapUiState(zoom = 10.0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                CityMapLibreSection(
                    initialState = latestState,
                    onStateChanged = { latestState = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_osm_attribution").assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_zoom_value").assertTextContains("Zoom 10.0x")

        composeTestRule.onNodeWithTag("city_map_zoom_in").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_zoom_value").assertTextContains("Zoom 11.0x")
        assertEquals(11.0, latestState.zoom, 0.001)

        composeTestRule.onNodeWithTag("city_map_zoom_out").performClick()
        composeTestRule.onNodeWithTag("city_map_zoom_out").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_zoom_value").assertTextContains("Zoom 9.0x")
        assertEquals(9.0, latestState.zoom, 0.001)
    }

    @Test
    fun `Navigating to Cities and Radar tabs displays on-demand MapLibre picker and independent radar layer`() {
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Clima3DNativeAssetsFoundationApp()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_saved_header")
        composeTestRule.onNodeWithTag("cities_open_map_picker_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_map_section").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_my_location").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_picker_close_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_layer_status_card").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `MeteorologicalRadarTileProvider operates independently of any cartographic map SDK`() {
        val template = "https://tilecache.rainviewer.com/v2/radar/1700000000/256/{z}/{x}/{y}/2/1_1.png"
        val provider = MeteorologicalRadarTileProvider(tileUrlTemplate = template)

        assertTrue(provider.isValidTemplate())
        val url = provider.getTileUrl(x = 12, y = 18, zoom = 5)
        assertNotNull(url)
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/1700000000/256/5/12/18/2/1_1.png",
            url.toString(),
        )
        assertNull(provider.getTileUrl(x = -1, y = 18, zoom = 5))

        val layerSpec =
            WeatherRadarLayerSpec(
                tileUrlTemplate = template,
                transparency = 0.35f,
                baseStyle = RadarBaseStyle.PRECIPITATION_MAP,
            )
        assertEquals(0.65f, layerSpec.opacity, 0.001f)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarLayerStatusCard()
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_layer_status_card").assertIsDisplayed()
    }
}
