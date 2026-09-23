#if DEBUG
import Foundation
import RecallCore

/// Made-up data for screenshots. Debug builds only:
/// `xcrun simctl launch booted app.recall -seedDemo YES` (wipes the database first).
@MainActor
enum DemoData {
    static func seedIfAsked() {
        // `-startTab money` opens on that tab.
        if let t = UserDefaults.standard.string(forKey: "startTab"), let tab = Tab(rawValue: t) { Navigation.shared.tab = tab }
        // `-modelTest YES` runs the model once and the review, and saves the results in kv.
        if UserDefaults.standard.bool(forKey: "modelTest") {
            Task { @MainActor in
                let store = AppModel.shared.store
                let t0 = Date()
                let hello = await Brain.shared.generate(system: "You are Recall, a private assistant on the user's phone.",
                                                        user: "In one short sentence, say hello and what you can help with.",
                                                        maxTokens: 60, temperature: 0.2, load: .interactive, onText: nil)
                store.set("test_hello", "\(hello ?? "nil: \(Brain.shared.blockedReason ?? "")") [\(String(format: "%.1f", Date().timeIntervalSince(t0)))s]")
                let rec = Recorder(store: store)
                let r = await AppModel.shared.engine.review(with: rec, load: .interactive)
                store.set("test_review", "reviewed \(r.reviewed), needs \(r.needs), dismissed \(r.dismissed), money \(r.money) [\(String(format: "%.1f", Date().timeIntervalSince(t0)))s]")
            }
        }
        guard UserDefaults.standard.bool(forKey: "seedDemo") else { return }
        let model = AppModel.shared
        let e = model.engine
        model.store.wipe()
        model.onboarded = true
        let cal = Calendar.current
        let now = Date()
        func at(daysAgo: Int, _ h: Int, _ m: Int = 0) -> Date {
            cal.date(bySettingHour: h, minute: m, second: 0, of: cal.date(byAdding: .day, value: -daysAgo, to: now)!)!
        }

        // Money: three months of bank texts, in formats the parser tests use.
        for d in 0..<95 {
            let dom = cal.component(.day, from: cal.date(byAdding: .day, value: -d, to: now)!)
            if dom == 1 { e.intake(text: "Update! INR 1,20,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for NEFT Cr-KKBK0000001-NORTHWIND DATA SYSTEMS PRI-A KUMAR-CMS1", sender: "AX-HDFCBK", source: .sms, at: at(daysAgo: d, 9)) }
            if dom == 5 { e.intake(text: "Sent Rs.28000.00 From HDFC Bank A/C *1111 To GREEN HOMES On 05/09/26 Ref 60000000\(d)", sender: "AX-HDFCBK", source: .sms, at: at(daysAgo: d, 10)) }
            if dom == 10 { e.intake(text: "Your additional purchase request for Rs.10000 has been received on 10 SEP 2026.", sender: "VM-MFUND", source: .sms, at: at(daysAgo: d, 11)) }
            if d % 2 == 0 {
                let amt = 180 + (d * 37) % 420
                e.intake(text: "Rs.\(amt).00 debited from A/c XX1234 on 22-09-26 to VPA swiggy@icici (UPI Ref 12\(d)56). Avl Bal Rs 12,345.67", sender: "AX-HDFCBK", source: .sms, at: at(daysAgo: d, 13, d % 50))
            }
            if d % 4 == 1 { e.intake(text: "Paid ₹\(300 + (d * 53) % 700) to Blinkit Commerce Pvt Ltd", sender: "GPay", source: .sms, at: at(daysAgo: d, 19)) }
            if d % 9 == 3 { e.intake(text: "Rs 2000.00 spent on your ICICI Bank Credit Card XX4321 at HP PETROL PUMP on 22-Sep-26. Avl Lmt: Rs 45,000.00", sender: "AX-ICICIB", source: .sms, at: at(daysAgo: d, 8)) }
            if d % 11 == 4 { e.intake(text: "INR \(799 + (d * 97) % 2500).00 debited from HDFC Bank Card x5678 at AMAZON on 2026-09-22", sender: "AX-HDFCBK", source: .sms, at: at(daysAgo: d, 21)) }
            if d % 17 == 5 { e.intake(text: "Dear Customer, Rs.1200 credited to your a/c XX1234 by UPI from rahul.k@okaxis on 22-09-26", sender: "AX-HDFCBK", source: .sms, at: at(daysAgo: d, 18)) }
        }

        // People waiting on you.
        e.intake(text: "hey can u approve this pr quick? blocking the release", sender: "Arjun Mehta", source: .whatsapp, at: at(daysAgo: 0, 15, 10))
        e.intake(text: "can you send the invoice by today? 🙏", sender: "Priya S", source: .sms, at: at(daysAgo: 0, 11, 2))
        e.intake(text: "Hi, following up on the contract review. Could you share your comments by Friday?", sender: "Karthik R", subject: "Contract review", source: .mail, at: at(daysAgo: 2, 10))
        e.intake(text: "orders / daily. 3 tables failed to load.", sender: "Beacon", subject: "[Beacon] ETL run failed", source: .mail, at: at(daysAgo: 0, 9, 40))
        e.intake(text: "Orders", sender: "Beacon", subject: "[Beacon] Warehouse build finished", source: .mail, at: at(daysAgo: 0, 9, 55))
        e.intake(text: "Lunch tomorrow?", sender: "Neha", source: .sms, at: at(daysAgo: 0, 12, 30))

        // What the model would have said.
        if let l = model.store.openLoop(convKey: "whatsapp|Arjun Mehta") { model.store.updateLoopFromModel(l.id, ask: "Approve PR #212 before the release", urgency: 92, due: "before release") }
        if let l = model.store.openLoop(convKey: "sms|Priya S") { model.store.updateLoopFromModel(l.id, ask: "Send the invoice", urgency: 58, due: "today") }
        model.store.insertLoop(convKey: MailThread.key("mail", "Beacon", "[Beacon] ETL run failed"), source: .mail, person: "Beacon", ask: "Check the failed ETL run", msgId: nil,
                               at: Clock.ms(at(daysAgo: 0, 9, 40)), urgency: 72, due: nil, ai: true)

        model.store.upsertFact(key: "money:top", topic: "money", text: "Food is ₹6,420 this month, up 18% on the same days last month.")
        model.store.upsertFact(key: "habit:done:mail", topic: "habits", text: "You clear most Mail requests with Done (9 of 11 this month).")
        let (s, end) = Clock.dayBounds()
        let (spent, received) = model.store.moneyTotals(from: s, to: end)
        model.store.saveDigest(Digest(day: Clock.dayKey(), at: Clock.now,
                                      summary: "Arjun is waiting for you to approve PR #212 before the release, and Priya needs the invoice today. The ETL run for Orders failed this morning.",
                                      spentPaise: spent, receivedPaise: received, waiting: 2, urgent: 1, model: "on-device"))
        model.store.addChat(role: "user", text: "how much on food this month")
        model.store.addChat(role: "assistant", text: "₹6,420 across 16 payments, mostly Swiggy. That's up 18% on the same days last month.", sources: ["16 payments"])
        model.refreshStatus()
    }
}
/// Passes calls to the model and keeps the last prompt and reply, for checking what it saw.
@MainActor
final class Recorder: TextGenerator {
    let store: RecallStore
    private var n = 0
    init(store: RecallStore) { self.store = store }
    var available: Bool { Brain.shared.available }
    func generate(system: String, user: String, maxTokens: Int, temperature: Float, load: GenerationLoad,
                  onText: (@Sendable (String) -> Void)?) async -> String? {
        let out = await Brain.shared.generate(system: system, user: user, maxTokens: maxTokens, temperature: temperature, load: load, onText: onText)
        n += 1
        store.set("test_prompt_\(n)", user)
        store.set("test_output_\(n)", out ?? "nil")
        return out
    }
}
#endif
