import SwiftUI

struct SafetyConsentView: View {
    @ObservedObject var model: AncAppModel
    @State private var marketing = false

    private let bullets = [
        "CarANC 為輔助降噪工具，不能取代車廠原廠 ANC 或安全駕駛判斷。",
        "請在車輛靜止或安全情境下完成校正；行駛中請以交通安全為優先。",
        "本 App 需要麥克風、定位權限以提供降噪功能。",
        "若感到暈眩、耳壓不適或聽不見警報聲，請立即停止降噪。",
        "iOS 版目前為本機喇叭路徑；CarPlay 音訊路由為後續版本。"
    ]

    var body: some View {
        NavigationStack {
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

                    Button {
                        model.acceptSafetyConsent(marketingOptIn: marketing)
                    } label: {
                        Text("我已閱讀並同意")
                            .frame(maxWidth: .infinity)
                            .padding()
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)
                }
                .padding()
            }
            .navigationTitle("CarANC")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
