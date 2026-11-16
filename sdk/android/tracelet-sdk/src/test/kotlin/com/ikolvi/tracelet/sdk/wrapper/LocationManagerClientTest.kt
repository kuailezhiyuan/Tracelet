package com.ikolvi.tracelet.sdk.wrapper

import android.app.Application
import android.os.CancellationSignal
import com.ikolvi.tracelet.sdk.ConfigManager
import org.junit.After
import java.util.function.Consumer
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.ikolvi.tracelet.sdk.wrapper.TraceletLocationCallback
import com.ikolvi.tracelet.sdk.wrapper.TraceletLocationPriority
import com.ikolvi.tracelet.sdk.wrapper.TraceletLocationRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], manifest = Config.NONE, application = Application::class)
class LocationManagerClientTest {
    private val manager = mock<LocationManager>()
    private val registrations = mutableListOf<Registration>()
    private val callback = RecordingCallback()
    private var gpsEnabled = true
    private var networkEnabled = true
    private var failNetwork = false
    private lateinit var client: LocationManagerClient
    private lateinit var config: ConfigManager

    @Before
    fun setUp() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        whenever(manager.isProviderEnabled(any())).thenAnswer {
            when (it.getArgument<String>(0)) {
                LocationManager.GPS_PROVIDER -> gpsEnabled
                LocationManager.NETWORK_PROVIDER -> networkEnabled
                else -> true
            }
        }
        doAnswer {
            val registration = Registration(it.getArgument(0), it.getArgument(1), it.getArgument(3))
            registrations.add(registration)
            if (failNetwork && registration.provider == LocationManager.NETWORK_PROVIDER) {
                throw SecurityException("Network registration failed")
            }
            null
        }.whenever(manager).requestLocationUpdates(
            any<String>(), any<LocationRequest>(), any<Executor>(), any<LocationListener>(),
        )
        config = ConfigManager(RuntimeEnvironment.getApplication())
        config.reset(null)
        client = LocationManagerClient(RuntimeEnvironment.getApplication(), config, manager)
    }

    @Test
    fun highRegistersGpsAndNetworkAtOneSecondWithHighAccuracy() {
        register()
        assertEquals(listOf("gps", "network"), registrations.map { it.provider })
        for (registration in registrations) {
            assertEquals(1000L, registration.request.intervalMillis)
            assertEquals(1000L, registration.request.minUpdateIntervalMillis)
            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, registration.request.quality)
        }
    }

    @Test
    fun lowKeepsBothProvidersAtTenSecondsWithLowPowerQuality() {
        register(low = true)
        assertEquals(listOf("gps", "network"), registrations.map { it.provider })
        for (registration in registrations) {
            assertEquals(10_000L, registration.request.intervalMillis)
            assertEquals(10_000L, registration.request.minUpdateIntervalMillis)
            assertEquals(LocationRequest.QUALITY_LOW_POWER, registration.request.quality)
        }
    }

    @Test
    fun freshGpsSuppressesNetworkWithoutChangingTheGpsTimestamp() {
        register()
        val gps = fix("gps", 20f)
        val timestamp = gps.time
        listener().onLocationChanged(gps)
        listener().onLocationChanged(fix("network", 10f))
        assertEquals(1, callback.locations.size)
        assertSame(gps, callback.locations.single())
        assertEquals(timestamp, callback.locations.single().time)
    }

    @Test
    fun networkIsAvailableBeforeTheFirstGpsAndAfterHighGpsExpires() {
        register()
        listener().onLocationChanged(fix("network", 40f))
        listener().onLocationChanged(fix("gps", 20f))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        listener().onLocationChanged(fix("network", 44f))
        assertEquals(listOf("network", "gps", "network"), callback.locations.map { it.provider })
    }

    @Test
    fun lowUsesThirtySecondsOfGpsFreshness() {
        register(low = true)
        listener().onLocationChanged(fix("gps", 20f))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(29))
        listener().onLocationChanged(fix("network", 40f))
        assertEquals(1, callback.locations.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        listener().onLocationChanged(fix("network", 40f))
        assertEquals(2, callback.locations.size)
    }

    @Test
    fun inaccurateOrOldGpsCannotSuppressNetworkFallback() {
        register()
        listener().onLocationChanged(fix("gps", 20f))
        listener().onLocationChanged(fix("gps", 150f))
        listener().onLocationChanged(fix("network", 44f))
        assertEquals("network", callback.locations.last().provider)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        val oldGps = fix("gps", 20f).apply {
            elapsedRealtimeNanos -= 20_000_000_000L
            time -= 20_000
        }
        listener().onLocationChanged(oldGps)
        listener().onLocationChanged(fix("network", 44f))
        assertEquals("network", callback.locations.last().provider)
    }

    @Test
    fun lowRetainsTheFreshGpsAnchorAcrossPaceChanges() {
        register()
        val oldListener = listener()
        oldListener.onLocationChanged(fix("gps", 20f))
        register(low = true)
        listener().onLocationChanged(fix("network", 44f))
        oldListener.onLocationChanged(fix("gps", 20f))
        assertEquals(1, callback.locations.size)
        verify(manager).removeUpdates(oldListener)
    }

    @Test
    fun failedReplacementRetainsTheOldSubscriptionAndCleansUpThePartialNewOne() {
        register()
        val oldListener = listener()
        failNetwork = true
        var result: Result<Unit>? = null
        client.requestLocationUpdatesWithResult(request(low = true), callback, Looper.getMainLooper()) {
            result = it
        }
        assertTrue(result!!.isFailure)
        val failedListener = listener()
        verify(manager).removeUpdates(failedListener)
        verify(manager, never()).removeUpdates(oldListener)
        failedListener.onLocationChanged(fix("gps", 20f))
        oldListener.onLocationChanged(fix("gps", 20f))
        assertEquals(1, callback.locations.size)
    }

    @Test
    fun disablingGpsAllowsNetworkWithoutDeclaringAllProvidersUnavailable() {
        register()
        listener().onLocationChanged(fix("gps", 20f))
        gpsEnabled = false
        listener().onProviderDisabled("gps")
        listener().onLocationChanged(fix("network", 44f))
        assertEquals("network", callback.locations.last().provider)
        assertEquals(listOf(true, true), callback.availability)
        networkEnabled = false
        listener().onProviderDisabled("network")
        assertFalse(callback.availability.last())
    }

    @Test
    fun removedSubscriptionsDoNotDeliverLateFixes() {
        register()
        val previous = listener()
        client.removeLocationUpdates(callback)
        previous.onLocationChanged(fix("gps", 20f))
        assertTrue(callback.locations.isEmpty())
        verify(manager).removeUpdates(previous)
    }

    @Test
    fun failedUnregistrationIsRetriedOnLateDeliveryWithoutForwardingTheFix() {
        register()
        val previous = listener()
        doThrow(IllegalStateException("Binder temporarily unavailable")).doNothing()
            .whenever(manager).removeUpdates(previous)
        client.removeLocationUpdates(callback)
        previous.onLocationChanged(fix("gps", 20f))
        verify(manager, times(2)).removeUpdates(previous)
        assertTrue(callback.locations.isEmpty())
    }

    @After
    fun tearDown() {
        client.removeLocationUpdates(callback)
        config.reset(null)
    }

    @Test
    fun requestIntervalsComeFromTheEngineInsteadOfAccuracy() {
        client.requestLocationUpdates(
            request(low = true).copy(intervalMillis = 20_000, minUpdateIntervalMillis = 5_000),
            callback, Looper.getMainLooper(),
        )
        assertTrue(registrations.all {
            it.request.intervalMillis == 20_000L && it.request.minUpdateIntervalMillis == 5_000L
        })
    }

    @Test
    fun passiveRequestsDoNotActivateGpsOrNetwork() {
        client.requestLocationUpdates(
            request(low = false).copy(priority = TraceletLocationPriority.PRIORITY_PASSIVE),
            callback, Looper.getMainLooper(),
        )
        assertEquals(listOf("passive"), registrations.map { it.provider })
    }

    @Test
    fun currentFixUsesGpsEvenAtLowAccuracyAndFallsBackWhenItReturnsNull() {
        val sources = mutableListOf<String>()
        val consumers = mutableListOf<Consumer<Location?>>()
        doAnswer {
            sources.add(it.getArgument(0))
            consumers.add(it.getArgument(3))
            null
        }.whenever(manager).getCurrentLocation(
            any<String>(), any<CancellationSignal>(), any<Executor>(), any<Consumer<Location?>>(),
        )
        val received = mutableListOf<Location?>()
        client.getCurrentLocation(TraceletLocationPriority.PRIORITY_LOW_POWER, null, received::add)
        assertEquals(listOf("gps"), sources)
        consumers.single().accept(null)
        assertEquals(listOf("gps", "network"), sources)
        val network = fix("network", 20f)
        consumers.last().accept(network)
        assertEquals(listOf(network), received)
    }

    @Test
    fun cancelledCurrentFixDoesNotDeliverOrStartNetworkFallback() {
        var signal: CancellationSignal? = null
        var consumer: Consumer<Location?>? = null
        doAnswer {
            signal = it.getArgument(1)
            consumer = it.getArgument(3)
            null
        }.whenever(manager).getCurrentLocation(
            any<String>(), any<CancellationSignal>(), any<Executor>(), any<Consumer<Location?>>(),
        )
        val cts = TraceletCancellationTokenSource()
        val received = mutableListOf<Location?>()
        client.getCurrentLocation(TraceletLocationPriority.PRIORITY_HIGH_ACCURACY, cts.token, received::add)
        cts.cancel()
        consumer!!.accept(null)
        assertTrue(signal!!.isCanceled)
        assertTrue(received.isEmpty())
        verify(manager, times(1)).getCurrentLocation(
            any<String>(), any<CancellationSignal>(), any<Executor>(), any<Consumer<Location?>>(),
        )
    }

    @Test
    fun oneShotGpsAlsoSuppressesNetworkOnTheContinuousStream() {
        var consumer: Consumer<Location?>? = null
        doAnswer {
            consumer = it.getArgument(3)
            null
        }.whenever(manager).getCurrentLocation(
            any<String>(), any<CancellationSignal>(), any<Executor>(), any<Consumer<Location?>>(),
        )
        register()
        val received = mutableListOf<Location?>()
        client.getCurrentLocation(TraceletLocationPriority.PRIORITY_HIGH_ACCURACY, null, received::add)
        val gps = fix("gps", 20f)
        consumer!!.accept(gps)
        listener().onLocationChanged(fix("network", 10f))
        assertEquals(listOf(gps), received)
        assertTrue(callback.locations.isEmpty())
    }

    @Test
    fun cachePrefersFreshAccurateGpsOverNewerNetworkThenFallsBackWhenItExpires() {
        val gps = fix("gps", 20f)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val network = fix("network", 10f)
        whenever(manager.getLastKnownLocation("gps")).thenReturn(gps)
        whenever(manager.getLastKnownLocation("network")).thenReturn(network)
        val received = mutableListOf<Location?>()
        client.getLastLocation(received::add) { throw AssertionError(it) }
        assertSame(gps, received.last())
        register()
        listener().onLocationChanged(network)
        assertTrue(callback.locations.isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        client.getLastLocation(received::add) { throw AssertionError(it) }
        assertSame(network, received.last())
    }

    private fun register(low: Boolean = false) {
        client.requestLocationUpdates(request(low), callback, Looper.getMainLooper())
    }

    private fun request(low: Boolean) = TraceletLocationRequest(
        if (low) TraceletLocationPriority.PRIORITY_LOW_POWER
        else TraceletLocationPriority.PRIORITY_HIGH_ACCURACY,
        if (low) 10_000 else 1000,
        minUpdateIntervalMillis = if (low) 10_000 else 1000,
    )

    private fun listener() = registrations.last().listener

    private fun fix(provider: String, accuracyM: Float) = Location(provider).apply {
        accuracy = accuracyM
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private data class Registration(
        val provider: String,
        val request: LocationRequest,
        val listener: LocationListener,
    )

    private class RecordingCallback : TraceletLocationCallback {
        val locations = mutableListOf<Location>()
        val availability = mutableListOf<Boolean>()
        override fun onLocationResult(locations: List<Location>) { this.locations.addAll(locations) }
        override fun onLocationAvailability(isLocationAvailable: Boolean) { availability.add(isLocationAvailable) }
    }
}
