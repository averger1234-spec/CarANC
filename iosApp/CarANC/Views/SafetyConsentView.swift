import SwiftUI

struct SafetyConsentView: View {
    @ObservedObject var model: AncAppModel
    @State private var marketing = false

    private let bullets = [
        "CarANC 為輔助降噪工具，不能取代車廠原廠 ANC 或安全駕駛判斷。",
        "請在車輛靜止或安全情境下完成校正；行駛中請以交通安全為優先。",
        "本 App 需要麥克風、定位權限以提供降噪功能。",
        "若感到暈眩、耳壓不適或聽不見警報聲，請立即停止降噪。",
        "支援本機喇叭與 CarPlay 車機路徑（對齊 Android Auto：手機 DSP、車機輸出）。建議優先有線 CarPlay。"
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("安全與使用聲明")
                            .font(.title2.bold())
                        Text("請閱讀並同意後再啟動主動降噪。")
                            .foregroundStyle(.secondary)

                        ForEach(Array(bullets.enumerated()), id: \.offset) { _, line in
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: "exclamationmark.shield.fill")
                                    .foregroundStyle(.orange)
                                Text(line)
                                    .font(.body)
                            }
                        }

                        Toggle("願意接收產品更新資訊（可選）", isOn: $marketing)
                            .padding(.top, 8)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button(action: accept) {
                    Text("我已閱讀並同意")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .contentShape(Rectangle())
                .padding(.horizontal)
                .padding(.bottom, 16)
                .background(.bar)
            }
            .navigationTitle("CarANC")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func accept() {
        model.acceptSafetyConsent(marketingOptIn: marketing)
        SessionLogger.shared.event("safety_consent_accepted", [
            "marketingOptIn": "\(marketing)",
            "platform": "ios"
        ])
    }
}
