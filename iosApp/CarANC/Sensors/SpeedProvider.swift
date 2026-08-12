import Foundation
import CoreLocation

struct SpeedSnapshot {
    var kmh: Float
    /// 可餵 DSP / SpeedScheduled（gps | hold | imu_proxy）
    var valid: Bool
    /// 較嚴路測：gps 或新鮮 hold
    var validForRoadTest: Bool
    /// gps | gps_hold | imu_proxy | none
    var source: String
    var holdAgeSec: Float
    var imuProxyKmh: Float
    var rawGpsValid: Bool
}

/// GPS 車速 + **掉線保持** + **IMU 代車速**（對齊 shared `VehicleSpeedFusion`）
final class SpeedProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var _snapshot = SpeedSnapshot(
        kmh: 0, valid: false, validForRoadTest: false,
        source: "none", holdAgeSec: -1, imuProxyKmh: 0, rawGpsValid: false
    )
    private let lock = NSLock()

    // fusion state（對齊 VehicleSpeedFusionState）
    private var lastGoodGpsKmh: Float = 0
    private var lastGoodGpsAt: TimeInterval = 0
    private var smoothedOut: Float = 0
    private var accelEma: Float = 0
    private var motionEma: Float = 0
    /// 最近一次 CoreLocation 回報時間；超過 [gpsStaleSec] 視為掉線 → hold
    private var lastLocationAt: TimeInterval = 0
    private var lastRawGpsKmh: Float = 0
    private var lastRawGpsSampleValid = false

    private let holdMaxSec: Float = 25
    private let holdDecayPerSec: Float = 0.012
    private let holdMinKmh: Float = 8
    private let imuProxyMax: Float = 75
    private let gpsStaleSec: TimeInterval = 2.5

    /// 外部注入 IMU 量級（AncAudioEngine 每 tick 更新）
    private var latestAccel: Float = 0

    var snapshot: SpeedSnapshot {
        lock.lock(); defer { lock.unlock() }
        return _snapshot
    }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start() {
        let status = manager.authorizationStatus
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        if status == .authorizedWhenInUse || status == .authorizedAlways || status == .notDetermined {
            manager.startUpdatingLocation()
        }
        // 無 GPS 權限時仍可靠 IMU 代速
        recompute(gpsKmh: 0, gpsValid: false)
    }

    func stop() {
        manager.stopUpdatingLocation()
        lock.lock()
        _snapshot = SpeedSnapshot(
            kmh: 0, valid: false, validForRoadTest: false,
            source: "none", holdAgeSec: -1, imuProxyKmh: 0, rawGpsValid: false
        )
        lastGoodGpsKmh = 0
        lastGoodGpsAt = 0
        smoothedOut = 0
        accelEma = 0
        motionEma = 0
        lock.unlock()
    }

    /// 由引擎每 UI tick 帶入 IMU（無新 GPS 時也能 hold / imu_proxy）
    func tickWithAccel(_ mag: Float) {
        latestAccel = max(0, mag)
        let now = Date().timeIntervalSince1970
        lock.lock()
        let fresh = lastLocationAt > 0 && (now - lastLocationAt) <= gpsStaleSec
        let gpsValid = fresh && lastRawGpsSampleValid
        let gpsKmh = gpsValid ? lastRawGpsKmh : 0
        lock.unlock()
        recompute(gpsKmh: gpsKmh, gpsValid: gpsValid)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let speedMps = loc.speed
        let acc = loc.horizontalAccuracy
        let gpsValid = speedMps >= 0 && acc >= 0 && acc < 50
        let kmh = gpsValid ? Float(speedMps * 3.6) : 0
        lock.lock()
        lastLocationAt = Date().timeIntervalSince1970
        lastRawGpsSampleValid = gpsValid
        lastRawGpsKmh = kmh
        lock.unlock()
        recompute(gpsKmh: kmh, gpsValid: gpsValid)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse
            || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    private func recompute(gpsKmh: Float, gpsValid: Bool) {
        let now = Date().timeIntervalSince1970
        let accel = latestAccel
        lock.lock()
        defer { lock.unlock() }

        accelEma = accelEma <= 0 ? accel : accelEma * 0.88 + accel * 0.12
        let motion: Float = accelEma > 0.12 ? 1 : 0
        motionEma = motionEma * 0.92 + motion * 0.08
        let imuProxy = Self.imuProxyKmh(accelEma: accelEma, motionEma: motionEma, maxKmh: imuProxyMax)

        if gpsValid && gpsKmh >= 0 {
            smoothedOut = smoothedOut <= 0 ? gpsKmh : smoothedOut * 0.75 + gpsKmh * 0.25
            lastGoodGpsKmh = smoothedOut
            lastGoodGpsAt = now
            _snapshot = SpeedSnapshot(
                kmh: smoothedOut,
                valid: true,
                validForRoadTest: true,
                source: "gps",
                holdAgeSec: 0,
                imuProxyKmh: imuProxy,
                rawGpsValid: true
            )
            return
        }

        let age: Float = lastGoodGpsAt > 0 ? Float(now - lastGoodGpsAt) : .greatestFiniteMagnitude
        if lastGoodGpsKmh >= holdMinKmh && age <= holdMaxSec {
            let decay = max(0.55, min(1, 1 - holdDecayPerSec * age))
            let vibBoost: Float = motionEma > 0.35 ? 1 : 0.85
            var hold = lastGoodGpsKmh * decay * vibBoost
            if imuProxy > hold + 8 && motionEma > 0.4 {
                hold = hold * 0.7 + imuProxy * 0.3
            }
            hold = min(130, max(0, hold))
            smoothedOut = hold
            _snapshot = SpeedSnapshot(
                kmh: hold,
                valid: true,
                validForRoadTest: age <= 12,
                source: "gps_hold",
                holdAgeSec: age,
                imuProxyKmh: imuProxy,
                rawGpsValid: false
            )
            return
        }

        if imuProxy >= 12 && motionEma > 0.28 {
            smoothedOut = smoothedOut > 5 ? smoothedOut * 0.85 + imuProxy * 0.15 : imuProxy
            _snapshot = SpeedSnapshot(
                kmh: smoothedOut,
                valid: true,
                validForRoadTest: false,
                source: "imu_proxy",
                holdAgeSec: age.isFinite ? age : -1,
                imuProxyKmh: imuProxy,
                rawGpsValid: false
            )
            return
        }

        smoothedOut *= 0.9
        _snapshot = SpeedSnapshot(
            kmh: 0,
            valid: false,
            validForRoadTest: false,
            source: "none",
            holdAgeSec: age.isFinite ? age : -1,
            imuProxyKmh: imuProxy,
            rawGpsValid: false
        )
    }

    private static func imuProxyKmh(accelEma: Float, motionEma: Float, maxKmh: Float) -> Float {
        if motionEma < 0.15 || accelEma < 0.08 { return 0 }
        let base: Float
        if accelEma < 0.15 {
            base = 5 + accelEma * 40
        } else if accelEma < 0.35 {
            base = 18 + (accelEma - 0.15) * 90
        } else if accelEma < 0.70 {
            base = 36 + (accelEma - 0.35) * 55
        } else if accelEma < 1.20 {
            base = 55 + (accelEma - 0.70) * 30
        } else {
            base = 70 + min(10, (accelEma - 1.20) * 8)
        }
        let scaled = base * (0.55 + 0.45 * min(1, max(0, motionEma)))
        return min(maxKmh, max(0, scaled))
    }
}
