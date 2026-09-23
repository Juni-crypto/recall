import Foundation
import RecallCore
import UserNotifications

/// The nightly digest. iOS can't wake an app at an exact time, so the notification is scheduled
/// ahead and rewritten every time Recall runs (each Shortcuts message counts), so it's current.
enum DigestScheduler {
    static let id = "digest"

    static var hour: Int {
        get { (Shared.defaults.object(forKey: "digestHour") as? Int) ?? 21 }
        set { Shared.defaults.set(newValue, forKey: "digestHour") }
    }

    static var minute: Int {
        get { (Shared.defaults.object(forKey: "digestMinute") as? Int) ?? 30 }
        set { Shared.defaults.set(newValue, forKey: "digestMinute") }
    }

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Next digest time: today at 9:30 PM, or tomorrow if that's passed.
    static func nextFire(now: Date = Date()) -> Date {
        let cal = Calendar.current
        let today = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now)!
        return today > now ? today : cal.date(byAdding: .day, value: 1, to: today)!
    }

    @MainActor
    static func reschedule(store: RecallStore, snapshot: StatusSnapshot) {
        let fire = nextFire()
        let forToday = Calendar.current.isDateInToday(fire)
        let content = UNMutableNotificationContent()
        content.title = snapshot.headline
        var lines: [String] = []
        if forToday {
            for item in snapshot.items.prefix(3) { lines.append("\(item.urgent ? "●" : "○") \(item.person): \(item.text)") }
            let (start, end) = Clock.dayBounds()
            let (spent, received) = store.moneyTotals(from: start, to: end)
            if spent > 0 || received > 0 { lines.append("₹ Spent \(Fmt.rupees(spent)) · received \(Fmt.rupees(received))") }
            if let d = store.digest(day: Clock.dayKey()), lines.isEmpty { lines.append(d.summary) }
        }
        content.body = lines.isEmpty ? "Tap to see your day." : lines.joined(separator: "\n")
        content.sound = .default
        content.threadIdentifier = "digest"
        content.userInfo = ["tab": "today"]
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
        let request = UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
        UNUserNotificationCenter.current().add(request)
    }
}
