import Foundation

/// 單頻段 FxLMS（對齊 Android BandFxLms 核心：y = w·x，filtered-x 含 plant delay）
final class BandFxLms {
    let label: String
    let centerHz: Float
    var baseMuScale: Float
    var filterLength: Int
    var baseMu: Float
    var acousticDelaySamples: Int = 0
    var leakage: Float = 0.9998

    private let maxFilterLength = 512
    private let bufferSize = 16384
    private let bufferMask: Int
    private let sHatLength = 64

    private var w: [Float]
    private var xBuffer: [Float]
    private var filteredXBuffer: [Float]
    private var yBuffer: [Float]
    private var bufferIndex = 0
    private var sHat: [Float]

    private(set) var lmsProcessCalls: Int64 = 0
    private(set) var lmsUpdateCount: Int64 = 0
    private(set) var lastLmsPfx: Float = 0
    private(set) var lastMuScale: Float = 0

    init(label: String, centerHz: Float, baseMuScale: Float, filterLength: Int, baseMu: Float) {
        self.label = label
        self.centerHz = centerHz
        self.baseMuScale = baseMuScale
        self.filterLength = min(filterLength, maxFilterLength)
        self.baseMu = baseMu
        self.bufferMask = bufferSize - 1
        self.w = [Float](repeating: 0, count: maxFilterLength)
        self.xBuffer = [Float](repeating: 0, count: bufferSize)
        self.filteredXBuffer = [Float](repeating: 0, count: bufferSize)
        self.yBuffer = [Float](repeating: 0, count: bufferSize)
        self.sHat = [Float](repeating: 0, count: sHatLength)
        self.sHat[0] = 1
    }

    func configure(filterLength: Int, baseMu: Float, leakage: Float) {
        self.filterLength = min(max(filterLength, 32), maxFilterLength)
        self.baseMu = baseMu
        self.leakage = min(max(leakage, 0.99), 0.99999)
    }

    func bindSecondaryPath(_ model: [Float]) {
        for i in 0..<min(sHatLength, model.count) {
            sHat[i] = model[i]
        }
    }

    func reset() {
        w = [Float](repeating: 0, count: maxFilterLength)
        xBuffer = [Float](repeating: 0, count: bufferSize)
        filteredXBuffer = [Float](repeating: 0, count: bufferSize)
        yBuffer = [Float](repeating: 0, count: bufferSize)
        bufferIndex = 0
        lmsProcessCalls = 0
        lmsUpdateCount = 0
    }

    /// - Returns: controller output y（anti-noise 前為 -y）
    func processSample(sample: Float, muScale: Float, freezeUpdates: Bool, errorSample: Float) -> Float {
        xBuffer[bufferIndex] = sample
        lmsProcessCalls += 1
        lastMuScale = muScale

        var y: Float = 0
        let fl = filterLength
        for j in 0..<fl {
            let idx = (bufferIndex - j) & bufferMask
            y += w[j] * xBuffer[idx]
        }

        // Filtered-x：plant delay D + 短 IR ŝ
        var filteredX: Float = 0
        let d = min(max(acousticDelaySamples, 0), bufferMask)
        for j in 0..<sHatLength {
            let idx = (bufferIndex - d - j) & bufferMask
            filteredX += sHat[j] * xBuffer[idx]
        }
        filteredXBuffer[bufferIndex] = filteredX
        yBuffer[bufferIndex] = y

        if !freezeUpdates && muScale > 0 {
            var pfx: Float = 0
            for j in 0..<fl {
                let idx = (bufferIndex - j) & bufferMask
                let fx = filteredXBuffer[idx]
                pfx += fx * fx
            }
            lastLmsPfx = pfx
            let denom = pfx + 1e-6
            let currentMu = min(max(baseMu * muScale, 1e-6), 0.12)
            let muNorm = currentMu / denom
            let e = errorSample
            // Leaky LMS: w = α w + μ e x'
            for j in 0..<fl {
                let idx = (bufferIndex - j) & bufferMask
                let fx = filteredXBuffer[idx]
                var nw = leakage * w[j] + muNorm * e * fx
                // gradient clip
                let dw = nw - w[j]
                if dw > 0.05 { nw = w[j] + 0.05 }
                if dw < -0.05 { nw = w[j] - 0.05 }
                w[j] = min(max(nw, -2), 2)
            }
            lmsUpdateCount += 1
        }

        bufferIndex = (bufferIndex + 1) & bufferMask
        return y
    }
}
