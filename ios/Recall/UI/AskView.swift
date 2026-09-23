import RecallCore
import SwiftUI

struct AskView: View {
    let prefill: String?
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var question = ""
    @State private var partial: String?
    @State private var asking = false
    @FocusState private var focused: Bool

    var body: some View {
        let _ = model.version
        let turns = model.store.chat(limit: 40)
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            if turns.isEmpty && partial == nil {
                                VStack(spacing: 12) {
                                    RecallOrb(mood: .calm, animate: false).frame(width: 90)
                                    Text("Ask about your messages, who's waiting, or your money.").font(Type.body(14)).foregroundStyle(RC.muted).multilineTextAlignment(.center)
                                }
                                .frame(maxWidth: .infinity).padding(.top, 40)
                            }
                            ForEach(turns) { t in bubble(t.role, t.text, sources: t.sources) }
                            if let partial { bubble("assistant", partial.isEmpty ? "…" : partial, sources: []) }
                            Color.clear.frame(height: 1).id("end")
                        }
                        .padding(16)
                    }
                    .onChange(of: turns.count) { proxy.scrollTo("end") }
                    .onChange(of: partial) { proxy.scrollTo("end") }
                }
                HStack(spacing: 10) {
                    TextField("Ask Recall", text: $question, axis: .vertical)
                        .font(Type.body(15)).lineLimit(1...4).focused($focused)
                        .padding(12).background(RC.surface, in: RoundedRectangle(cornerRadius: 18)).overlay(RoundedRectangle(cornerRadius: 18).stroke(RC.line))
                        .onSubmit(send)
                    Button(action: asking ? { Brain.shared.stop() } : send) {
                        Image(systemName: asking ? "stop.fill" : "arrow.up").font(.system(size: 16, weight: .bold))
                            .foregroundStyle(RC.accentInk).frame(width: 40, height: 40).background(RC.accent, in: Circle())
                    }
                    .accessibilityLabel(asking ? "Stop" : "Send")
                }
                .padding(12)
            }
            .background(RC.bg)
            .navigationTitle("Ask")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button("Clear") { model.store.clearChat() }.disabled(turns.isEmpty) }
            }
            .onAppear {
                if let prefill, !prefill.isEmpty { question = prefill; send() } else { focused = true }
            }
        }
    }

    private func bubble(_ role: String, _ text: String, sources: [String]) -> some View {
        let mine = role == "user"
        return VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
            Text(text).font(Type.body(15)).foregroundStyle(mine ? RC.accentInk : RC.text)
                .padding(12)
                .background(mine ? RC.accent : RC.surface, in: RoundedRectangle(cornerRadius: 16))
                .textSelection(.enabled)
            if !sources.isEmpty { Text(sources.joined(separator: " · ")).font(Type.mono(10)).foregroundStyle(RC.dim) }
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }

    private func send() {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !asking else { return }
        question = ""
        asking = true
        partial = ""
        Task {
            _ = await model.chat.ask(q, brain: Brain.shared) { text in
                Task { @MainActor in partial = text }
            }
            partial = nil
            asking = false
        }
    }
}

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var page = 0

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            RecallOrb(mood: page == 0 ? .needs : .calm).frame(width: 220)
            Text("Recall").font(Type.display(42)).foregroundStyle(RC.text)
            Group {
                if page == 0 {
                    Text("Remembers who's waiting on you and where your money went. The AI runs on this iPhone. Nothing leaves it.")
                } else {
                    Text("iPhone apps can't read other apps' notifications, so your texts and emails reach Recall through Shortcuts automations. It takes a few minutes to set up.")
                }
            }
            .font(Type.body(16)).foregroundStyle(RC.muted).multilineTextAlignment(.center).padding(.horizontal, 32)
            Spacer()
            PrimaryButton(title: page == 0 ? "Next" : "Get started") {
                if page == 0 { page = 1; return }
                Task {
                    _ = await DigestScheduler.requestPermission()
                    model.onboarded = true
                    Navigation.shared.tab = .you
                    model.refreshStatus()
                }
            }
            .padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity)
        .background(RadialGradient(colors: [RC.accentDeep.opacity(0.4), RC.bg], center: .top, startRadius: 40, endRadius: 600).ignoresSafeArea())
    }
}
