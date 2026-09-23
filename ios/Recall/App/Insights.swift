import Foundation
import RecallCore

/// Plain-language facts Recall writes about you once a day. You can read and delete each one;
/// a deleted fact isn't written again.
enum Insights {
    @MainActor
    static func write(store: RecallStore, now: Date = Date()) {
        let cal = Calendar.current
        let monthStart = cal.date(from: cal.dateComponents([.year, .month], from: now))!
        let lastMonthStart = cal.date(byAdding: .month, value: -1, to: monthStart)!
        let dayOfMonth = cal.component(.day, from: now)
        let lastMonthSameDay = cal.date(byAdding: .day, value: dayOfMonth, to: lastMonthStart)!

        let thisMonth = store.categoryTotals(from: Clock.ms(monthStart), to: Clock.ms(now))
        let lastMonth = store.categoryTotals(from: Clock.ms(lastMonthStart), to: Clock.ms(lastMonthSameDay))
        if let top = thisMonth.first(where: { !Categories.isMovement($0.category) && $0.category != "Other" }) {
            let before = lastMonth.first { $0.category == top.category }?.paise ?? 0
            var text = "\(top.category) is \(Fmt.rupees(top.paise)) this month"
            if before > 0 {
                let change = Int(((Double(top.paise) - Double(before)) / Double(before) * 100).rounded())
                if abs(change) >= 5 { text += ", \(change > 0 ? "up" : "down") \(abs(change))% on the same days last month" }
            }
            put(store, key: "money:top", topic: "money", text: text + ".")
        }

        let party = store.partyTotals(from: Clock.ms(monthStart), to: Clock.ms(now), direction: "out", limit: 1).first
        if let party, party.count >= 3 {
            put(store, key: "money:party", topic: "money", text: "You've paid \(party.name) \(party.count) times this month (\(Fmt.rupees(party.paise))).")
        }

        for (source, done, notForMe) in store.loopOutcomes(since: Clock.now - 30 * Clock.day) where done + notForMe >= 4 {
            if notForMe > done {
                put(store, key: "habit:nfm:\(source.rawValue)", topic: "habits",
                    text: "You usually mark \(source.label) requests as Not for me (\(notForMe) of \(done + notForMe) this month).")
            } else {
                put(store, key: "habit:done:\(source.rawValue)", topic: "habits",
                    text: "You clear most \(source.label) requests with Done (\(done) of \(done + notForMe) this month).")
            }
        }
    }

    @MainActor
    private static func put(_ store: RecallStore, key: String, topic: String, text: String) {
        guard !store.isForgotten(key) else { return }
        store.upsertFact(key: key, topic: topic, text: text)
    }
}
