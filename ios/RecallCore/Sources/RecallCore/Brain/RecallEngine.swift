import CryptoKit
import Foundation

/// What happens to a message once it reaches Recall: store it, read money out of it, decide
/// whether someone is waiting on you. Then, when the model can run, review it properly.
/// Same flow as the Android app, minus the notification listener iOS doesn't allow.
@MainActor
public final class RecallEngine {
    public let store: RecallStore

    public init(store: RecallStore) { self.store = store }

    // MARK: - Settings kept in the database (the widget and Shortcuts read them too)

    /// Account holder name(s) learned from bank messages.
    public var ownerNames: [String] { OwnerName.load(store.get("owner_name") ?? "") }

    /// Names the user goes by, so "@Asha" in a group counts as addressed to them.
    public var userNames: [String] {
        get { (store.get("user_names") ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count >= 2 } }
        set { store.set("user_names", newValue.joined(separator: ", ")) }
    }

    // MARK: - Intake

    /// A message handed over by a Shortcuts automation, the share sheet, or typed in.
    /// Returns false if it was already stored (automations sometimes fire twice).
    @discardableResult
    public func intake(text raw: String, sender rawSender: String?, subject: String? = nil, source: Source, at date: Date = Date()) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        let sender = rawSender?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let at = Clock.ms(date)
        let body: String
        let convKey: String
        switch source {
        case .mail:
            let subj = subject?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            body = subj.map { "\($0)\n\(text)" } ?? text
            convKey = MailThread.key("mail", sender, subj ?? text)
        case .sms:
            body = text
            convKey = "sms|\(sender ?? "unknown")"
        case .whatsapp:
            body = text
            convKey = "whatsapp|\(sender ?? "unknown")"
        case .note:
            body = text
            convKey = "note|\(at)"
        }
        // Ten-minute buckets: the same text from the same sender twice in a row is one message.
        let hash = Self.sha1("\(source.rawValue)|\(sender ?? "")|\(body)|\(at / 600_000)")
        let c = Captured(hash: hash, source: source, convKey: convKey, convTitle: sender, sender: sender, text: body, at: at)
        return handle(c)
    }

    @discardableResult
    public func handle(_ c: Captured, trackRequests: Bool = true) -> Bool {
        guard let id = try? store.insertMessage(c) else { return false }
        if c.isSelf {
            // You replied: whatever was waiting in that conversation is answered.
            store.closeLoops(convKey: c.convKey, by: "reply")
        } else {
            parseMoney(c, msgId: id)
            if trackRequests { trackLoop(c, msgId: id) }
        }
        return true
    }

    // MARK: - Money

    private static let banky = RulePattern(#"\b(a/c|acct|account|upi|card|debited|credited|bank|vpa|imps|neft)\b"#, ignoreCase: true)

    private func moneyEligible(_ source: Source, sender: String?, text: String) -> Bool {
        switch source {
        case .sms: SenderHeuristics.isAutomated(sender, "") || Self.banky.containsMatch(in: text)
        case .mail: Self.banky.containsMatch(in: text)
        case .whatsapp, .note: false // "I paid 500 for dinner" in a chat is not your transaction
        }
    }

    private func parseMoney(_ c: Captured, msgId: Int64) {
        guard moneyEligible(c.source, sender: c.sender, text: c.text) else { return }
        guard let p = MoneyParser.parse(c.text) ?? (c.source == .mail ? c.convTitle.flatMap { MoneyParser.parse("\($0). \(c.text)") } : nil) else { return }
        record(p.amountPaise, p.direction, merchant: p.merchant, account: p.account, raw: c.text, msgId: msgId, source: sourceLabel(c), at: c.at)
    }

    private func sourceLabel(_ c: Captured) -> String {
        switch c.source {
        case .sms:
            // "AX-HDFCBK-S" -> "HDFCBK SMS"
            let parts = (c.sender ?? "").split(separator: "-")
            let name = parts.count >= 2 && parts[0].count == 2 ? String(parts[1]) : (c.sender ?? "Bank")
            return "\(name) SMS"
        default: return c.source.label
        }
    }

    private func record(_ paise: Int64, _ direction: String, merchant: String?, account: String?, raw: String, msgId: Int64?, source: String, at: Int64) {
        // Your own correction wins, then the rules, then the model's remembered guess.
        let ruled = Categories.of(merchant, direction, raw, ownerNames: ownerNames)
        let saved = merchant.flatMap { store.merchantCategory($0) }
        let category: String
        if let saved, saved.1 == "user" { category = saved.0 }
        else if ruled != "Other" { category = ruled }
        else { category = saved?.0 ?? ruled }
        store.insertOrMergeTxn(amountPaise: paise, direction: direction, merchant: merchant, category: category, account: account,
                               source: source, msgId: msgId, raw: raw, at: at)
    }

    /// Re-reads every stored message after a parser change or once the owner name is known.
    public func reparseMoney() {
        store.clearTxns()
        for m in store.messages(from: 0, to: .max, includeSelf: false, limit: 200_000) {
            let c = Captured(hash: "", source: m.source, convKey: m.convKey, convTitle: m.convTitle, sender: m.sender, text: m.text, at: m.at)
            parseMoney(c, msgId: m.id)
        }
        store.pairSelfTransfers()
    }

    /// Learns the account holder's name from bank SMS, so transfers to yourself aren't spending.
    public func learnOwnerName() {
        let names = OwnerName.detect(store.recentBankTexts())
        guard !names.isEmpty else { return }
        let stored = names.reduce(store.get("owner_name") ?? "") { OwnerName.add($1, to: $0) }
        if stored != store.get("owner_name") {
            store.set("owner_name", stored)
            reparseMoney()
        }
    }

    // MARK: - Waiting on you (rules, instant)

    public func mentionsUser(_ text: String) -> Bool {
        let t = text.lowercased()
        return userNames.contains { n in
            RulePattern(#"(^|[^\p{L}])@?"# + NSRegularExpression.escapedPattern(for: n.lowercased()) + #"([^\p{L}]|$)"#).containsMatch(in: t)
        }
    }

    private func trackLoop(_ c: Captured, msgId: Int64) {
        guard c.source != .note else { return }
        let isMail = c.source == .mail
        let automated = SenderHeuristics.isAutomated(c.sender, c.text) || SenderHeuristics.isPromo(c.text) || (isMail && SenderHeuristics.isSystemMail(c.text))
        if automated { return }
        guard !c.isGroup || mentionsUser(c.text) else { return }

        let request = RequestDetector.detect(c.text)
        let existing = store.openLoop(convKey: c.convKey)
        if !request.isRequest && existing == nil { return }

        let hour = Calendar.current.component(.hour, from: Clock.date(c.at))
        let input = UrgencyScorer.Input(
            text: c.text, pings: store.countIncoming(convKey: c.convKey, since: c.at - Clock.hour),
            importance: 0.3, isQuestion: request.reasons.contains("question"), isGroup: c.isGroup,
            isEmail: isMail, isCall: false, isEvening: hour >= 18, appOpenRate: 0.5, automated: false,
        )
        let score = UrgencyScorer.score(input)
        if let existing {
            store.bumpLoop(existing.id, at: c.at, urgency: score.value)
        } else {
            let person = c.convTitle ?? c.sender ?? c.source.label
            store.insertLoop(convKey: c.convKey, source: c.source, person: person, ask: String(c.text.prefix(240)), msgId: msgId,
                             at: c.at, urgency: score.value, due: score.dueHint, ai: false)
        }
    }

    public func done(_ loop: OpenLoop) { store.setLoopState(loop.id, state: "closed", closedBy: "done") }
    public func notForMe(_ loop: OpenLoop) { store.setLoopState(loop.id, state: "dismissed", closedBy: "not_for_me") }
    public func snooze(_ loop: OpenLoop, hours: Int = 3) { store.snoozeLoop(loop.id, until: Clock.now + Int64(hours) * Clock.hour) }

    // MARK: - Model review

    public struct Report: Sendable { public var reviewed = 0, needs = 0, dismissed = 0, money = 0 }

    private static let window: Int64 = 3 * Clock.day
    private static let batch = 10

    private func worthReview(_ m: Message) -> Bool {
        if m.source == .note { return false }
        // A payment alert the rules already read isn't a request.
        if store.hasTxn(msgId: m.id) { return false }
        // Alert mails: routine ones ("build finished") skip the model, problems get a look.
        if m.source == .mail && SenderHeuristics.isSystemMail(m.text) { return SenderHeuristics.isProblem(m.text) }
        if SenderHeuristics.isAutomated(m.sender, m.text) || SenderHeuristics.isPromo(m.text) { return false }
        return !m.isGroup || mentionsUser(m.text)
    }

    /// The model reads what people sent, with the conversation around it, and decides what needs you.
    public func review(with brain: TextGenerator, load: GenerationLoad, maxBatches: Int = 4) async -> Report {
        var report = Report()
        guard brain.available else { return report }
        for _ in 0..<maxBatches {
            guard let r = await triageBatch(brain, load: load) else { break }
            report.reviewed += r.reviewed; report.needs += r.needs; report.dismissed += r.dismissed
        }
        report.money = await moneyBatch(brain, load: load)
        return report
    }

    private func triageBatch(_ brain: TextGenerator, load: GenerationLoad) async -> Report? {
        let now = Clock.now
        let pool = store.triageCandidates(since: now - Self.window, limit: 60)
        guard !pool.isEmpty else { return nil }
        let people = pool.filter(worthReview)
        let skip = pool.filter { !worthReview($0) }
        store.markTriaged(skip.map(\.id), value: 2)
        // One item per conversation: its latest unreviewed message, with context.
        var latestByConv: [String: Message] = [:]
        var order: [String] = []
        for m in people { if latestByConv[m.convKey] == nil { order.append(m.convKey) }; latestByConv[m.convKey] = m }
        let latest = order.prefix(Self.batch).compactMap { latestByConv[$0] }
        if latest.isEmpty { return skip.isEmpty ? nil : Report() }

        var user = ""
        var titles: [String] = []
        for (i, m) in latest.enumerated() {
            // Always include the message itself, however old; an empty item throws the numbering off.
            let ctx = store.messagesForConv(m.convKey, since: min(now - 2 * Clock.day, m.at)).suffix(6)
            let title = m.isGroup ? "\(m.convTitle ?? "Group") (group)" : (m.convTitle ?? m.sender ?? m.source.label)
            titles.append(title)
            let alert = m.source == .mail && SenderHeuristics.isSystemMail(m.text)
            user += "\(i + 1). \(m.source.label) · \(title)\(alert ? " (alert)" : "")\n"
            for c in ctx {
                let who = c.isSelf ? "You" : (c.sender ?? title)
                user += "   \(who): \(c.text.replacingOccurrences(of: "\n", with: " ").prefix(160))\n"
            }
        }
        guard let out = await brain.generate(system: Prompts.triage, user: user, maxTokens: 26 * latest.count + 30,
                                             temperature: 0.1, load: load, onText: nil) else { return nil }
        let answers = Prompts.align(Prompts.parseTriage(out), titles: titles)

        var report = Report(reviewed: latest.count)
        for (i, m) in latest.enumerated() {
            guard var a = answers[i], !Prompts.isParroted(a.ask, text: m.text) else { continue }
            if Prompts.isParrotedDue(a.due, text: m.text) { a = Prompts.TriageAnswer(who: a.who, need: a.need, urgency: min(a.urgency, 2), due: nil, ask: a.ask) }
            let existing = store.openLoop(convKey: m.convKey)
            if a.need {
                report.needs += 1
                let modelScore = [20, 45, 72, 92][max(0, min(3, a.urgency))]
                let ask = a.ask.isEmpty ? String(m.text.prefix(80)) : a.ask
                if let existing {
                    store.updateLoopFromModel(existing.id, ask: ask, urgency: existing.asks >= 2 ? max(modelScore, existing.urgency) : modelScore, due: a.due)
                } else {
                    let person = m.isGroup && m.convTitle != nil && m.sender != nil ? "\(m.sender!) · \(m.convTitle!)" : (m.convTitle ?? m.sender ?? m.source.label)
                    store.insertLoop(convKey: m.convKey, source: m.source, person: person, ask: ask, msgId: m.id, at: m.at,
                                     urgency: modelScore, due: a.due, ai: true)
                }
            } else if let existing, existing.asks <= 1 {
                // The rules flagged it; the model, seeing the conversation, says it doesn't need you.
                store.setLoopState(existing.id, state: "dismissed", closedBy: "model_fyi")
                report.dismissed += 1
            }
        }
        // Everything in these conversations up to now has been reviewed.
        let reviewedKeys = Set(latest.map(\.convKey))
        store.markTriaged(people.filter { reviewedKeys.contains($0.convKey) }.map(\.id), value: 1)
        return report
    }

    private static let moneyHint = RulePattern(#"(₹|\brs\.?|\binr)\s*[0-9]"#, ignoreCase: true)
    private static let moveHint = RulePattern(#"\b(debit|credit|spent|paid|sent|received|withdraw|deposit|transfer|purchase|txn)"#, ignoreCase: true)

    /// Bank alerts the rules couldn't read. The model's amount must appear in the message.
    private func moneyBatch(_ brain: TextGenerator, load: GenerationLoad) async -> Int {
        let pool = store.moneyCandidates(since: Clock.now - Self.window, limit: 80)
        let maybe = pool.filter { Self.moneyHint.containsMatch(in: $0.text) && Self.moveHint.containsMatch(in: $0.text) && !SenderHeuristics.isPromo($0.text) }
        store.markMoneyChecked(pool.filter { m in !maybe.contains { $0.id == m.id } }.map(\.id))
        let batch = Array(maybe.prefix(Self.batch))
        guard !batch.isEmpty else { return 0 }
        let texts = batch.map { String($0.text.replacingOccurrences(of: "\n", with: " ").prefix(260)) }
        let user = texts.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
        let out = await brain.generate(system: Prompts.money, user: user, maxTokens: 22 * batch.count + 20, temperature: 0, load: load, onText: nil)
        store.markMoneyChecked(batch.map(\.id))
        guard let out else { return 0 }
        var found = 0
        for (i, a) in Prompts.parseMoney(out, messages: texts) {
            let m = batch[i]
            record(a.amountPaise, a.direction, merchant: a.party, account: nil, raw: m.text, msgId: m.id, source: m.source.label, at: m.at)
            found += 1
        }
        if found > 0 { store.pairSelfTransfers() }
        return found
    }

    // MARK: - Digest

    public func buildDigest(for date: Date = Date(), brain: TextGenerator?, load: GenerationLoad) async -> Digest {
        let (start, end) = Clock.dayBounds(date)
        let total = store.countMessages(from: start, to: end)
        let sources = store.sourceCounts(from: start, to: end)
        let loops = store.openLoops()
        let urgent = loops.filter(\.isUrgent)
        let waiting = loops.filter { !$0.isUrgent }
        let (spent, received) = store.moneyTotals(from: start, to: end)

        var summary = Self.fallbackSummary(total: total, urgent: urgent.count, waiting: waiting.count, busiest: sources.first)
        var model: String?
        if let brain, total > 0, brain.available {
            var user = "Date: \(date.formatted(date: .complete, time: .omitted)).\n"
            user += "Figures (already computed): \(total) messages. Spent \(Fmt.rupees(spent)). Received \(Fmt.rupees(received)).\n\n"
            if loops.isEmpty {
                user += "Nobody is waiting on the user.\n"
            } else {
                user += "Waiting on the user: each of these people wants the user to reply or act (most important first):\n"
                for l in (urgent + waiting).prefix(6) {
                    user += "- \(l.isUrgent ? "[urgent] " : "")\(Self.line(l)) (since \(Fmt.clock(l.firstAt)))\n"
                }
            }
            let facts = store.facts().prefix(5)
            if !facts.isEmpty { user += "\nWhat Recall knows about the user:\n" + facts.map { "- \($0.text)" }.joined(separator: "\n") + "\n" }
            user += "\nToday's conversations (busiest first):\n" + Self.conversations(store.messages(from: start, to: end, includeSelf: false), budget: 5500)
            user += "\nWrite the summary now."
            if let out = await brain.generate(system: Prompts.digest, user: user, maxTokens: 160, temperature: 0.5, load: load, onText: nil),
               !out.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                summary = out.replacingOccurrences(of: "\"", with: "").components(separatedBy: .newlines).joined(separator: " ").trimmingCharacters(in: .whitespaces)
                model = "on-device"
            }
        }
        let d = Digest(day: Clock.dayKey(date), at: Clock.now, summary: summary, spentPaise: spent, receivedPaise: received,
                       waiting: waiting.count, urgent: urgent.count, model: model)
        store.saveDigest(d)
        return d
    }

    public static func line(_ l: OpenLoop) -> String {
        var ask = l.askText.replacingOccurrences(of: "\n", with: " ")
        if ask.count > 70 { ask = String(ask.prefix(68)).trimmingCharacters(in: .whitespaces) + "…" }
        return "\(l.person) (\(l.source.label)): \(ask)\(l.asks > 1 ? " · asked \(l.asks)×" : "")"
    }

    static func fallbackSummary(total: Int, urgent: Int, waiting: Int, busiest: (Source, Int)?) -> String {
        if total == 0 { return "A quiet day so far. Nothing has come in yet." }
        let needs: String
        if urgent > 0 && waiting > 0 { needs = "\(urgent) urgent and \(waiting) more waiting on you." }
        else if urgent > 0 { needs = urgent == 1 ? "1 urgent thing needs you." : "\(urgent) urgent things need you." }
        else if waiting > 0 { needs = waiting == 1 ? "1 person is waiting on you." : "\(waiting) people are waiting on you." }
        else { needs = "Nobody is waiting on you." }
        let busy = busiest.map { " Most came from \($0.0.label) (\($0.1))." } ?? ""
        return "\(needs) \(total) messages today.\(busy)"
    }

    /// Compresses a day of messages into one line per conversation.
    static func conversations(_ messages: [Message], budget: Int) -> String {
        var groups: [String: [Message]] = [:]
        for m in messages where !SenderHeuristics.isAutomated(m.sender, m.text) { groups[m.convKey, default: []].append(m) }
        var out = ""
        for g in groups.values.sorted(by: { $0.count > $1.count }) {
            let first = g[0]
            let title = first.convTitle ?? first.sender ?? first.source.label
            let samples = g.suffix(3).map { m in
                (first.isGroup && m.sender != nil ? "\(m.sender!): " : "") + String(m.text.replacingOccurrences(of: "\n", with: " ").prefix(90))
            }.joined(separator: " | ")
            let line = "- \(first.source.label) · \(title)\(first.isGroup ? " (group)" : ""), \(g.count) msg: \(samples)\n"
            if out.count + line.count > budget { break }
            out += line
        }
        return out
    }

    // MARK: - Status (widget, home screen)

    public func snapshot() -> StatusSnapshot { StatusSnapshot.make(store: store) }

    public static func sha1(_ s: String) -> String {
        Insecure.SHA1.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// What needs you right now, in the shape the widget and home screen show.
public struct StatusSnapshot: Codable, Sendable, Equatable {
    public struct Item: Codable, Sendable, Equatable, Identifiable {
        public let id: Int64
        public let person: String
        public let source: String
        public let text: String
        public let urgent: Bool
    }

    public let mood: String // calm, needs, urgent, off
    public let headline: String
    public let items: [Item]
    /// Everything waiting, not just the items shown.
    public let waitingCount: Int
    public let spentTodayPaise: Int64
    public let at: Int64

    public static func make(store: RecallStore, capturing: Bool = true) -> StatusSnapshot {
        let loops = store.openLoops()
        let urgent = loops.filter(\.isUrgent).count
        let headline: String
        if !capturing { headline = "Set up Shortcuts to start" }
        else if urgent > 0 && loops.count > urgent { headline = "\(urgent) urgent · \(loops.count - urgent) waiting on you" }
        else if urgent > 0 { headline = urgent == 1 ? "1 urgent thing needs you" : "\(urgent) urgent things need you" }
        else if !loops.isEmpty { headline = loops.count == 1 ? "1 person is waiting on you" : "\(loops.count) people are waiting on you" }
        else { headline = "Nothing needs you right now" }
        let mood = !capturing ? "off" : urgent > 0 ? "urgent" : loops.isEmpty ? "calm" : "needs"
        let (start, end) = Clock.dayBounds()
        return StatusSnapshot(
            mood: mood, headline: headline,
            items: loops.prefix(4).map { l in
                var t = l.askText.replacingOccurrences(of: "\n", with: " ")
                if t.count > 80 { t = String(t.prefix(78)) + "…" }
                return Item(id: l.id, person: l.person, source: l.source.label, text: t, urgent: l.isUrgent)
            },
            waitingCount: loops.count, spentTodayPaise: store.moneyTotals(from: start, to: end).0, at: Clock.now,
        )
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
