import RecallCore
import SwiftUI
import WidgetKit

struct StatusEntry: TimelineEntry {
    let date: Date
    let status: StatusSnapshot?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry { StatusEntry(date: Date(), status: nil) }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) { completion(load()) }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        // The app reloads the widget whenever something changes; this is only a fallback.
        completion(Timeline(entries: [load()], policy: .after(Date().addingTimeInterval(30 * 60))))
    }

    private func load() -> StatusEntry {
        guard let store = try? RecallStore(path: Shared.databasePath) else { return StatusEntry(date: Date(), status: nil) }
        let capturing = !store.recentMessages(limit: 1).isEmpty
        return StatusEntry(date: Date(), status: StatusSnapshot.make(store: store, capturing: capturing))
    }
}

private enum W {
    static let bg = Color(red: 0.055, green: 0.067, blue: 0.086)
    static let text = Color(red: 0.91, green: 0.92, blue: 0.95)
    static let muted = Color(red: 0.60, green: 0.64, blue: 0.72)
    static let urgent = Color(red: 1, green: 0.42, blue: 0.42)
    static let wait = Color(red: 0.95, green: 0.72, blue: 0.29)

    static func orb(_ mood: String) -> some View {
        let (a, b): (Color, Color) = switch mood {
        case "urgent": (Color(red: 0.40, green: 0.06, blue: 0.18), Color(red: 1, green: 0.37, blue: 0.38))
        case "needs": (Color(red: 0.14, green: 0.10, blue: 0.46), Color(red: 0.49, green: 0.42, blue: 1))
        case "off": (Color(red: 0.12, green: 0.13, blue: 0.17), Color(red: 0.36, green: 0.39, blue: 0.47))
        default: (Color(red: 0.07, green: 0.20, blue: 0.48), Color(red: 0.25, green: 0.76, blue: 0.85))
        }
        return Circle()
            .fill(RadialGradient(colors: [.white.opacity(0.85), b, a], center: UnitPoint(x: 0.35, y: 0.3), startRadius: 0, endRadius: 40))
            .shadow(color: b.opacity(0.6), radius: 8)
    }
}

struct RecallWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: StatusEntry

    var body: some View {
        let s = entry.status
        switch family {
        case .accessoryInline:
            Text(s?.headline ?? "Recall")
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text("\(s?.waitingCount ?? 0)").font(.system(size: 20, weight: .bold))
                    Text("waiting").font(.system(size: 9))
                }
            }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 2) {
                Text(s?.headline ?? "Recall").font(.headline).lineLimit(1)
                if let top = s?.items.first {
                    Text("\(top.person): \(top.text)").font(.caption).lineLimit(2)
                } else if let s, s.spentTodayPaise > 0 {
                    Text("Spent \(Fmt.rupees(s.spentTodayPaise)) today").font(.caption)
                }
            }
        case .systemSmall:
            VStack(alignment: .leading, spacing: 8) {
                W.orb(s?.mood ?? "calm").frame(width: 38, height: 38)
                Spacer(minLength: 0)
                Text(s?.headline ?? "Open Recall to start").font(.system(size: 14, weight: .semibold)).foregroundStyle(W.text).lineLimit(3)
                if let s, s.spentTodayPaise > 0 {
                    Text("₹ \(Fmt.rupees(s.spentTodayPaise).dropFirst()) today").font(.system(size: 11, design: .monospaced)).foregroundStyle(W.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            HStack(alignment: .top, spacing: 12) {
                W.orb(s?.mood ?? "calm").frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 6) {
                    Text(s?.headline ?? "Open Recall to start").font(.system(size: 15, weight: .semibold)).foregroundStyle(W.text).lineLimit(1)
                    ForEach(s?.items.prefix(3) ?? []) { item in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Circle().fill(item.urgent ? W.urgent : W.wait).frame(width: 6, height: 6)
                            Text("\(item.person): \(item.text)").font(.system(size: 12)).foregroundStyle(W.muted).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if let s, s.spentTodayPaise > 0 {
                        Text("Spent \(Fmt.rupees(s.spentTodayPaise)) today").font(.system(size: 11, design: .monospaced)).foregroundStyle(W.muted)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

@main
struct RecallWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RecallWidget", provider: Provider()) { entry in
            RecallWidgetView(entry: entry)
                .containerBackground(W.bg, for: .widget)
                .widgetURL(URL(string: "recall://tab/waiting"))
        }
        .configurationDisplayName("Recall")
        .description("Who's waiting on you, and what you spent today.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline, .accessoryCircular])
    }
}
