import Foundation
import CoreLocation

struct SpeedSnapshot {
    var kmh: Float
    var valid: Bool
}

/// GPS 車速（對齊 Android VehicleSpeedProvider 簡化版）
final class SpeedProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var _snapshot = SpeedSnapshot(kmh: 0, valid: false)
    private let lock = NSLock()

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
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
        lock.lock()
        _snapshot = SpeedSnapshot(kmh: 0, valid: false)
        lock.unlock()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let speedMps = loc.speed // m/s；負值 = invalid
        lock.lock()
        if speedMps >= 0, loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 50 {
            _snapshot = SpeedSnapshot(kmh: Float(speedMps * 3.6), valid: true)
        } else if !(_snapshot.valid && speedMps < 0) {
            _snapshot = SpeedSnapshot(kmh: 0, valid: false)
        }
        lock.unlock()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse
            || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }
}
