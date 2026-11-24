package com.example

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.maps.MeteorologicalRadarTileProvider
import com.example.maps.OpenStreetMapTileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherIconAndAuditTest {

    @Test
    fun `AndroidManifest preserves app identity and official adaptive launcher icons`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.applicationInfo

        assertEquals("com.aistudio.clima3d.v53krm", BuildConfig.APPLICATION_ID)
        assertEquals("Clima 3D", context.getString(appInfo.labelRes))
        assertEquals(R.mipmap.ic_launcher, appInfo.icon)

        val adaptiveIcon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        val adaptiveRoundIcon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
        val officialForeground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_official_foreground)
        val foregroundLayer = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        val backgroundLayer = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)

        assertTrue("ic_launcher must resolve as AdaptiveIconDrawable on API 26+", adaptiveIcon is AdaptiveIconDrawable)
        assertTrue("ic_launcher_round must resolve as AdaptiveIconDrawable on API 26+", adaptiveRoundIcon is AdaptiveIconDrawable)
        assertNotNull(officialForeground)
        assertNotNull(foregroundLayer)
        assertNotNull(backgroundLayer)
    }

    @Test
    fun `Google Maps SDK and Maps Compose classes are completely absent from runtime classpath`() {
        val googleMapsPresent =
            runCatching { Class.forName("com.google.android.gms.maps.GoogleMap") }.isSuccess
        val mapsComposePresent =
            runCatching { Class.forName("com.google.maps.android.compose.GoogleMapKt") }.isSuccess
        val mapLibrePresent =
            runCatching { Class.forName("org.maplibre.android.MapLibre") }.isSuccess

        assertFalse("Google Maps SDK must not be on classpath", googleMapsPresent)
        assertFalse("Maps Compose must not be on classpath", mapsComposePresent)
        assertTrue("MapLibre Native SDK must be on classpath", mapLibrePresent)
    }

    @Test
    fun `Manifest contains no Google Maps API key metadata and OSM policy is enforced`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metaData = context.applicationInfo.metaData
        val googleMapsKey = metaData?.getString("com.google.android.geo.API_KEY")

        assertTrue("com.google.android.geo.API_KEY must be absent from manifest", googleMapsKey.isNullOrEmpty())
        assertEquals("© OpenStreetMap contributors", OpenStreetMapTileConfig.ATTRIBUTION)
        assertTrue(OpenStreetMapTileConfig.userAgent.contains(BuildConfig.APPLICATION_ID))

        // Verify radar tile provider continues working independently
        val radarProvider =
            MeteorologicalRadarTileProvider("https://tilecache.rainviewer.com/v2/radar/1700000000/256/{z}/{x}/{y}/2/1_1.png")
        assertNotNull(radarProvider.getTileUrl(x = 3, y = 4, zoom = 3))
    }
}
