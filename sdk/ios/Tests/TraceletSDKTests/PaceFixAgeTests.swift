import CoreLocation
import XCTest

@testable import TraceletSDK

/// Motion evidence must not inherit stale speeds or position jitter from the
/// accepted-location cache. Tests feed raw fixes without starting background
/// tracking, which the SwiftPM test bundle is not entitled to run.
final class PaceFixAgeTests: XCTestCase {

    private func makeEngine() -> (LocationEngine, PaceAgeLocationManager) {
        let config = ConfigManager()
        config.reset(nil)
        _ = config.setConfig([
            "desiredAccuracy": 0,
            "distanceFilter": 0.0,
            // persistMode 3 = none → dispatch skips DB writes, so the test
            // needs no native persistence wiring.
            "persistMode": 3,
            "resolveAddress": false,
            "rejectMockLocations": false,
            "pausesLocationUpdatesAutomatically": false,
        ])
        let engine = LocationEngine(
            configManager: config,
            stateManager: StateManager(),
            eventDispatcher: PaceAgeEventSender()
        )
        let recorder = PaceAgeLocationManager()
        engine.locationManager = recorder
        recorder.delegate = engine
        return (engine, recorder)
    }

    private func fix(at timestamp: Date) -> CLLocation {
        CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 10.787929, longitude: 76.684183),
            altitude: 0,
            horizontalAccuracy: 5,
            verticalAccuracy: 5,
            timestamp: timestamp
        )
    }

    private func speedFix(_ speed: Double, metres: Double = 0, age: TimeInterval = 0, accuracy: Double = 5, uncertainty: Double = 0.07) -> CLLocation {
        CLLocation(coordinate: CLLocationCoordinate2D(latitude: 36.7 + metres / 111_320, longitude: 117.1),
            altitude: 0, horizontalAccuracy: accuracy, verticalAccuracy: 5,
            course: -1, courseAccuracy: -1, speed: speed, speedAccuracy: uncertainty,
            timestamp: Date(timeIntervalSinceNow: -age))
    }

    func testPlatformZeroIsNotReplacedByADriftingPosition() {
        let (engine, _) = makeEngine()
        var speeds: [Double] = []
        engine.speedSink = { speeds.append($0) }
        engine.feedSpeedMotion(speedFix(0, age: 1))
        engine.feedSpeedMotion(speedFix(0, metres: 54))
        XCTAssertEqual(speeds, [0, 0])
    }

    func testMillisecondPositionDifferencesDoNotProvideMotionSpeed() {
        for dt in [0.002, 0.008, 0.068] {
            let (engine, _) = makeEngine()
            var speed = 0.0
            engine.speedSink = { speed = $0 }
            engine.feedSpeedMotion(speedFix(-1, age: dt))
            engine.feedSpeedMotion(speedFix(-1, metres: 44))
            XCTAssertLessThan(speed, 0)
            XCTAssertEqual(engine.getMotionSpeedDiagnostics()["motionSpeedReason"] as? String, "short_interval")
        }
    }

    func testPositionDriftWithinAccuracyIsUnknownRatherThanHighSpeed() {
        let (engine, _) = makeEngine()
        var speed = 0.0
        engine.speedSink = { speed = $0 }
        engine.feedSpeedMotion(speedFix(-1, age: 1, accuracy: 95))
        engine.feedSpeedMotion(speedFix(-1, metres: 19.2, accuracy: 90))
        XCTAssertLessThan(speed, 0)
        XCTAssertEqual(engine.getMotionSpeedDiagnostics()["motionSpeedReason"] as? String, "within_position_error")
    }

    func testCrediblePositionDeltaCanStillDetectAVehicleWithoutPlatformSpeed() {
        let (engine, _) = makeEngine()
        var speed = 0.0
        engine.speedSink = { speed = $0 }
        engine.feedSpeedMotion(speedFix(-1, age: 5))
        engine.feedSpeedMotion(speedFix(-1, metres: 100))
        XCTAssertEqual(speed, 18, accuracy: 0.1)
    }

    func testUncertainSpeedInsideTheHysteresisBandIsUnknown() {
        let (engine, _) = makeEngine()
        engine.feedSpeedMotion(speedFix(0.7, uncertainty: 10))
        XCTAssertEqual(engine.getMotionSpeedDiagnostics()["motionSpeedReason"] as? String, "speed_uncertainty")
        XCTAssertNil(engine.getMotionSpeedDiagnostics()["motionSpeedMps"] as? Double)
    }

    func testRepeatedAndOutOfOrderFixesCannotCountAsWakeConfirmations() {
        let (engine, _) = makeEngine()
        var speeds: [Double] = []
        engine.speedSink = { speeds.append($0) }
        let location = speedFix(3)
        engine.feedSpeedMotion(location)
        engine.feedSpeedMotion(location)
        engine.feedSpeedMotion(speedFix(3, age: 1))
        XCTAssertEqual(speeds, [3])
    }

    /// Nothing accepted yet is *unknown*, and must stay distinguishable from a
    /// speed of zero — the distinction #333 established and #404 extends to age.
    func testNoFixReportsNoAge() {
        let (engine, _) = makeEngine()
        XCTAssertNil(
            engine.paceFixAge,
            "no fix has been accepted, so there is no reading to be old or new")
    }

    func testACurrentFixReportsANegligibleAge() {
        let (engine, recorder) = makeEngine()
        engine.locationManager(recorder, didUpdateLocations: [fix(at: Date())])

        let age = try? XCTUnwrap(engine.paceFixAge)
        XCTAssertNotNil(age)
        XCTAssertLessThan(
            age ?? .greatestFiniteMagnitude, LocationEngine.maximumPaceFixAge,
            "a fix taken now is inside the window that may drive the pace machine")
    }

    /// The reported state: the fix behind the frozen speed is minutes old.
    func testAFixFromFiveMinutesAgoIsReportedAsStale() {
        let (engine, recorder) = makeEngine()
        engine.locationManager(
            recorder, didUpdateLocations: [fix(at: Date(timeIntervalSinceNow: -300))])

        let age = try? XCTUnwrap(engine.paceFixAge)
        XCTAssertNotNil(age)
        XCTAssertGreaterThan(
            age ?? 0, LocationEngine.maximumPaceFixAge,
            "five minutes is far outside the window, so the tremor override must " +
            "treat this speed as unknown rather than as proof the device is parked (#404)")
        // Not an arbitrary bound: it pins that the age is the *fix's* age and
        // not the time since the engine happened to be handed it.
        XCTAssertEqual(age ?? 0, 300, accuracy: 5)
    }

    /// The gate's constant is shared rather than duplicated, so the tremor
    /// override and the pace sink cannot drift apart — which is how they came
    /// to disagree in the first place.
    func testTheAgeLimitIsTheOneThePaceSinkUses() {
        XCTAssertEqual(
            LocationEngine.maximumPaceFixAge, 10,
            "ten seconds: longer than any live fix interval, far shorter than the " +
            "gap a cached fix survives across a stationary period")
    }
}

private final class PaceAgeLocationManager: CLLocationManager {
    private var allowsBackground = false
    private var storedDistanceFilter: CLLocationDistance = kCLDistanceFilterNone

    override var allowsBackgroundLocationUpdates: Bool {
        get { allowsBackground }
        set { allowsBackground = newValue }
    }

    override var distanceFilter: CLLocationDistance {
        get { storedDistanceFilter }
        set { storedDistanceFilter = newValue }
    }

    override var authorizationStatus: CLAuthorizationStatus { .authorizedAlways }

    override func startUpdatingLocation() {}
    override func stopUpdatingLocation() {}
    override func startMonitoringSignificantLocationChanges() {}
    override func stopMonitoringSignificantLocationChanges() {}
}

private final class PaceAgeEventSender: TraceletEventSending {
    func sendLocation(_ data: [String: Any]) {}
    func sendMotionChange(_ data: [String: Any]) {}
    func sendActivityChange(_ data: [String: Any]) {}
    func sendProviderChange(_ data: [String: Any]) {}
    func sendGeofence(_ data: [String: Any]) {}
    func sendGeofencesChange(_ data: [String: Any]) {}
    func sendHeartbeat(_ data: [String: Any]) {}
    func sendHttp(_ data: [String: Any]) {}
    func sendSchedule(_ data: [String: Any]) {}
    func sendPowerSaveChange(_ isPowerSave: Bool) {}
    func sendConnectivityChange(_ data: [String: Any]) {}
    func sendEnabledChange(_ enabled: Bool) {}
    func sendNotificationAction(_ data: [String: Any]) {}
    func sendAuthorization(_ data: [String: Any]) {}
    func sendWatchPosition(_ data: [String: Any]) {}
    func sendRemoteConfigEvent(_ data: [String: Any]) {}
    func sendTrip(_ data: [String: Any]) {}
    func sendBudgetAdjustment(_ data: [String: Any]) {}
    func sendSpeedMotionEvent(_ data: [String: Any]) {}
    func sendDrivingEvent(_ data: [String: Any]) {}
    func sendImpact(_ data: [String: Any]) {}
    func sendModeChange(_ data: [String: Any]) {}
    func hasListener(eventName: String) -> Bool { false }
}
