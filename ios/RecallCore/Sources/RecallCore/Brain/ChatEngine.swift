import Foundation

/// Answers questions about your own messages and money, entirely on the phone.
/// Figures come from SQL; the model only words the answer.
@MainActor
public final class ChatEngine {
    private let store: RecallStore
    private let engine: RecallEngine

    public init(engine: RecallEngine) {
        self.engine = engine
        self.store = engine.store
    }

    public struct Answer: Sendable { public let text: String; public let sources: [String] }

    public func ask(_ question: String, brain: TextGenerator?, onPartial: (@Sendable (String) -> Void)? = nil) async -> Answer {
        store.addChat(role: "user", text: question)
        let today = Date()
        let apps = Source.allCases.map { ($0.rawValue, $0.label) } + [("mail", "email"), ("mail", "gmail"), ("sms", "sms"), ("sms", "text")]
        let q = QueryParser.parse(question, today: today, people: store.people(), apps: apps, merchants: store.merchants())
        let start = Clock.ms(q.range.start)
        let end = Clock.ms(q.range.endExclusive)

        // Money: computed here, never by the model.
        var money: [String] = []
        if q.money {
            let (spent, received) = store.moneyTotals(from: start, to: end)
            let txns = store.txns(from: start, to: end)
            money.append("Total spent \(q.range.label): \(Fmt.rupees(spent)) across \(txns.filter(\.isOut).count) payments.")
            money.append("Total received \(q.range.label): \(Fmt.rupees(received)).")
            if let term = q.merchantTerm {
                let matches = store.spentMatching(term, from: start, to: end)
                if let biggest = matches.max(by: { $0.amountPaise < $1.amountPaise }) {
                    money.append("Spent on \"\(term)\" \(q.range.label): \(Fmt.rupees(matches.reduce(0) { $0 + $1.amountPaise })) across \(matches.count) payments. Biggest: \(Fmt.rupees(biggest.amountPaise)) on \(Fmt.when(biggest.at)).")
                } else {
                    money.append("No payments matching \"\(term)\" \(q.range.label).")
                }
            }
            let byCat = store.categoryTotals(from: start, to: end).filter { !Categories.isMovement($0.category) }.prefix(5)
            if !byCat.isEmpty { money.append("By category: " + byCat.map { "\($0.category) \(Fmt.rupees($0.paise))" }.joined(separator: "; ")) }
            for t in txns.prefix(8) {
                money.append("\(Fmt.when(t.at)): \(t.isOut ? "paid" : "received") \(Fmt.rupees(t.amountPaise))" + (t.merchant.map { t.isOut ? " to \($0)" : " from \($0)" } ?? ""))
            }
        }

        // Messages
        var messages: [Message]
        if !q.people.isEmpty {
            messages = store.messages(convKeys: q.people, from: start, to: end, limit: 40)
        } else if q.money && q.keywords.isEmpty {
            messages = []
        } else {
            let found = store.search(q.keywords, from: start, to: end, limit: 40)
            let filtered = q.pkgs.isEmpty ? found : found.filter { q.pkgs.contains($0.source.rawValue) }
            if !filtered.isEmpty { messages = filtered.sorted { $0.at < $1.at } }
            else if !q.pkgs.isEmpty { messages = Array(store.messages(from: start, to: end).filter { q.pkgs.contains($0.source.rawValue) }.suffix(40)) }
            else if q.keywords.isEmpty && !q.waiting { messages = Array(store.messages(from: start, to: end).suffix(30)) }
            else { messages = [] }
        }

        let all = store.openLoops()
        let loops = !q.people.isEmpty ? all.filter { q.people.contains($0.convKey) } : (q.waiting ? all : Array(all.prefix(3)))
        let sources = Array(NSOrderedSet(array: messages.suffix(4).map { "\($0.source.label) · \(Fmt.when($0.at))" }).array as? [String] ?? [])

        var context = ""
        if !messages.isEmpty {
            context += "Message records (\(q.range.label)):\n" + messages.map(Self.record).joined(separator: "\n") + "\n\n"
        }
        if !loops.isEmpty {
            context += "Still waiting on the user:\n" + loops.prefix(8).map { "- \($0.person) (\($0.source.label)), since \(Fmt.relative($0.firstAt)): \($0.askText.prefix(140))" }.joined(separator: "\n") + "\n\n"
        }
        if !money.isEmpty { context += "Money figures (exact):\n" + money.map { "- \($0)" }.joined(separator: "\n") + "\n\n" }
        let facts = store.facts().prefix(8)
        if !facts.isEmpty { context += "What Recall has learned:\n" + facts.map { "- \($0.text)" }.joined(separator: "\n") + "\n\n" }
        let history = store.chat(limit: 6).dropLast()
        if !history.isEmpty {
            context += "Earlier in this chat:\n" + history.map { "\($0.role == "user" ? "User" : "Recall"): \($0.text.prefix(200))" }.joined(separator: "\n") + "\n\n"
        }
        context += "Today is \(today.formatted(date: .complete, time: .shortened))."

        var text: String?
        if let brain, brain.available {
            text = await brain.generate(system: Prompts.chat, user: "Question: \(question)\n\n\(context)\nAnswer the question.",
                                        maxTokens: 220, temperature: 0.3, load: .interactive, onText: onPartial)
        }
        let answer = (text?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
            ?? Self.fallback(q, messages: messages, money: money, loops: loops, hasModel: brain?.available == true)
        store.addChat(role: "assistant", text: answer, sources: sources)
        return Answer(text: answer, sources: sources)
    }

    private static func record(_ m: Message) -> String {
        let who = m.isSelf ? "you → \(m.convTitle ?? "them")" : (m.isGroup ? "\(m.sender ?? "someone") in \(m.convTitle ?? "a group")" : (m.sender ?? m.convTitle ?? m.source.label))
        return "[\(Fmt.when(m.at)) · \(m.source.label) · \(who)] \(m.text.replacingOccurrences(of: "\n", with: " ").prefix(220))"
    }

    /// Without a model, answer with the facts themselves.
    private static func fallback(_ q: QueryParser.Query, messages: [Message], money: [String], loops: [OpenLoop], hasModel: Bool) -> String {
        if !money.isEmpty { return money.prefix(3).joined(separator: "\n") }
        if q.waiting && !loops.isEmpty {
            return "Waiting on you:\n" + loops.prefix(5).map { "• \($0.person) (\($0.source.label)): \($0.askText.prefix(100))" }.joined(separator: "\n")
        }
        if messages.isEmpty { return "I couldn't find anything about that in your messages." }
        var s = "Here's what I found:\n" + messages.suffix(5).map { "• \(Fmt.when($0.at)), \($0.sender ?? $0.source.label): \($0.text.replacingOccurrences(of: "\n", with: " ").prefix(120))" }.joined(separator: "\n")
        if !hasModel { s += "\n\nDownload a model for full answers." }
        return s
    }
}
