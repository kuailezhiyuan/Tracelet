package com.ikolvi.tracelet.sdk.wrapper

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.ikolvi.tracelet.sdk.ConfigManager
import com.ikolvi.tracelet.sdk.util.TraceletLog
import java.util.concurrent.ConcurrentHashMap

/** Explicit LocationManager backend; leaves automatic GMS/AOSP selection unchanged. */
internal class LocationManagerClient(
    private val context: Context,
    private val config: ConfigManager,
    private val manager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager,
) : TraceletLocationClient {
    private val subscriptions = ConcurrentHashMap<TraceletLocationCallback, Subscription>()
    // Keep the GPS anchor when High/Low replaces the subscription.
    @Volatile private var lastGps: Location? = null

    override fun requestLocationUpdates(
        request: TraceletLocationRequest,
        callback: TraceletLocationCallback,
        looper: Looper,
    ) {
        callback.onLocationAvailability(register(request, callback, looper))
    }

    override fun requestLocationUpdatesWithResult(
        request: TraceletLocationRequest,
        callback: TraceletLocationCallback,
        looper: Looper,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val result = try {
            check(register(request, callback, looper)) { "No enabled LocationManager provider" }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
        onResult(result)
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun register(
        request: TraceletLocationRequest,
        callback: TraceletLocationCallback,
        looper: Looper,
    ): Boolean {
        val passive = request.priority == TraceletLocationPriority.PRIORITY_PASSIVE
        val low = request.priority == TraceletLocationPriority.PRIORITY_LOW_POWER
        val providers = providersFor(request.priority)
        val enabled = providers.filter(manager::isProviderEnabled)
        if (enabled.isEmpty()) return false

        val interval = request.intervalMillis
        val quality = when (request.priority) {
            TraceletLocationPriority.PRIORITY_HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
            TraceletLocationPriority.PRIORITY_LOW_POWER -> LocationRequestCompat.QUALITY_LOW_POWER
            else -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        }
        val nativeRequest = LocationRequestCompat.Builder(
            if (passive) LocationRequestCompat.PASSIVE_INTERVAL else interval,
        ).setQuality(quality)
            .setMinUpdateIntervalMillis(request.minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(request.minUpdateDistanceMeters)
            .setMaxUpdateDelayMillis(request.maxUpdateDelayMillis)
            .build()
        val subscription = Subscription(
            callback, providers, if (low) 30_000L else 10_000L,
            config.getTrackingAccuracyThreshold(),
        )
        try {
            for (provider in enabled) {
                LocationManagerCompat.requestLocationUpdates(
                    manager, provider, nativeRequest, subscription, looper,
                )
            }
        } catch (error: Exception) {
            subscription.stop()?.let(error::addSuppressed)
            throw error
        }
        // A failed replacement must leave the previous subscription running.
        subscriptions.put(callback, subscription)?.stop()
        subscription.state = State.ACTIVE
        TraceletLog.lifecycle(
            "location-manager: request sources=${enabled.joinToString()} quality=$quality " +
                "interval=${interval}ms minInterval=${request.minUpdateIntervalMillis}ms " +
                "gpsFreshness=${subscription.freshnessMs}ms accuracyGate=${subscription.accuracyGate}m",
        )
        return true
    }

    @Synchronized
    override fun removeLocationUpdates(callback: TraceletLocationCallback) {
        subscriptions.remove(callback)?.stop()
    }

    private fun providersFor(priority: Int): List<String> =
        if (priority == TraceletLocationPriority.PRIORITY_PASSIVE)
            listOf(LocationManager.PASSIVE_PROVIDER)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    @SuppressLint("MissingPermission")
    override fun getCurrentLocation(
        priority: Int,
        cancellationToken: TraceletCancellationToken?,
        onSuccess: (Location?) -> Unit,
    ) {
        val signal = CancellationSignal()
        cancellationToken?.onCanceled(signal::cancel)
        if (cancellationToken?.isCancelled == true) signal.cancel()
        val providers = providersFor(priority).filter(manager::isProviderEnabled)
        fun request(index: Int) {
            if (signal.isCanceled) return
            if (index == providers.size) {
                onSuccess(null)
                return
            }
            val provider = providers[index]
            LocationManagerCompat.getCurrentLocation(
                manager, provider, signal, ContextCompat.getMainExecutor(context),
            ) { location ->
                if (!signal.isCanceled) {
                    if (location != null) {
                        rememberGps(location)
                        onSuccess(location)
                    } else request(index + 1)
                }
            }
        }
        request(0)
    }

    @SuppressLint("MissingPermission")
    override fun getLastLocation(onSuccess: (Location?) -> Unit, onFailure: (Exception) -> Unit) {
        val best = try {
            val gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            rememberGps(gps)
            if (isUsableGps(gps, 30_000L, config.getTrackingAccuracyThreshold())) gps
                else listOfNotNull(gps, network).maxByOrNull { it.elapsedRealtimeNanos }
        } catch (error: Exception) {
            onFailure(error)
            return
        }
        onSuccess(best)
    }

    @Synchronized
    private fun rememberGps(location: Location?) {
        if (location?.provider != LocationManager.GPS_PROVIDER) return
        val previous = lastGps
        if (previous == null || location.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) {
            lastGps = Location(location)
        }
    }

    private fun isUsableGps(gps: Location?, freshnessMs: Long, accuracyGate: Int): Boolean {
        if (gps == null || !gps.hasAccuracy() || !gps.accuracy.isFinite() || gps.accuracy < 0) return false
        val age = SystemClock.elapsedRealtimeNanos() - gps.elapsedRealtimeNanos
        return (accuracyGate <= 0 || gps.accuracy <= accuracyGate) &&
            age in 0L..freshnessMs * 1_000_000 &&
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private enum class State { REGISTERING, ACTIVE, STOPPED }

    private inner class Subscription(
        private val callback: TraceletLocationCallback,
        private val providers: List<String>,
        val freshnessMs: Long,
        val accuracyGate: Int,
    ) : LocationListenerCompat {
        @Volatile var state = State.REGISTERING
        private var unregisterPending = false
        private var forwardedSource: String? = null

        private fun active(): Boolean {
            if (state == State.STOPPED && unregisterPending) stop()
            return state == State.ACTIVE
        }

        override fun onLocationChanged(location: Location) {
            if (!active()) return
            rememberGps(location)
            val gps = lastGps
            val gpsAgeNanos = gps?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
            val usableGps = isUsableGps(gps, freshnessMs, accuracyGate)
            val skip = location.provider == LocationManager.NETWORK_PROVIDER && usableGps
            val reason = when {
                skip -> "fresh_gps"
                location.provider == LocationManager.GPS_PROVIDER -> "gps"
                else -> "network_fallback"
            }
            TraceletLog.debug(
                "location-manager: raw source=${location.provider} fixTimeMs=${location.time} " +
                    "accuracy=${location.accuracy}m speed=${if (location.hasSpeed()) location.speed else null} " +
                    "speedAccuracy=${if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null} " +
                    "gpsAgeMs=${gpsAgeNanos?.div(1_000_000)} forward=${!skip} reason=$reason",
            )
            if (skip) return
            if (forwardedSource != location.provider) {
                forwardedSource = location.provider
                TraceletLog.lifecycle(
                    "location-manager: forward source=${location.provider} reason=$reason " +
                        "fixTimeMs=${location.time} accuracy=${location.accuracy}m",
                )
            }
            callback.onLocationResult(listOf(location))
        }

        override fun onProviderEnabled(provider: String) {
            if (active()) callback.onLocationAvailability(true)
        }

        override fun onProviderDisabled(provider: String) {
            if (!active()) return
            if (provider == LocationManager.GPS_PROVIDER) lastGps = null
            callback.onLocationAvailability(providers.any(manager::isProviderEnabled))
        }

        fun stop(): Exception? {
            state = State.STOPPED
            unregisterPending = true
            return try {
                LocationManagerCompat.removeUpdates(manager, this)
                unregisterPending = false
                null
            } catch (error: Exception) {
                TraceletLog.error("location-manager: failed to remove LocationManager subscription", error)
                error
            }
        }
    }
}

// Shared by continuous tracking and periodic workers, including native recovery.
internal fun createLocationClient(context: Context, config: ConfigManager): TraceletLocationClient =
    if (config.getUseLocationManager()) LocationManagerClient(context, config)
    else TraceletServices.getProvider(context).getLocationClient(context)
