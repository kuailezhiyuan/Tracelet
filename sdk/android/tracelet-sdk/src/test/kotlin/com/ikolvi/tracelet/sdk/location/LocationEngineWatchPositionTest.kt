package com.ikolvi.tracelet.sdk.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ikolvi.tracelet.sdk.ConfigManager
import com.ikolvi.tracelet.sdk.StateManager
import com.ikolvi.tracelet.sdk.TraceletEventSender
import com.ikolvi.tracelet.sdk.util.TraceletLog
import com.ikolvi.tracelet.sdk.wrapper.TraceletActivityRecognitionClient
import com.ikolvi.tracelet.sdk.wrapper.TraceletEventExtractor
import com.ikolvi.tracelet.sdk.wrapper.TraceletGeofencingClient
import com.ikolvi.tracelet.sdk.wrapper.TraceletLocationCallback
import com.ikolvi.tracelet.sdk.wrapper.TraceletLocationClient
import com.ikolvi.tracelet.sdk.wrapper.TraceletServices
import com.ikolvi.tracelet.sdk.wrapper.TraceletServicesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationEngineWatchPositionTest {
    private lateinit var engine: LocationEngine
    private lateinit var client: TraceletLocationClient

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        client = mock()
        TraceletServices.setProvider(object : TraceletServicesProvider {
            override fun getLocationClient(context: Context) = client
            override fun getGeofencingClient(context: Context) = mock<TraceletGeofencingClient>()
            override fun getActivityRecognitionClient(context: Context) = mock<TraceletActivityRecognitionClient>()
            override fun getEventExtractor() = mock<TraceletEventExtractor>()
        })
        TraceletLog.detach()
        engine = LocationEngine(context, ConfigManager.getInstance(context), StateManager(context), mock<TraceletEventSender>())
    }

    @After
    fun tearDown() {
        engine.stopAllWatchers()
        TraceletServices.setProvider(null)
        ConfigManager.resetInstance()
        TraceletLog.detach()
    }

    @Test
    fun `foreground watcher survives recording stop until explicitly cancelled`() {
        val watchId = engine.watchPosition(emptyMap())
        assertTrue(watchId >= 0)
        val callback = argumentCaptor<TraceletLocationCallback>()
        verify(client).requestLocationUpdates(any(), callback.capture(), eq(Looper.getMainLooper()))

        engine.stop()
        verify(client, never()).removeLocationUpdates(any())

        assertTrue(engine.stopWatchPosition(watchId))
        verify(client).removeLocationUpdates(callback.firstValue)
        assertFalse(engine.stopWatchPosition(watchId))
    }

    @Test
    fun `permission revoked while registering logs the exception and rejects the watcher`() {
        val failure = SecurityException("Location permission revoked")
        doThrow(failure).whenever(client).requestLocationUpdates(any(), any(), any())

        assertEquals(-1, engine.watchPosition(emptyMap()))
        assertTrue(ShadowLog.getLogsForTag("Tracelet").any {
            it.msg.contains("watchPosition") && it.throwable === failure
        })
        verify(client, never()).removeLocationUpdates(any())
    }
}
