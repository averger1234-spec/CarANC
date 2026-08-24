import SwiftUI

struct ContentView: View {
    @ObservedObject private var app = AppController.shared

    private var model: AncAppModel { app.model }
    private var engine: AncAudioEngine { app.engine }

    var body: some View {
        TabView(selection: Binding(
            get: { model.selectedTab },
            set: { model.selectedTab = $0 }
        )) {
            NavigationStack {
                StatusTabView(model: model, engine: engine)
                    .navigationTitle("CarANC")
            }
            .tabItem { Label("狀態", systemImage: "house.fill") }
            .tag(0)

            NavigationStack {
                PlanTabView(model: model, engine: engine)
            }
            .tabItem { Label("方案", systemImage: "cart.fill") }
            .tag(1)

            NavigationStack {
                GuidedTestView(model: model, engine: engine)
            }
            .tabItem { Label("測試腳本", systemImage: "list.bullet.clipboard.fill") }
            .tag(2)

            NavigationStack {
                TestLabView(model: model, engine: engine)
            }
            .tabItem { Label("測試平台", systemImage: "wrench.and.screwdriver.fill") }
            .tag(3)
        }
        // iPad `.sheet` 常把同意鈕捲出可視區或吞掉點擊；全螢幕 + 底部固定鈕才關得掉
        .fullScreenCover(isPresented: Binding(
            get: { model.showSafetyConsent },
            set: { newValue in
                if model.safetyConsentAccepted || newValue {
                    model.showSafetyConsent = newValue
                }
            }
        )) {
            SafetyConsentView(model: model)
                .interactiveDismissDisabled(true)
        }
    }
}

#Preview {
    ContentView()
}
