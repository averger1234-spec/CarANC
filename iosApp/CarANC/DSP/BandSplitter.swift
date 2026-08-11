import Foundation

/// 2nd-order Linkwitz-Riley 三頻段分割（對齊 Android BandSplitter：250 / 800 Hz）
final class BandSplitter {
    struct Bands {
        var low: Float
        var mid: Float
        var high: Float
    }

    private let cLowMid: Float
    private let cMidHigh: Float
    private var lowA: Float = 0
    private var lowB: Float = 0
    private var midA: Float = 0
    private var midB: Float = 0

    init(sampleRate: Int) {
        cLowMid = Self.coeff(freqHz: 250, sampleRate: sampleRate)
        cMidHigh = Self.coeff(freqHz: 800, sampleRate: sampleRate)
    }

    func split(_ x: Float) -> Bands {
        lowA += cLowMid * (x - lowA)
        lowB += cLowMid * (lowA - lowB)
        let low = clamp(lowB)

        let hp1 = x - lowB
        midA += cMidHigh * (hp1 - midA)
        midB += cMidHigh * (midA - midB)
        let mid = clamp(midB)
        let high = clamp(hp1 - midB)
        return Bands(low: low, mid: mid, high: high)
    }

    func reset() {
        lowA = 0; lowB = 0; midA = 0; midB = 0
    }

    private static func coeff(freqHz: Float, sampleRate: Int) -> Float {
        let c = Float(2.0 * Double.pi * Double(freqHz) / Double(sampleRate))
        return min(max(c, 0.003), 0.2)
    }

    private func clamp(_ v: Float) -> Float {
        min(max(v, -1), 1)
    }
}
