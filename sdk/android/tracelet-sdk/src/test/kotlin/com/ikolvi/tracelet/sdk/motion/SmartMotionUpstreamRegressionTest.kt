package com.ikolvi.tracelet.sdk.motion

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ikolvi.tracelet.sdk.ConfigManager
import com.ikolvi.tracelet.sdk.ListenerEventSender
import com.ikolvi.tracelet.sdk.StateManager
import com.ikolvi.tracelet.sdk.TraceletEventSender
import com.ikolvi.tracelet.sdk.TraceletSdk
import com.ikolvi.tracelet.sdk.location.LocationEngine
import com.ikolvi.tracelet.sdk.model.SpeedMotionState
import com.ikolvi.tracelet.sdk.model.TrackingMode
import com.ikolvi.tracelet.sdk.service.LocationService
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses APIs shared with upstream, so identical tests can verify whether our fixes are still needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartMotionUpstreamRegressionTest {
    private lateinit var context: Context
    private lateinit var config: ConfigManager
    private lateinit var state: StateManager
    private var engine: LocationEngine? = null
    private var service: org.robolectric.android.controller.ServiceController<LocationService>? = null
    private var speedManager: SpeedMotionManager? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        config = ConfigManager.getInstance(context)
        config.reset(null)
        config.setConfig(mapOf(
            "motionDetectionMode" to 2, "distanceFilter" to 0.0,
            "maxImpliedSpeed" to 0, "resolveAddress" to false,
            "persistMode" to 3, "trackingAccuracyThreshold" to 100,
            "foregroundChannelId" to "motion_regression",
        ))
        state = StateManager(context)
        state.reset()
        state.enabled = true
        state.isMoving = true
        state.trackingMode = TrackingMode.CONTINUOUS
        state.sessionTrackingMode = TrackingMode.CONTINUOUS
    }

    @After
    fun tearDown() {
        speedManager?.stop()
        engine?.destroy()
        service?.destroy()
        LocationService.stopStationaryTimer()
        ConfigManager.resetInstance()
    }

    private fun makeEngine(speeds: MutableList<Double>): LocationEngine =
        LocationEngine(context, config, state, mock<TraceletEventSender>()).also {
            engine = it
            it.speedMotionSpeedSink = { speed -> speeds.add(speed) }
        }

    private fun deliver(target: LocationEngine, metres: Double, speed: Float?) {
        val fix = Location("gps").apply {
            latitude = 36.7 + metres / 111_320
            longitude = 117.1
            accuracy = 5f
            if (speed != null) {
                this.speed = speed
                speedAccuracyMetersPerSecond = 0.01f
            }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        val receiver = LocationEngine::class.java.getDeclaredMethod(
            "onLocationReceived", Location::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        )
        receiver.isAccessible = true
        receiver.invoke(target, fix, "location", false)
    }

    @Test
    fun `a platform zero cannot become motion from drifting coordinates`() {
        val speeds = mutableListOf<Double>()
        val target = makeEngine(speeds)
        deliver(target, 0.0, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        deliver(target, 54.0, 0f)
        assertEquals(2, speeds.size)
        assertEquals(0.0, speeds.last(), "measured zero must survive the entire native processing path")
    }

    @Test
    fun `millisecond position deltas cannot provide a motion speed`() {
        val speeds = mutableListOf<Double>()
        val target = makeEngine(speeds)
        deliver(target, 0.0, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        deliver(target, 44.0, null)
        assertEquals(2, speeds.size)
        assertTrue(speeds.last() < 0, "a 2ms coordinate delta is unavailable evidence, not movement")
    }

    @Test
    fun `non finite speed cannot satisfy wake confirmations`() {
        config.setConfig(mapOf("speedWakeConfirmCount" to 3))
        state.speedMotionState = SpeedMotionState.STATIONARY
        val callback = object : SpeedMotionManager.SpeedMotionCallback {
            override fun switchToContinuous() = Unit
            override fun switchToStationaryPeriodic() = Unit
            override fun switchToStationaryGeofences() = Unit
        }
        val manager = SpeedMotionManager(config, state, mock(), callback)
        speedManager = manager
        manager.start()
        repeat(3) { manager.onLocation(Double.POSITIVE_INFINITY) }
        assertEquals("stationary", manager.getCurrentState())
    }

    @Test
    fun `native restore preserves an acceleration only moving pace`() {
        TraceletSdk.getInstance(context).setEventSender(ListenerEventSender())
        state.speedMotionState = SpeedMotionState.STATIONARY
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("boot_start", true)
        }
        val controller = Robolectric.buildService(LocationService::class.java)
        service = controller
        controller.create().withIntent(intent).startCommand(0, 1)
        val coordinator = assertNotNull(LocationService.bootSmartMotionCoordinator)
        assertTrue(coordinator.isAccelMoving, "speed restoration must retain the saved moving input")
        assertFalse(coordinator.isSpeedMoving)
        assertTrue(state.isMoving)
    }

    @Test
    fun `an explicit tremor opt out preserves a moving sensor`() {
        config.setConfig(mapOf("enableGpsTremorOverride" to false))
        val target = makeEngine(mutableListOf())
        deliver(target, 0.0, 0f)
        val coordinator = SmartMotionCoordinator(context, config, state, mock(), target, mock(), mock())
        coordinator.syncCurrentMode()
        coordinator.onAccelStateChange(true)
        coordinator.onSpeedStateChange(false)
        assertTrue(coordinator.isAccelMoving)
        assertTrue(state.isMoving)
    }

    @Test
    fun `stationary accelerometer does not erase native vehicle speed`() {
        TraceletSdk.getInstance(context).setEventSender(ListenerEventSender())
        state.speedMotionState = SpeedMotionState.MOVING
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("boot_start", true)
        }
        val controller = Robolectric.buildService(LocationService::class.java)
        service = controller
        controller.create().withIntent(intent).startCommand(0, 1)
        assertNotNull(LocationService.bootMotionDetector).onMotionStateChanged?.invoke(false)
        val coordinator = assertNotNull(LocationService.bootSmartMotionCoordinator)
        assertFalse(coordinator.isAccelMoving)
        assertTrue(coordinator.isSpeedMoving, "smooth vehicle motion must retain the independent speed input")
        assertTrue(state.isMoving)
    }
}
