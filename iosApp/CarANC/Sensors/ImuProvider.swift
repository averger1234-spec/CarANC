import Foundation
import CoreMotion

/// IMU 線加速度量級（路噪 / 輪噪 structural FF 代理）
final class ImuProvider {
    private let motion = CMMotionManager()
    private let queue = OperationQueue()
    private let lock = NSLock()
    private var _mag: Float = 0
    private var _ax: Float = 0
    private var _ay: Float = 0
    private var _az: Float = 0

    var linearAccelMagnitude: Float {
        lock.lock(); defer { lock.unlock() }
        return _mag
    }

    /// 1.2.8+ 三軸 userAcceleration（供 KMP setImuAxes）
    var axes: (Float, Float, Float) {
        lock.lock(); defer { lock.unlock() }
        return (_ax, _ay, _az)
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
            let ax = Float(u.x)
            let ay = Float(u.y)
            let az = Float(u.z)
            let mag = sqrt(ax * ax + ay * ay + az * az)
            self.lock.lock()
            // EMA
            self._mag = self._mag * 0.85 + mag * 0.15
            self._ax = self._ax * 0.85 + ax * 0.15
            self._ay = self._ay * 0.85 + ay * 0.15
            self._az = self._az * 0.85 + az * 0.15
            self.lock.unlock()
        }
    }

    func stop() {
        motion.stopDeviceMotionUpdates()
        lock.lock()
        _mag = 0
        _ax = 0
        _ay = 0
        _az = 0
        lock.unlock()
    }
}
