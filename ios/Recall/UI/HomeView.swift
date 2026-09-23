import RecallCore
import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(Navigation.self) private var nav

    var body: some View {
        let s = model.snapshot
        ScrollView {
            VStack(spacing: 18) {
                Text(greeting).font(Type.body(13)).foregroundStyle(RC.muted).padding(.top, 18)
                Button { ask(nil) } label: {
                    RecallOrb(mood: Brain.shared.state != .idle ? .thinking : OrbMood(s.mood))
                        .frame(maxWidth: 300)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Ask Recall")
                Text("Recall").font(Type.display(40)).foregroundStyle(RC.text)
                Text(s.headline).font(Type.body(17, weight: .medium))
                    .foregroundStyle(s.mood == "urgent" ? RC.urgent : RC.text).multilineTextAlignment(.center)
                if let top = s.items.first {
                    Text("\(top.person): \(top.text)").font(Type.body(13)).foregroundStyle(RC.muted)
                        .multilineTextAlignment(.center).lineLimit(2).padding(.horizontal, 32)
                }
                if !model.capturing {
                    Card {
                        Text("Recall hasn't received anything yet").font(Type.body(15, weight: .semibold)).foregroundStyle(RC.text)
                        Text("iPhone apps can't read other apps' notifications. Set up two Shortcuts automations so your bank texts and emails reach Recall.")
                            .font(Type.body(13)).foregroundStyle(RC.muted)
                        GhostButton(title: "Set up Shortcuts", systemImage: "bolt") { nav.tab = .you }
                    }
                    .padding(.horizontal, 20)
                }
                FlowChips(items: ["What did I miss today?", "Who's waiting on me?", "Money today", "What you know about me"]) { ask($0) }
                    .padding(.horizontal, 20)
                if model.reviewing {
                    Text("Reading new messages…").font(Type.mono(11)).foregroundStyle(RC.dim)
                } else if let r = model.lastReview {
                    Text(r).font(Type.mono(11)).foregroundStyle(RC.dim)
                } else {
                    Text("Tap the orb to ask").font(Type.mono(11)).foregroundStyle(RC.dim)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 24)
        }
        .background(
            RadialGradient(colors: [RC.accentDeep.opacity(0.35), RC.bg], center: .top, startRadius: 40, endRadius: 520).ignoresSafeArea()
        )
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        let part = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening"
        return "\(part) · \(Date().formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))"
    }

    private func ask(_ q: String?) {
        nav.askPrefill = q
        nav.showAsk = true
    }
}

/// Chips that wrap onto new lines.
struct FlowChips: View {
    let items: [String]
    var action: (String) -> Void
    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { item in
                Pill(text: item) { action(item) }
            }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let h = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX + (bounds.width - row.width) / 2
            for i in row.items {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
                x += s.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var items: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            let extra = rows[rows.count - 1].items.isEmpty ? s.width : s.width + spacing
            if rows[rows.count - 1].width + extra > width, !rows[rows.count - 1].items.isEmpty {
                rows.append(Row())
            }
            let add = rows[rows.count - 1].items.isEmpty ? s.width : s.width + spacing
            rows[rows.count - 1].items.append(i)
            rows[rows.count - 1].width += add
            rows[rows.count - 1].height = max(rows[rows.count - 1].height, s.height)
        }
        return rows
    }
}
