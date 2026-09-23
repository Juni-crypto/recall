import Foundation

/// What the model is told. Close to the Android app's wording; the review also asks the model
/// to repeat each sender's name, so a skipped item can't shift answers onto the wrong person.
public enum Prompts {
    public static let triage = """
    You read messages sent to the user and decide which ones need the user.
    Messages can be in English, Tamil, Hindi, Kannada, Telugu or mixed (Tanglish, Hinglish).
    Each numbered item shows the latest message and a few earlier lines of that conversation (for email, that thread).
    Lines starting "You:" were written by the user; if the user already answered, it doesn't need them.

    NEED = the sender wants the user to do, answer, decide, send, pay, call or approve something.
    Items marked (alert) are automated. They are NEED when they report a failure, errors, an outage or a
    suspension, because the user has to check it. Routine alerts (started, finished, updated) are FYI.
    FYI = news, thanks, jokes, forwards, greetings, routine alerts (started, finished, updated), or already answered.

    Urgency: 0 whenever, 1 soon, 2 today, 3 right now (emergency, blocking, repeated pings).

    Reply with exactly one line per item, in order, and nothing else:
    <number> | <name exactly as shown in the item> | NEED or FYI | <urgency 0-3> | <deadline in 1-3 words, or -> | <what they want, max 8 words, in English>
    Example: 2 | Arjun | NEED | 3 | before release | Approve PR #212
    Example: 3 | Beacon | NEED | 2 | - | Check the failed ETL run
    """

    public static let money = """
    You read bank, card and UPI messages and extract the money movement for the account holder.
    For each numbered message reply with exactly one line and nothing else:
    <number> | DEBIT or CREDIT or NONE | <amount as digits, e.g. 1299.00> | <who was paid or who paid, or ->
    Use NONE for OTPs, reminders, offers, bills due, requests and failed or future payments.
    """

    public static let digest = """
    You are Recall, a private assistant that lives only on the user's phone.
    From the messages the user got today, write a short summary of their day.
    Rules:
    - 2 or 3 sentences, at most 55 words, speaking to the user as "you".
    - Lead with what still needs them. The people listed under "Waiting on the user" are waiting
      for the USER to act, so write it that way round: "Amma is waiting for you to confirm the
      password change", never "you're waiting on Amma".
    - Then one line on what the day was mostly about.
    - Use only the facts given. Never invent names, times or events.
    - Use the money figures exactly as given; never add numbers up yourself.
    - No greeting, no lists, no emoji, no quotation marks.
    """

    public static let chat = """
    You are Recall, a private assistant that lives only on the user's phone.
    You answer questions about the user's own messages, the people who message them,
    what is waiting on them, and their spending.
    Rules:
    - Answer only from the records, figures and facts provided below the question.
    - Be brief: 1 to 4 sentences. Be specific: names, sources, days and times.
    - Speak to the user as "you". Messages marked "you →" were sent by the user.
    - Money figures are already computed; quote them exactly and never do arithmetic.
    - If the records don't contain the answer, say you couldn't find it in their messages.
    - Never invent messages, people or amounts.
    """

    static let triageLine = RulePattern(#"^\s*(\d+)\s*[|:.)-]\s*(?:([^|]*?)\s*\|\s*)?(NEED|FYI)\s*\|\s*([0-3])\s*\|\s*([^|]*)\|\s*(.*)$"#, ignoreCase: true)
    static let moneyLine = RulePattern(#"^\s*(\d+)\s*[|:.)-]\s*(DEBIT|CREDIT|NONE)\s*\|\s*([0-9.,]+|-)\s*\|\s*(.*)$"#, ignoreCase: true)

    public struct TriageAnswer: Equatable, Sendable {
        /// The name the model repeated, to check the answer belongs to this item.
        public let who: String?
        public let need: Bool
        public let urgency: Int
        public let due: String?
        public let ask: String
    }

    /// Model output lines keyed by item index (0-based). Lines that don't fit the format are ignored.
    public static func parseTriage(_ out: String) -> [Int: TriageAnswer] {
        var answers: [Int: TriageAnswer] = [:]
        for line in out.components(separatedBy: .newlines) {
            guard let m = triageLine.find(line), let n = Int(m.groupValue(1)) else { continue }
            let who = m.groupValue(2).trimmingCharacters(in: .whitespaces)
            let due = m.groupValue(5).trimmingCharacters(in: .whitespaces)
            var ask = m.groupValue(6).trimmingCharacters(in: .whitespaces)
            while ask.hasSuffix(".") { ask.removeLast() }
            answers[n - 1] = TriageAnswer(
                who: who.isEmpty ? nil : who,
                need: m.groupValue(3).uppercased() == "NEED", urgency: Int(m.groupValue(4)) ?? 0,
                due: (due.isEmpty || due == "-") ? nil : due, ask: ask,
            )
        }
        return answers
    }

    /// Puts each answer on the item whose name it repeats. An answer naming nobody in the batch is dropped;
    /// one without a name is trusted by its number.
    public static func align(_ answers: [Int: TriageAnswer], titles: [String]) -> [Int: TriageAnswer] {
        func norm(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber || $0 == " " }.trimmingCharacters(in: .whitespaces) }
        func same(_ a: String, _ b: String) -> Bool {
            let x = norm(a), y = norm(b)
            guard !x.isEmpty, !y.isEmpty else { return false }
            return x == y || x.contains(y) || y.contains(x) || x.split(separator: " ").first == y.split(separator: " ").first
        }
        var out: [Int: TriageAnswer] = [:]
        for (i, a) in answers.sorted(by: { $0.key < $1.key }) {
            guard let who = a.who else { if i < titles.count { out[i] = a }; continue }
            if i < titles.count, same(who, titles[i]) { out[i] = a; continue }
            let matches = titles.indices.filter { same(who, titles[$0]) && out[$0] == nil }
            if matches.count == 1 { out[matches[0]] = a }
        }
        return out
    }

    /// Small models sometimes copy the prompt's examples. An answer that repeats one is only
    /// kept when the message is actually about that thing.
    static let exampleAsks: [(ask: String, marker: String)] = [("approve pr #212", "#212"), ("check the failed etl run", "etl")]

    /// The example deadline, copied onto a message that says nothing about it.
    public static func isParrotedDue(_ due: String?, text: String) -> Bool {
        guard let d = due?.lowercased() else { return false }
        return d == "before release" && !text.lowercased().contains("release")
    }

    public static func isParroted(_ ask: String, text: String) -> Bool {
        let a = ask.lowercased().trimmingCharacters(in: .whitespaces)
        let t = text.lowercased()
        return exampleAsks.contains { a == $0.ask && !t.contains($0.marker) }
    }

    public struct MoneyAnswer: Equatable, Sendable {
        public let direction: String
        public let amountPaise: Int64
        public let party: String?
    }

    /// An amount only counts if those exact digits appear in the message, so the model can't invent money.
    public static func parseMoney(_ out: String, messages: [String]) -> [Int: MoneyAnswer] {
        var answers: [Int: MoneyAnswer] = [:]
        for line in out.components(separatedBy: .newlines) {
            guard let m = moneyLine.find(line), let n = Int(m.groupValue(1)), n >= 1, n <= messages.count else { continue }
            let dir: String
            switch m.groupValue(2).uppercased() { case "DEBIT": dir = "out"; case "CREDIT": dir = "in"; default: continue }
            let amountText = m.groupValue(3).replacingOccurrences(of: ",", with: "")
            guard let rupees = Double(amountText), rupees > 0 else { continue }
            let digits = amountText.split(separator: ".").first.map(String.init) ?? amountText
            let msg = messages[n - 1].replacingOccurrences(of: ",", with: "")
            guard msg.contains(digits) else { continue }
            let party = m.groupValue(4).trimmingCharacters(in: .whitespaces)
            answers[n - 1] = MoneyAnswer(direction: dir, amountPaise: Int64((rupees * 100).rounded()),
                                         party: (party.isEmpty || party == "-") ? nil : String(party.prefix(40)))
        }
        return answers
    }
}
