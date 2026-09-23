import Foundation

/// How much a model run is allowed to cost. The app maps this to its heat and battery guard.
public enum GenerationLoad: Sendable { case interactive, light, heavy }

/// The on-device model, as the rest of Recall sees it. The app implements it with llama.cpp;
/// tests can pass a fake.
@MainActor
public protocol TextGenerator: AnyObject {
    var available: Bool { get }
    func generate(system: String, user: String, maxTokens: Int, temperature: Float, load: GenerationLoad,
                  onText: (@Sendable (String) -> Void)?) async -> String?
}

public enum Fmt {
    /// Indian grouping: ₹1,22,400. Paise are dropped unless the amount is under ₹100.
    public static func rupees(_ paise: Int64) -> String {
        let neg = paise < 0
        let p = abs(paise)
        let whole = p / 100
        let frac = p % 100
        var s = String(whole)
        if s.count > 3 {
            let last3 = String(s.suffix(3))
            var rest = String(s.dropLast(3))
            var groups: [String] = []
            while rest.count > 2 { groups.insert(String(rest.suffix(2)), at: 0); rest = String(rest.dropLast(2)) }
            if !rest.isEmpty { groups.insert(rest, at: 0) }
            s = groups.joined(separator: ",") + "," + last3
        }
        let tail = (whole < 100 && frac > 0) ? String(format: ".%02d", frac) : ""
        return (neg ? "-" : "") + "₹" + s + tail
    }

    public static func clock(_ ms: Int64) -> String {
        Clock.date(ms).formatted(date: .omitted, time: .shortened)
    }

    /// "3:10 PM", "Yesterday 6:05 PM", "Mon 6:03 PM", "12 Sep"
    public static func when(_ ms: Int64, now: Date = Date(), calendar: Calendar = .current) -> String {
        let d = Clock.date(ms)
        if calendar.isDateInToday(d) { return clock(ms) }
        if calendar.isDateInYesterday(d) { return "Yesterday \(clock(ms))" }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: d), to: calendar.startOfDay(for: now)).day ?? 99
        if days < 7 { return d.formatted(.dateTime.weekday(.abbreviated)) + " " + clock(ms) }
        return d.formatted(.dateTime.day().month(.abbreviated))
    }

    public static func relative(_ ms: Int64, now: Int64 = Clock.now) -> String {
        let m = max(0, (now - ms) / 60_000)
        if m < 1 { return "just now" }
        if m < 60 { return "\(m) min ago" }
        let h = m / 60
        if h < 24 { return "\(h) h ago" }
        return "\(h / 24) d ago"
    }
}
