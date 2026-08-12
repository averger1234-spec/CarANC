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
        .sheet(isPresented: Binding(
            get: { model.showSafetyConsent },
            set: { model.showSafetyConsent = $0 }
        )) {
            SafetyConsentView(model: model)
                .interactiveDismissDisabled(true)
        }
    }
}

#Preview {
    ContentView()
}
