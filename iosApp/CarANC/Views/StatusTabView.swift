import SwiftUI

struct StatusTabView: View {
    @ObservedObject var model: AncAppModel
    @ObservedObject var engine: AncAudioEngine
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                headerCard
                metricsRow
                SpectrumBarsView(values: model.noiseSpectrum, color: .orange, title: "麥克風頻譜")
                SpectrumBarsView(values: model.antiSpectrum, color: .cyan, title: "反噪音輸出")
                infoBlock
                controls
                advancedToggle
            }
            .padding()
        }
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle()
                    .fill(model.isRunning ? Color.green : Color.gray)
                    .frame(width: 12, height: 12)
                Text(model.isRunning ? "運作中" : "已停止")
                    .font(.headline)
                Spacer()
                // 對齊 Android 主畫面 `v{VERSION_NAME}` — 路測對版用
                Text(AppVersion.display)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                Text(model.tier.shortLabel)
                    .font(.caption.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.blue.opacity(0.15))
                    .clipShape(Capsule())
            }
            Text(model.statusText)
                .font(.title3.weight(.semibold))
            Text(model.nvhFocus.displayName)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("\(AppVersion.platformLabel) · \(AppVersion.shortDisplay) · build \(AppVersion.build)")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Color.blue.opacity(0.18), Color.cyan.opacity(0.08)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var metricsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                MetricPill(label: "輸入 dB", value: String(format: "%.1f", model.rawDb))
                MetricPill(label: "反噪 dB", value: String(format: "%.1f", model.antiDb))
                MetricPill(label: "低頻 KPI", value: String(format: "%.2f", model.lowBandRumbleReduction))
                MetricPill(label: "IMU", value: String(format: "%.2f", model.rumbleAccel))
            }
        }
    }

    private var infoBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.speedText)
            Text(model.latencyText)
            HStack(spacing: 6) {
                Image(systemName: model.carPlayConnected ? "car.fill" : "iphone")
                Text(model.carPlayConnected
                     ? "CarPlay 已連線 · \(model.aaLinkType)"
                     : "本機路徑 · \(model.aaLinkType)")
            }
            .foregroundStyle(model.carPlayConnected ? Color.green : Color.secondary)
            Text("產品目標：路噪 · 輪噪 · 風切（高頻不追消）· 車機對齊 AA")
                .font(.caption)
                .foregroundStyle(.secondary)
            if model.showAdvanced {
                Text(String(format: "strategy maxCancel=%.0f mid=%@ high=%@ wireless=%@",
                            model.maxCancelHz,
                            model.midEnabled ? "on" : "off",
                            model.highEnabled ? "on" : "off",
                            model.wirelessCarPlaySuspected ? "yes" : "no"))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
        .font(.subheadline)
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                Task { await toggleStart() }
            } label: {
                Label(
                    model.isRunning ? "停止降噪" : "開始降噪",
                    systemImage: model.isRunning ? "stop.fill" : "waveform.circle.fill"
                )
                .frame(maxWidth: .infinity)
                .padding()
            }
            .buttonStyle(.borderedProminent)
            .tint(model.isRunning ? .red : .blue)
            .disabled(busy)
        }
    }

    private var advancedToggle: some View {
        Toggle("顯示工程資訊", isOn: $model.showAdvanced)
            .font(.subheadline)
    }

    @MainActor
    private func toggleStart() async {
        busy = true
        defer { busy = false }
        let app = AppController.shared
        if model.isRunning {
            app.stopAnc()
            return
        }
        if let err = await app.startAnc() {
            model.phase = .error
            model.lastError = err
            model.isRunning = false
        }
    }
}
