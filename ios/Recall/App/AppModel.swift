import Foundation
import Observation
import RecallCore
import WidgetKit

/// App-wide state: the engine, what needs you, and the background model review.
@MainActor @Observable
final class AppModel {
    static let shared = AppModel()

    let store: RecallStore
    let engine: RecallEngine
    let chat: ChatEngine
    private(set) var snapshot: StatusSnapshot
    /// Bumped on every database write, so screens re-read.
    private(set) var version = 0
    private(set) var reviewing = false
    private(set) var lastReview: String?

    var onboarded: Bool {
        get { Shared.defaults.bool(forKey: "onboarded") }
        set { Shared.defaults.set(newValue, forKey: "onboarded"); version += 1 }
    }

    /// True once at least one message has arrived through Shortcuts or an import.
    var capturing: Bool { !store.recentMessages(limit: 1).isEmpty }

    private init() {
        store = Shared.openStore()
        engine = RecallEngine(store: store)
        chat = ChatEngine(engine: engine)
        snapshot = engine.snapshot()
        NotificationCenter.default.addObserver(forName: RecallStore.changed, object: nil, queue: .main) { _ in
            Task { @MainActor in AppModel.shared.changed() }
        }
    }

    private var pendingRefresh: Task<Void, Never>?

    private func changed() {
        version += 1
        // Writes come in bursts (an import, a review); refresh the widget once they settle.
        pendingRefresh?.cancel()
        pendingRefresh = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            refreshStatus()
        }
    }

    func refreshStatus() {
        snapshot = StatusSnapshot.make(store: store, capturing: capturing)
        WidgetCenter.shared.reloadAllTimelines()
        DigestScheduler.reschedule(store: store, snapshot: snapshot)
    }

    /// Lets the model read what came in since last time. Runs when the app opens and in the background.
    func review(load: GenerationLoad) async {
        guard !reviewing, Brain.shared.available, ModelStore.shared.activeSpec?.canReview ?? true else { return }
        reviewing = true
        defer { reviewing = false }
        let r = await engine.review(with: Brain.shared, load: load)
        if r.reviewed > 0 || r.money > 0 {
            lastReview = "Read \(r.reviewed) conversation\(r.reviewed == 1 ? "" : "s"), \(r.needs) need\(r.needs == 1 ? "s" : "") you"
        }
        refreshStatus()
    }

    /// Daily chores: learn the owner name, prune a year back, write facts.
    func maintenance() {
        let today = Clock.dayKey()
        guard store.get("maintenance_day") != today else { return }
        store.set("maintenance_day", today)
        engine.learnOwnerName()
        store.prune(olderThan: Clock.now - 365 * Clock.day)
        Insights.write(store: store)
    }
}
