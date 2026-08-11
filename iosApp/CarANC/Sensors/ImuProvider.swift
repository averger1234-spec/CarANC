import Foundation
import CoreMotion

/// IMU 線加速度量級（路噪 / 輪噪 structural FF 代理）
final class ImuProvider {
    private let motion = CMMotionManager()
    private let queue = OperationQueue()
    private let lock = NSLock()
    private var _mag: Float = 0

    var linearAccelMagnitude: Float {
        lock.lock(); defer { lock.unlock() }
        return _mag
    }

    init() {
        queue.name = "com.caranc.imu"
        queue.qualityOfService = .userInitiated
    }

    func start() {
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 50.0
        motion.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            let u = data.userAcceleration
            let mag = sqrt(u.x * u.x + u.y * u.y + u.z * u.z)
            self.lock.lock()
            // EMA
            self._mag = self._mag * 0.85 + Float(mag) * 0.15
            self.lock.unlock()
        }
    }

    func stop() {
        motion.stopDeviceMotionUpdates()
        lock.lock()
        _mag = 0
        lock.unlock()
    }
}
