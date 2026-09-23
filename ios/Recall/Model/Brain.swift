import Foundation
import Observation
import RecallCore
import UIKit

/// The one entry point for running the model. Checks the heat and battery guard, loads the
/// active model, and cancels generation if the phone heats up mid-way.
@MainActor @Observable
final class Brain: TextGenerator {
    static let shared = Brain()

    enum State: Equatable { case idle, loading, thinking }
    private(set) var state: State = .idle
    private(set) var blockedReason: String?
    private var busy = false

    init() {
        NotificationCenter.default.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main) { _ in
            let t = ProcessInfo.processInfo.thermalState
            if t == .serious || t == .critical { LlamaRunner.shared.cancel() }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in
                LlamaRunner.shared.cancel()
                if !Brain.shared.busy { await LlamaRunner.shared.unload() }
            }
        }
    }

    var available: Bool { ModelStore.shared.activeURL != nil }

    func generate(system: String, user: String, maxTokens: Int, temperature: Float, load: GenerationLoad,
                  onText: (@Sendable (String) -> Void)?) async -> String? {
        let verdict = Safety.check(load == .interactive ? .interactive : (load == .heavy ? .heavy : .light))
        guard verdict.ok else { blockedReason = verdict.reason; return nil }
        blockedReason = nil
        guard let url = ModelStore.shared.activeURL, !busy else { return nil }
        busy = true
        defer { busy = false; state = .idle }
        do {
            state = .loading
            try await LlamaRunner.shared.load(path: url.path)
            state = .thinking
            let noThink = ModelStore.shared.activeThinks
            return try await LlamaRunner.shared.generate(system: system, user: user, maxTokens: maxTokens,
                                                         temperature: temperature, noThink: noThink, onText: onText)
        } catch {
            blockedReason = "\(error)"
            return nil
        }
    }

    func stop() { LlamaRunner.shared.cancel() }
}
