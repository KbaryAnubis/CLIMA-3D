package com.example.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean

enum class LocationPrecisionLevel {
    NONE,
    APPROXIMATE,
    PRECISE,
}

@Immutable
data class LocationPermissionSnapshot(
    val hasFinePermission: Boolean = false,
    val hasCoarsePermission: Boolean = false,
    val initialPromptShown: Boolean = false,
    val canShowRequestRationale: Boolean = false,
    val isLocationServiceEnabled: Boolean = true,
) {
    val hasAnyPermission: Boolean
        get() = hasFinePermission || hasCoarsePermission

    val precisionLevel: LocationPrecisionLevel
        get() =
            when {
                hasFinePermission -> LocationPrecisionLevel.PRECISE
                hasCoarsePermission -> LocationPrecisionLevel.APPROXIMATE
                else -> LocationPrecisionLevel.NONE
            }

    val mapPermissionState: LocationPermissionState
        get() =
            when {
                hasAnyPermission -> LocationPermissionState.GRANTED
                initialPromptShown -> LocationPermissionState.DENIED
                else -> LocationPermissionState.NOT_REQUESTED
            }

    /**
     * True when the initial prompt has already been shown and Android will no longer show the
     * runtime permission dialog ("Don't ask again" / permanently denied), so the user should be
     * directed to System App Settings.
     */
    val permanentlyDenied: Boolean
        get() = initialPromptShown && !hasAnyPermission && !canShowRequestRationale

    val statusSummaryPt: String
        get() =
            when {
                precisionLevel == LocationPrecisionLevel.PRECISE && isLocationServiceEnabled ->
                    "Concedida (localização precisa)"
                precisionLevel == LocationPrecisionLevel.PRECISE ->
                    "Concedida (precisa) • GPS do aparelho desativado"
                precisionLevel == LocationPrecisionLevel.APPROXIMATE && isLocationServiceEnabled ->
                    "Concedida (localização aproximada)"
                precisionLevel == LocationPrecisionLevel.APPROXIMATE ->
                    "Concedida (aproximada) • Localização do aparelho desativada"
                permanentlyDenied ->
                    "Bloqueada pelo Android • Libere nas configurações do aparelho"
                initialPromptShown ->
                    "Negada • Pesquisa manual e cidades salvas continuam ativas"
                else ->
                    "Não solicitada ainda"
            }
}

sealed interface LocationLookupResult {
    data class Success(
        val coordinate: CityMapCoordinate,
        val precision: LocationPrecisionLevel,
    ) : LocationLookupResult

    data object PermissionDenied : LocationLookupResult

    data object LocationServiceDisabled : LocationLookupResult

    data class UnavailableTemporarily(val reason: String) : LocationLookupResult
}

/**
 * Coordinates runtime Android location permissions (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`),
 * initial first-open prompt eligibility, Android Settings deep-link, precision detection,
 * and safe location lookup without ever inventing coordinates or requesting background location.
 */
object LocationPermissionCoordinator {

    val REQUIRED_PERMISSIONS: Array<String> =
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    fun hasFinePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCoarsePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAnyLocationPermission(context: Context): Boolean =
        hasFinePermission(context) || hasCoarsePermission(context)

    fun isLocationServiceEnabled(context: Context): Boolean =
        runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        }.getOrDefault(false)

    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun canShowAnyLocationRationale(activity: Activity?): Boolean {
        if (activity == null) return false
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) || ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    fun inspect(
        context: Context,
        initialPromptShown: Boolean,
        activity: Activity? = findActivity(context),
    ): LocationPermissionSnapshot {
        val fine = hasFinePermission(context)
        val coarse = hasCoarsePermission(context)
        val hasAny = fine || coarse
        val canRationale = if (!hasAny) canShowAnyLocationRationale(activity) else false
        return LocationPermissionSnapshot(
            hasFinePermission = fine,
            hasCoarsePermission = coarse,
            initialPromptShown = initialPromptShown,
            canShowRequestRationale = canRationale,
            isLocationServiceEnabled = isLocationServiceEnabled(context),
        )
    }

    fun resolvePermissionCallback(
        grantResults: Map<String, Boolean>,
        initialPromptShown: Boolean,
        canShowRationale: Boolean,
        isLocationServiceEnabled: Boolean,
    ): LocationPermissionSnapshot {
        val fine = grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grantResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        return LocationPermissionSnapshot(
            hasFinePermission = fine,
            hasCoarsePermission = coarse,
            initialPromptShown = initialPromptShown,
            canShowRequestRationale = if (fine || coarse) false else canShowRationale,
            isLocationServiceEnabled = isLocationServiceEnabled,
        )
    }

    fun shouldLaunchInitialPrompt(
        preferencesLoaded: Boolean,
        snapshot: LocationPermissionSnapshot,
        launchedInSession: Boolean,
    ): Boolean {
        if (!preferencesLoaded) return false
        if (launchedInSession) return false
        if (snapshot.initialPromptShown) return false
        if (snapshot.hasAnyPermission) return false
        return true
    }

    fun buildAppSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun openAppSettings(context: Context): Boolean =
        runCatching {
            context.startActivity(buildAppSettingsIntent(context))
            true
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun fetchRealLocation(
        context: Context,
        onResult: (LocationLookupResult) -> Unit,
    ) {
        val fine = hasFinePermission(context)
        val coarse = hasCoarsePermission(context)
        val precision =
            when {
                fine -> LocationPrecisionLevel.PRECISE
                coarse -> LocationPrecisionLevel.APPROXIMATE
                else -> LocationPrecisionLevel.NONE
            }

        if (precision == LocationPrecisionLevel.NONE) {
            onResult(LocationLookupResult.PermissionDenied)
            return
        }

        val serviceEnabled = isLocationServiceEnabled(context)
        if (!serviceEnabled) {
            onResult(LocationLookupResult.LocationServiceDisabled)
            return
        }

        val completed = AtomicBoolean(false)
        fun deliverOnce(result: LocationLookupResult) {
            if (completed.compareAndSet(false, true)) {
                onResult(result)
            }
        }

        val fusedPriority =
            if (precision == LocationPrecisionLevel.PRECISE) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }

        val fusedClient = runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
        if (fusedClient == null) {
            fetchPlatformLocation(context, precision, ::deliverOnce)
            return
        }

        fusedClient.lastLocation
            .addOnSuccessListener { lastKnown: Location? ->
                if (lastKnown != null) {
                    deliverOnce(
                        LocationLookupResult.Success(
                            coordinate = CityMapCoordinate(lastKnown.latitude, lastKnown.longitude),
                            precision = precision,
                        ),
                    )
                } else {
                    val tokenSource = CancellationTokenSource()
                    fusedClient
                        .getCurrentLocation(fusedPriority, tokenSource.token)
                        .addOnSuccessListener { current ->
                            if (current != null) {
                                deliverOnce(
                                    LocationLookupResult.Success(
                                        coordinate = CityMapCoordinate(current.latitude, current.longitude),
                                        precision = precision,
                                    ),
                                )
                            } else {
                                fetchPlatformLocation(context, precision, ::deliverOnce)
                            }
                        }
                        .addOnFailureListener {
                            fetchPlatformLocation(context, precision, ::deliverOnce)
                        }
                }
            }
            .addOnFailureListener {
                fetchPlatformLocation(context, precision, ::deliverOnce)
            }
    }

    @SuppressLint("MissingPermission")
    private fun fetchPlatformLocation(
        context: Context,
        precision: LocationPrecisionLevel,
        deliverOnce: (LocationLookupResult) -> Unit,
    ) {
        val lastPlatform = fallbackSystemLastLocation(context, precision)
        if (lastPlatform != null) {
            deliverOnce(LocationLookupResult.Success(lastPlatform, precision))
            return
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            deliverOnce(
                LocationLookupResult.UnavailableTemporarily(
                    "Serviço de localização temporariamente indisponível.",
                ),
            )
            return
        }

        val provider =
            when {
                precision == LocationPrecisionLevel.PRECISE &&
                    runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.GPS_PROVIDER
                runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }

        if (provider == null) {
            deliverOnce(
                LocationLookupResult.UnavailableTemporarily(
                    "Última localização indisponível e sem sinal ativo no momento.",
                ),
            )
            return
        }

        val signal = CancellationSignal()
        runCatching {
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (location != null) {
                    deliverOnce(
                        LocationLookupResult.Success(
                            coordinate = CityMapCoordinate(location.latitude, location.longitude),
                            precision = precision,
                        ),
                    )
                } else {
                    deliverOnce(
                        LocationLookupResult.UnavailableTemporarily(
                            "Sem sinal de localização no momento. Tente novamente em local aberto ou pesquise a cidade.",
                        ),
                    )
                }
            }
        }.onFailure {
            deliverOnce(
                LocationLookupResult.UnavailableTemporarily(
                    "Não foi possível obter nova posição agora. O mapa permanece navegável.",
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun fallbackSystemLastLocation(
        context: Context,
        precision: LocationPrecisionLevel,
    ): CityMapCoordinate? {
        if (precision == LocationPrecisionLevel.NONE) return null
        return runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers =
                if (precision == LocationPrecisionLevel.PRECISE) {
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                } else {
                    listOf(LocationManager.NETWORK_PROVIDER)
                }
            val best =
                providers
                    .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxByOrNull { it.time }
            best?.let { CityMapCoordinate(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
