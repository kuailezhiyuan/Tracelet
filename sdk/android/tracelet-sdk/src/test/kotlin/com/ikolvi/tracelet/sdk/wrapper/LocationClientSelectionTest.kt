package com.ikolvi.tracelet.sdk.wrapper

import android.content.Context
import com.ikolvi.tracelet.sdk.ConfigManager
import com.ikolvi.tracelet.sdk.model.AndroidConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocationClientSelectionTest {
    private lateinit var context: Context
    private lateinit var config: ConfigManager
    private val provider = mock<TraceletServicesProvider>()
    private val defaultClient = mock<TraceletLocationClient>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = ConfigManager(context)
        config.reset(null)
        whenever(provider.getLocationClient(context)).thenReturn(defaultClient)
        TraceletServices.setProvider(provider)
    }

    @After
    fun tearDown() {
        TraceletServices.setProvider(null)
        config.reset(null)
    }

    @Test
    fun defaultUsesTheExistingServicesProvider() {
        assertFalse(config.getUseLocationManager())
        assertEquals(0L, config.getStationaryLocationUpdateInterval())
        assertSame(defaultClient, createLocationClient(context, config))
    }

    @Test
    fun explicitSelectionPersistsWithoutReplacingOtherServices() {
        val typed = AndroidConfig(useLocationManager = true, stationaryLocationUpdateInterval = 10_000)
        assertEquals(typed, AndroidConfig.fromMap(typed.toMap()))
        config.setConfig(mapOf("android" to typed.toMap()))
        config.setConfig(mapOf("android" to mapOf("useLocationManager" to null)))
        val restored = ConfigManager(context)
        assertTrue(restored.getUseLocationManager())
        assertEquals(10_000L, restored.getStationaryLocationUpdateInterval())
        assertTrue(createLocationClient(context, restored) is LocationManagerClient)
        assertSame(provider, TraceletServices.getProvider(context))
        config.setConfig(mapOf("android" to mapOf("useLocationManager" to false)))
        assertSame(defaultClient, createLocationClient(context, config))
    }

    @Test
    fun invalidIntervalsAreRejectedBeforePersisting() {
        val before = config.getConfig()
        assertThrows(IllegalArgumentException::class.java) {
            config.setConfig(mapOf("android" to mapOf("stationaryLocationUpdateInterval" to -1)))
        }
        assertEquals(before, ConfigManager(context).getConfig())
    }
}
