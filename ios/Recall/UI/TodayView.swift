import RecallCore
import SwiftUI

struct TodayView: View {
    @Environment(AppModel.self) private var model
    @Environment(Navigation.self) private var nav
    @State private var building = false
    @State private var showEverything = false

    var body: some View {
        let _ = model.version
        let (start, end) = Clock.dayBounds()
        let loops = model.store.openLoops()
        let urgent = loops.filter(\.isUrgent)
        let waiting = loops.filter { !$0.isUrgent }
        let (spent, received) = model.store.moneyTotals(from: start, to: end)
        let total = model.store.countMessages(from: start, to: end)
        let sources = model.store.sourceCounts(from: start, to: end)
        let digest = model.store.digest(day: Clock.dayKey())

        ScrollView {
            VStack(spacing: 12) {
                ScreenTitle(title: "Today", subtitle: "\(Date().formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))) · \(total) messages")
                Card {
                    Label2(text: "Your day", trailing: digest?.model != nil ? "written on-device" : nil)
                    Text(digest?.summary ?? "Your summary is written at \(String(format: "%d:%02d", DigestScheduler.hour > 12 ? DigestScheduler.hour - 12 : DigestScheduler.hour, DigestScheduler.minute)) PM. You can write it now.")
                        .font(Type.body(15).italic()).foregroundStyle(RC.text)
                    HStack {
                        if let d = digest { Text("Built at \(Fmt.clock(d.at))").font(Type.mono(11)).foregroundStyle(RC.dim) }
                        Spacer()
                        GhostButton(title: building ? "Writing…" : (digest == nil ? "Write it now" : "Rebuild")) {
                            guard !building else { return }
                            building = true
                            Task {
                                _ = await model.engine.buildDigest(brain: Brain.shared, load: .interactive)
                                model.refreshStatus()
                                building = false
                            }
                        }
                    }
                    if let reason = Brain.shared.blockedReason { Text(reason).font(Type.mono(11)).foregroundStyle(RC.wait) }
                }
                if !urgent.isEmpty {
                    Card {
                        Label2(text: "Urgent", trailing: "\(urgent.count)")
                        ForEach(urgent.prefix(4)) { l in LoopRow(loop: l, color: RC.urgent) }
                    }
                }
                Card {
                    Label2(text: "Waiting on you", trailing: "\(waiting.count) ›")
                    if waiting.isEmpty { Text("Nobody else is waiting on you.").font(Type.body(14)).foregroundStyle(RC.muted) }
                    ForEach(waiting.prefix(3)) { l in LoopRow(loop: l, color: RC.wait) }
                }
                .onTapGesture { nav.tab = .waiting }
                Card {
                    Label2(text: "Money")
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading) {
                            Text("Spent").font(Type.body(12)).foregroundStyle(RC.muted)
                            Text(Fmt.rupees(spent)).font(Type.display(26)).foregroundStyle(RC.text)
                        }
                        Spacer()
                        VStack(alignment: .trailing) {
                            Text("Received").font(Type.body(12)).foregroundStyle(RC.muted)
                            Text(Fmt.rupees(received)).font(Type.display(22)).foregroundStyle(RC.money)
                        }
                    }
                }
                .onTapGesture { nav.tab = .money }
                Card {
                    Label2(text: "Everything", trailing: "\(total) ›")
                    Text(sources.isEmpty ? "Nothing has come in yet today." : sources.map { "\($0.0.label) \($0.1)" }.joined(separator: " · "))
                        .font(Type.body(13)).foregroundStyle(RC.muted)
                }
                .onTapGesture { showEverything = true }
            }
            .padding(16)
        }
        .sheet(isPresented: $showEverything) { EverythingView() }
    }
}

struct LoopRow: View {
    let loop: OpenLoop
    let color: Color
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle().fill(color).frame(width: 8, height: 8).padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text("\(loop.person) · \(loop.source.label)").font(Type.body(14, weight: .semibold)).foregroundStyle(RC.text).lineLimit(1)
                    Spacer()
                    Text(Fmt.when(loop.lastAt)).font(Type.mono(11)).foregroundStyle(RC.dim)
                }
                Text(loop.askText.replacingOccurrences(of: "\n", with: " ")).font(Type.body(13)).foregroundStyle(RC.muted).lineLimit(2)
            }
        }
    }
}

struct WaitingView: View {
    @Environment(AppModel.self) private var model
    @State private var filter = "All"

    var body: some View {
        let _ = model.version
        let all = model.store.openLoops()
        let shown = filter == "Urgent" ? all.filter(\.isUrgent) : filter == "Mail" ? all.filter { $0.source == .mail } : all
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenTitle(title: "Waiting on you", subtitle: "Asks with no reply from you yet")
                HStack(spacing: 8) {
                    ForEach(["All", "Urgent", "Mail"], id: \.self) { f in
                        Pill(text: f == "All" ? "All \(all.count)" : f, selected: filter == f) { filter = f }
                    }
                }
                if shown.isEmpty {
                    Card {
                        Text("Nothing is waiting on you.").font(Type.body(15, weight: .semibold)).foregroundStyle(RC.text)
                        Text("When someone asks you something (\"can you send the invoice?\"), it stays here until you tap Done.")
                            .font(Type.body(13)).foregroundStyle(RC.muted)
                    }
                }
                ForEach(shown) { l in
                    Card {
                        HStack {
                            Text(l.person).font(Type.body(16, weight: .semibold)).foregroundStyle(RC.text).lineLimit(1)
                            Spacer()
                            if l.ai { Tag(text: "Qwen") }
                            if l.isUrgent { Tag(text: "urgent", color: RC.urgent) }
                            if let due = l.dueHint { Tag(text: due, color: RC.wait) }
                        }
                        Text("\(l.source.label) · \(Fmt.when(l.firstAt))\(l.asks > 1 ? " · asked \(l.asks)×" : "")").font(Type.mono(11)).foregroundStyle(RC.dim)
                        Text("\"\(l.askText.replacingOccurrences(of: "\n", with: " "))\"").font(Type.body(14)).foregroundStyle(RC.text).lineLimit(4)
                        HStack(spacing: 8) {
                            GhostButton(title: "Done", systemImage: "checkmark") { model.engine.done(l) }
                            GhostButton(title: "Snooze") { model.engine.snooze(l) }
                            GhostButton(title: "Not for me", color: RC.muted) { model.engine.notForMe(l) }
                        }
                    }
                }
            }
            .padding(16)
        }
    }
}

struct EverythingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let _ = model.version
        let words = query.split(separator: " ").map(String.init)
        let items = words.isEmpty ? model.store.recentMessages(limit: 400) : model.store.search(words, limit: 200)
        NavigationStack {
            List(items) { m in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(m.who).font(Type.body(14, weight: .semibold)).foregroundStyle(RC.text).lineLimit(1)
                        Spacer()
                        Text("\(m.source.label) · \(Fmt.when(m.at))").font(Type.mono(11)).foregroundStyle(RC.dim)
                    }
                    Text(m.text).font(Type.body(13)).foregroundStyle(RC.muted).lineLimit(4)
                }
                .listRowBackground(RC.surface)
            }
            .scrollContentBackground(.hidden)
            .background(RC.bg)
            .searchable(text: $query, prompt: "Search your messages")
            .navigationTitle("Everything")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
