import Foundation
import CoreLocation

/// Coordinates between MotionDetector (Accelerometer/ActivityRecognition) and
/// SpeedMotionManager (GPS Speed) to implement the 'smart' MotionDetectionMode.
///
/// Implements a Logical OR:
/// If EITHER sensor detects motion, the app stays in continuous tracking mode.
/// If BOTH sensors detect stationary, the SDK applies the configured stationary
/// tracking mode.
public class TraceletSmartMotionCoordinator {
    
    private var coreCoordinator: SmartMotionCoordinator?
    
    public var isAccelMoving: Bool { coreCoordinator?.isAccelMoving() ?? false }
    public var isSpeedMoving: Bool { coreCoordinator?.isSpeedMoving() ?? true }
    
    private weak var sdk: TraceletSdk?
    
    public init(sdk: TraceletSdk) {
        self.sdk = sdk
        
        let useGeofences = sdk.configManager?.getStationaryTrackingMode() == .geofences
        self.coreCoordinator = SmartMotionCoordinator(useGeofencesWhenStationary: useGeofences)
    }
    
    /// Synchronize the Rust core mode with the native StateManager on startup or mode change.
    ///
    /// `stateManager.trackingMode` and the coordinator's `currentMode` measure
    /// different things, and conflating them wedged the coordinator (#344):
    ///
    /// * `trackingMode` is the **session** mode — which start API was called. It
    ///   is set to `.continuous` by `start()` and stays there for the whole
    ///   session; `switchToStationaryPeriodicForce()` deliberately leaves it
    ///   alone and records the pace in `stateManager.isMoving` instead.
    /// * `currentMode` is the **posture** — whether the engine is running
    ///   continuous GPS right now or is parked in a stationary schedule.
    ///
    /// `start()` sets `isMoving` from `motion.isMoving` (false by default) and
    /// then calls this, so mapping `.continuous -> .continuous` wrote
    /// `Continuous` into a coordinator whose inputs both said stationary. The
    /// core's `evaluate_state` emits no action for that pair in either
    /// direction: a moving accelerometer sees `currentMode == .continuous` and
    /// returns `.none`, so `switchToContinuousForce()` never ran and the session
    /// stayed parked — no fixes recorded, nothing to sync — until the process
    /// was killed. Reading the posture off `isMoving` keeps the two in step, and
    /// the first real accel or speed event produces the wake-up.
    ///
    /// #409 is the same wedge from the other side: `isMoving` false with a *live*
    /// stream parked the coordinator while the engine kept streaming, and
    /// `evaluate_state` returns `.none` for that pair too. The posture is now the
    /// OR of the committed pace and the engine's actual state — the only reading
    /// that survives both directions.
    ///
    /// The engine's state is `isContinuousStreaming`, not `isTracking`: the
    /// speed/smart pipeline parks by stopping continuous updates while leaving
    /// `isTracking` true so delegate callbacks keep arriving, so `isTracking`
    /// reads `true` on a parked engine. ORing *that* in writes Continuous into a
    /// coordinator that is genuinely parked, and the core emits no wake-up for a
    /// posture it already believes is Continuous — #344's swallowed shake,
    /// re-entered through the #409 fix.
    /// Re-reads the posture once ``TraceletSdk/start(isResume:)`` has settled the
    /// engine, and lets the core act on what it finds.
    ///
    /// ``syncCurrentMode()`` runs *before* start() touches the engine, so it can
    /// only ever read the pace committed a few lines earlier. Anything that opens
    /// a stream afterwards leaves the coordinator holding a posture that is
    /// merely a prediction — and when the prediction is wrong the core stays
    /// silent, because `evaluate_state` speaks only on a *transition* and both
    /// motion inputs are already where they were.
    ///
    /// The field report is a session that resumed while a fence too small for the
    /// OS to resolve needed in-app evaluation: the #357 branch starts a stream so
    /// the fence has something to be decided from, and a parked coordinator then
    /// has nothing it can say that would ever stop it. What the device shows is
    /// continuous GPS — every fix `isMoving: false`, at the configured interval,
    /// with the location indicator lit — for the rest of the session.
    ///
    /// Reconciling asks the core to judge the state it is actually in rather than
    /// waiting for a transition that will never come. A stationary session that
    /// inherited a stream is parked into the stationary schedule, which still
    /// feeds the in-app fence evaluator at the stationary cadence; a moving one
    /// is left streaming (#409).
    public func reconcilePosture() {
        syncCurrentMode()
        guard let core = coreCoordinator else { return }
        let useGeofences = sdk?.configManager?.getStationaryTrackingMode() == .geofences
        let action = core.evaluateConfigurationChange(useGeofences: useGeofences)
        // Always-on: once per start(), and it is the entry that answers "why is
        // GPS still running" — it names both motion inputs and the engine at the
        // one moment the session re-judges them. A release build runs at the
        // default logLevel, where a `debug` line is discarded and the question is
        // unanswerable from the report (#414).
        TraceletLog.lifecycle(
            "smart-motion: reconciled the posture — action=\(action) "
                + "accelMoving=\(isAccelMoving) speedMoving=\(isSpeedMoving) "
                + "streaming=\(sdk?.locationEngine?.isContinuousStreaming ?? false)")
        handleAction(action, trigger: "reconcile")
    }

    public func syncCurrentMode() {
        guard let stateManager = sdk?.stateManager else { return }

        let useGeofences = sdk?.configManager?.getStationaryTrackingMode() == .geofences
        let engine = sdk?.locationEngine
        coreCoordinator?.setCurrentMode(
            mode: TraceletSmartMotionCoordinator.coordinatorMode(
                sessionMode: stateManager.trackingMode,
                isMoving: stateManager.isMoving,
                // A low-accuracy continuous stream is already in the stationary posture.
                isStreamLive: engine?.isContinuousStreaming == true
                    && engine?.isStationaryContinuousTracking == false,
                useGeofencesWhenStationary: useGeofences))
        coreCoordinator?.setUseGeofencesWhenStationary(useGeofences: useGeofences)
    }

    /// The posture the coordinator should be holding, given the session mode, the
    /// committed pace, and whether the engine is streaming right now. Pure so the
    /// mappings in #344 and #409 can be pinned directly.
    static func coordinatorMode(
        sessionMode: TraceletTrackingMode,
        isMoving: Bool,
        isStreamLive: Bool,
        useGeofencesWhenStationary: Bool
    ) -> TrackingMode {
        let stationary: TrackingMode = useGeofencesWhenStationary ? .stationaryGeofences : .stationaryPeriodic
        switch sessionMode {
        case .continuous:
            // `isMoving` predicts what start() is *about* to do; `isStreamLive`
            // reports what the engine is doing *now*. Either alone gets a case
            // wrong, so the posture is the OR of them (#409).
            //
            // `isMoving` alone was the wedge. start() syncs before it touches the
            // engine, so a session resuming stationary while a stream was still
            // live wrote a stationary posture into a coordinator whose engine was
            // streaming — and `evaluate_state` only emits the stop action when it
            // believes the posture is `.continuous`. Every later stationary
            // decision returned `.none` and GPS ran at the configured interval for
            // the rest of the session, on a device correctly reported as parked.
            //
            // `isStreamLive` alone breaks #344's direction: this runs before
            // start() starts the engine, so a moving start would sync stationary
            // and then stream — the same wedge, mirrored.
            return (isMoving || isStreamLive) ? .continuous : stationary
        case .geofences:
            return .stationaryGeofences
        case .periodic:
            return .stationaryPeriodic
        }
    }
    
    /// Called when the accelerometer/activity recognition state changes.
    /// Returns the action taken so the caller can conditionally reset
    /// the speed state machine on real wake-ups.
    @discardableResult
    public func onAccelStateChange(isMoving: Bool) -> CoordinatorAction {
        guard let action = coreCoordinator?.onAccelStateChange(isMoving: isMoving) else { return .none }
        TraceletLog.debug("[Tracelet] SmartMotionCoordinator: onAccelStateChange -> isMoving=\(isMoving), action=\(action)")
        handleAction(action, trigger: "accel")
        return action
    }
    
    /// GPS speed at or below which a *moving* accelerometer is read as hand
    /// tremor on a physically still device rather than as real motion.
    private static let tremorSpeedThreshold: Double = 0.15

    private var resolvedSpeed: Double? {
        guard let sample = sdk?.locationEngine?.lastPaceSample else { return nil }
        let accuracy = sample.location.horizontalAccuracy
        let threshold = sdk?.configManager?.getTrackingAccuracyThreshold() ?? 0
        // A rejected-quality fix must not replace missing evidence with a parked verdict.
        guard sample.age >= 0, sample.age <= LocationEngine.maximumPaceFixAge,
              sample.speed.isFinite, sample.speed >= 0,
              sample.filterReason == nil || sample.filterReason == "DISTANCE_FILTER" || sample.filterReason == "SPARSE_FILTER",
              accuracy.isFinite, accuracy > 0,
              threshold <= 0 || accuracy <= Double(threshold) else { return nil }
        return sample.speed
    }

    // Keep the triggering pace input in switch logs so field reports can distinguish false speed from sensor motion.
    private var speedEvidence: String {
        guard let sample = sdk?.locationEngine?.lastPaceSample else {
            return "lastSpeed=unknown paceSample=none"
        }
        let location = sample.location
        let source = sample.source
        let speedText = resolvedSpeed.map { String(format: "%.2f", $0) } ?? "unknown"
        return "lastSpeed=\(speedText)m/s paceSpeed=\(sample.speed)m/s speedSource=\(source) "
            + "rawSpeed=\(location.speed) speedAccuracy=\(location.speedAccuracy) "
            + "fixAgeMs=\(String(format: "%.0f", sample.age * 1000)) accuracyM=\(location.horizontalAccuracy) provider=core_location "
            + "filterReason=\(sample.filterReason ?? "accepted")"
    }

    /// Returns the action taken, mirroring ``onAccelStateChange(isMoving:)``.
    /// Discardable — every existing caller ignores it — but it is the only
    /// externally visible product of this call, so a test has nothing else to
    /// assert the speed wake-up on.
    @discardableResult
    public func onSpeedStateChange(isMoving: Bool) -> CoordinatorAction {
        var overrideAction: CoordinatorAction = .none
        if !isMoving && isAccelMoving {
            let enabled = sdk?.configManager?.getEnableGpsTremorOverride() ?? true
            TraceletLog.lifecycle(
                "smart-motion: speed stationary while accel moving — "
                    + "enableGpsTremorOverride=\(enabled) \(speedEvidence)")
            // Low GPS speed cannot distinguish a slow walk from hand tremor.
            switch enabled ? resolvedSpeed : nil {
            case .none:
                TraceletLog.debug("[Tracelet] SmartMotionCoordinator: GPS tremor override disabled or no usable speed — trusting accel, staying continuous.")
            case .some(let speed) where speed <= TraceletSmartMotionCoordinator.tremorSpeedThreshold:
                TraceletLog.debug(String(format: "[Tracelet] SmartMotionCoordinator: GPS speed near zero (%.2f m/s) — overriding accel to false (hand tremor).", speed))
                // Handle the accel edge too: it may carry the actual stationary switch.
                overrideAction = coreCoordinator?.onAccelStateChange(isMoving: false) ?? .none
                handleAction(overrideAction, trigger: "gps_tremor_override")
            case .some(let speed):
                TraceletLog.debug(String(format: "[Tracelet] SmartMotionCoordinator: GPS speed %.2f m/s is above the %.2f m/s tremor threshold — trusting accel, staying continuous.",
                                         speed, TraceletSmartMotionCoordinator.tremorSpeedThreshold))
            }
        }
        guard let action = coreCoordinator?.onSpeedStateChange(isMoving: isMoving) else { return overrideAction }
        TraceletLog.debug("[Tracelet] SmartMotionCoordinator: onSpeedStateChange -> isMoving=\(isMoving), action=\(action)")
        handleAction(action, trigger: "speed")
        // The override above already switched the engine when the speed flag was
        // the one that was stale; reporting `.none` here would hide that from a
        // caller that reads the result.
        return action == .none ? overrideAction : action
    }
    
    /// Called when the user manually forces the pace via changePace().
    public func onManualPaceChange(isMoving: Bool) {
        if let accelAction = coreCoordinator?.onAccelStateChange(isMoving: isMoving) {
            handleAction(accelAction, trigger: "manual")
        }
        if let speedAction = coreCoordinator?.onSpeedStateChange(isMoving: isMoving) {
            handleAction(speedAction, trigger: "manual")
        }
    }
    
    private func handleAction(_ action: CoordinatorAction, trigger: String) {
        guard let sdk = sdk else { return }

        switch action {
        case .switchToContinuous:
            recordModeSwitch("CONTINUOUS", trigger: trigger)
            sdk.switchToContinuousForce()
            sdk.motionDetector.onManualPaceChange(true)

        case .switchToStationaryGeofences:
            recordModeSwitch("STATIONARY_GEOFENCES", trigger: trigger)
            sdk.switchToStationaryGeofencesForce()
            sdk.motionDetector.onManualPaceChange(false)

        case .switchToStationaryPeriodic:
            let keepsStreaming = sdk.configManager?.getStationaryTrackingMode() == .continuous
            recordModeSwitch(keepsStreaming ? "STATIONARY_CONTINUOUS" : "STATIONARY_PERIODIC", trigger: trigger)
            sdk.switchToStationaryPeriodicForce()
            sdk.motionDetector.onManualPaceChange(false)

        case .none:
            break
        }
    }

    /// Records a tracking-mode switch on the always-on lifecycle channel,
    /// naming both inputs of the OR (#334).
    ///
    /// The coordinator is a logical OR of the accelerometer and the GPS-speed
    /// machine, so a downgrade means *both* said stationary. Which one flipped
    /// last — and what the other was doing at the time — is the first question
    /// asked of any "it stopped tracking while I was moving" report, and at the
    /// shipped log levels the answer was not recorded anywhere.
    private func recordModeSwitch(_ mode: String, trigger: String) {
        TraceletLog.lifecycle(
            "smart-motion: switching to \(mode) — trigger=\(trigger) accelMoving=\(isAccelMoving) "
                + "speedMoving=\(isSpeedMoving) \(speedEvidence)")
    }
}
