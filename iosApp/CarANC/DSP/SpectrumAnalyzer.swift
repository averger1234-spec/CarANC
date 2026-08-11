import Foundation
import Accelerate

/// 簡易頻譜條（UI 用），對數頻段能量
final class SpectrumAnalyzer {
    let barCount: Int
    private let fftLog2n: vDSP_Length
    private let fftSize: Int
    private var window: [Float]
    private var real: [Float]
    private var imag: [Float]
    private var magnitudes: [Float]
    private let setup: FFTSetup?

    init(barCount: Int = 32, fftSize: Int = 512) {
        self.barCount = barCount
        self.fftSize = fftSize
        self.fftLog2n = vDSP_Length(log2(Float(fftSize)))
        self.window = [Float](repeating: 0, count: fftSize)
        self.real = [Float](repeating: 0, count: fftSize)
        self.imag = [Float](repeating: 0, count: fftSize)
        self.magnitudes = [Float](repeating: 0, count: fftSize / 2)
        self.setup = vDSP_create_fftsetup(fftLog2n, FFTRadix(kFFTRadix2))
        // Hann window
        vDSP_hann_window(&window, vDSP_Length(fftSize), Int32(vDSP_HANN_NORM))
    }

    deinit {
        if let setup { vDSP_destroy_fftsetup(setup) }
    }

    /// - Parameter samples: 正規化 -1…1
    func analyze(_ samples: [Float]) -> [Float] {
        guard let setup, samples.count >= 32 else {
            return [Float](repeating: 0, count: barCount)
        }
        let n = min(samples.count, fftSize)
        var input = [Float](repeating: 0, count: fftSize)
        for i in 0..<n { input[i] = samples[i] * window[i] }

        real = input
        imag = [Float](repeating: 0, count: fftSize)

        real.withUnsafeMutableBufferPointer { rBuf in
            imag.withUnsafeMutableBufferPointer { iBuf in
                var split = DSPSplitComplex(realp: rBuf.baseAddress!, imagp: iBuf.baseAddress!)
                vDSP_fft_zip(setup, &split, 1, fftLog2n, FFTDirection(FFT_FORWARD))
            }
        }

        for i in 0..<(fftSize / 2) {
            let re = real[i]
            let im = imag[i]
            magnitudes[i] = sqrt(re * re + im * im) + 1e-9
        }

        // 對數映射到 barCount 條
        var bars = [Float](repeating: 0, count: barCount)
        let half = fftSize / 2
        for b in 0..<barCount {
            let t0 = Float(b) / Float(barCount)
            let t1 = Float(b + 1) / Float(barCount)
            // log freq: 20Hz..Nyquist 近似用 index^2 映射
            let i0 = max(1, Int(pow(t0, 1.6) * Float(half - 1)))
            let i1 = max(i0 + 1, Int(pow(t1, 1.6) * Float(half - 1)))
            var sum: Float = 0
            let end = min(i1, half)
            for i in i0..<end { sum += magnitudes[i] }
            let avg = sum / Float(max(1, end - i0))
            // dB 正規化到 0…1
            let db = 20 * log10(avg)
            bars[b] = min(max((db + 80) / 80, 0), 1)
        }
        return bars
    }

    static func rmsDb(_ samples: [Float]) -> Float {
        guard !samples.isEmpty else { return -90 }
        var sum: Float = 0
        for s in samples { sum += s * s }
        let rms = sqrt(sum / Float(samples.count))
        return 20 * log10(max(rms, 1e-7))
    }
}
