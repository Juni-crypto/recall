import BackgroundTasks
import RecallCore
import SwiftUI
import UserNotifications

@main
struct RecallApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase
    @State private var model = AppModel.shared
    @State private var importMessage: String?

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .onOpenURL { url in handleOpen(url) }
                .alert(importMessage ?? "", isPresented: Binding(get: { importMessage != nil }, set: { if !$0 { importMessage = nil } })) {
                    Button("OK", role: .cancel) {}
                }
        }
        .onChange(of: phase) { _, now in
            guard now == .active else {
                if now == .background { Background.schedule() }
                return
            }
            model.maintenance()
            model.refreshStatus()
            Task { await model.review(load: .light) }
        }
    }

    /// A WhatsApp export opened with Recall ("Open in…" or the share sheet's app row).
    @MainActor
    private func handleOpen(_ url: URL) {
        // recall://tab/waiting from the widget
        if url.scheme == "recall" {
            if let tab = Tab(rawValue: url.lastPathComponent) { Navigation.shared.tab = tab }
            return
        }
        guard url.isFileURL else { return }
        do {
            let r = try ChatImport.importFile(url, engine: model.engine)
            importMessage = "Imported \(r.imported) messages from \(r.chat)."
        } catch {
            importMessage = error.localizedDescription
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Background.register()
        #if DEBUG
        MainActor.assumeIsolated { DemoData.seedIfAsked() }
        #endif
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    @MainActor
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let tab = response.notification.request.content.userInfo["tab"] as? String { Navigation.shared.tab = Tab(rawValue: tab) ?? .home }
    }
}

/// Background work iOS allows: a short refresh, and a longer model review while charging.
enum Background {
    static let refresh = "app.recall.refresh"
    static let review = "app.recall.review"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refresh, using: nil) { task in
            schedule()
            let work = Task { @MainActor in
                AppModel.shared.refreshStatus()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: review, using: nil) { task in
            let work = Task { @MainActor in
                await AppModel.shared.review(load: .heavy)
                if Calendar.current.component(.hour, from: Date()) >= DigestScheduler.hour - 1 {
                    _ = await AppModel.shared.engine.buildDigest(brain: Brain.shared, load: .heavy)
                    AppModel.shared.refreshStatus()
                }
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = {
                Brain.shared.stop()
                work.cancel()
            }
        }
    }

    static func schedule() {
        let r = BGAppRefreshTaskRequest(identifier: refresh)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(r)
        // The model review only runs while charging, like heavy work on Android.
        let p = BGProcessingTaskRequest(identifier: review)
        p.requiresExternalPower = true
        p.requiresNetworkConnectivity = false
        p.earliestBeginDate = Date(timeIntervalSinceNow: 20 * 60)
        try? BGTaskScheduler.shared.submit(p)
    }
}
