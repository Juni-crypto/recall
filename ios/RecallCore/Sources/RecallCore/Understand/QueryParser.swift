import Foundation

/** Pulls a time range, people, apps and intent out of a plain-language question. */
public enum QueryParser {
    /** Whole days: `start` and `endExclusive` are the start of a day in the calendar passed to `parse`. */
    public struct Range: Equatable, Hashable, Sendable {
        public let start: Date
        public let endExclusive: Date
        public let label: String

        public init(start: Date, endExclusive: Date, label: String) {
            self.start = start
            self.endExclusive = endExclusive
            self.label = label
        }
    }

    public struct Query: Equatable, Hashable, Sendable {
        public let range: Range
        public let rangeExplicit: Bool
        public let money: Bool
        public let waiting: Bool
        public let people: [String]      // conv keys
        public let peopleNames: [String]
        public let pkgs: [String]
        public let merchantTerm: String?
        public let keywords: [String]
    }

    private static let MONEY = RulePattern(#"\b(spen[dt]|spending|paid|pay|payments?|money|expenses?|₹|rs|rupees|cost|bought|buy|received|credited|debited|income|transactions?|how much)\b"#)
    private static let WAITING = RulePattern(#"\b(waiting|pending|reply|replied|respond|responded|owe|missed|miss|unanswered|forgot|haven'?t)\b"#)
    private static let STOP: Set<String> = [
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "from", "with", "about", "what", "who",
        "whom", "when", "where", "why", "how", "did", "do", "does", "is", "are", "was", "were", "be", "been", "i", "me",
        "my", "mine", "you", "your", "we", "us", "our", "it", "this", "that", "these", "those", "there", "any", "anything",
        "someone", "somebody", "today", "yesterday", "week", "month", "last", "this", "tonight", "morning", "evening",
        "want", "wanted", "say", "said", "tell", "told", "send", "sent", "message", "messages", "much", "many", "spend",
        "spent", "money", "have", "has", "had", "can", "could", "should", "would", "will", "get", "got", "show", "list",
        "all", "give", "recall", "please", "pls", "ask", "asked", "keep", "missing", "miss", "waiting", "reply", "days",
        "day", "ago", "lately", "recently", "just", "still", "yet", "not", "no", "yes", "up", "out", "so", "if", "then",
    ]
    private static let WORD = RulePattern(#"[\p{L}\p{N}']+"#)
    private static let TITLE_SPLIT = RulePattern(#"[\s·,]+"#)
    private static let SPACES = RulePattern(#"\s+"#)
    private static let MERCHANT_AFTER = RulePattern(#"\b(?:on|at|for|to)\s+([\p{L}]{3,})"#)
    private static let N_DAYS = RulePattern(#"(?:last|past)\s+(\d{1,3})\s+days"#)
    private static let NOT_WORD = RulePattern(#"[^\p{L}\p{N}]"#)

    /**
     * `people` are (conv key, title) pairs, `apps` are (package, label) pairs.
     * `today` is any moment of the current day in `calendar`.
     */
    public static func parse(
        _ question: String,
        today: Date,
        people: [(String, String)],
        apps: [(String, String)],
        merchants: [String],
        calendar: Calendar = .current
    ) -> Query {
        let q = question.lowercased()
        let money = MONEY.containsMatch(in: q)
        let (range, explicit) = range(q, today: today, money: money, calendar: calendar)

        let words = WORD.findAll(q).map(\.value)
        let wordSet = Set(words)

        let matchedPeople = people.filter { _, title in
            let parts = TITLE_SPLIT.split(title.lowercased()).filter { $0.ktLength >= 3 }
            return parts.contains { wordSet.contains($0) }
        }
        let matchedApps = apps.filter { _, label in
            wordSet.contains(label.lowercased()) || wordSet.contains(label.lowercased().replacingOccurrences(of: " ", with: ""))
        }.map(\.0)

        var merchant = merchants.first { m in
            m.lowercased().components(separatedBy: " ").filter { $0.ktLength >= 3 }.contains { wordSet.contains($0) }
        }
        if merchant == nil, let term = MERCHANT_AFTER.find(q)?.group(1), money && !STOP.contains(term) {
            merchant = term
        }

        let nameWords = Set(matchedPeople.flatMap { SPACES.split($0.1.lowercased()) })
        var keywords: [String] = []
        for w in words where w.ktLength >= 3 && !STOP.contains(w) && !nameWords.contains(w) && !w.ktAll(KtChar.isDigit) {
            if !keywords.contains(w) { keywords.append(w) }
            if keywords.count == 6 { break }
        }

        var peopleNames: [String] = []
        for (_, name) in matchedPeople where !peopleNames.contains(name) { peopleNames.append(name) }

        return Query(
            range: range, rangeExplicit: explicit, money: money, waiting: WAITING.containsMatch(in: q),
            people: matchedPeople.map(\.0), peopleNames: peopleNames,
            pkgs: matchedApps, merchantTerm: merchant, keywords: keywords
        )
    }

    private static func range(_ q: String, today now: Date, money: Bool, calendar cal: Calendar) -> (Range, Bool) {
        let today = cal.startOfDay(for: now)
        func days(_ n: Int, _ from: Date) -> Date { cal.date(byAdding: .day, value: n, to: from)! }
        // weekday: 1 is Sunday, 2 is Monday, whatever the locale's first day.
        let monday = days(-((cal.component(.weekday, from: today) + 5) % 7), today)
        let monthStart = cal.date(from: cal.dateComponents([.year, .month], from: today))!
        let tomorrow = days(1, today)

        if let nDays = N_DAYS.find(q).flatMap({ Int($0.groupValue(1)) }) {
            return (Range(start: days(-(nDays - 1), today), endExclusive: tomorrow, label: "the last \(nDays) days"), true)
        }
        if q.contains("yesterday") { return (Range(start: days(-1, today), endExclusive: today, label: "yesterday"), true) }
        if q.contains("today") || q.contains("tonight") || q.contains("this morning") || q.contains("this evening") {
            return (Range(start: today, endExclusive: tomorrow, label: "today"), true)
        }
        if q.contains("last week") { return (Range(start: days(-7, monday), endExclusive: monday, label: "last week"), true) }
        if q.contains("this week") || q.contains("week") { return (Range(start: monday, endExclusive: tomorrow, label: "this week"), true) }
        if q.contains("last month") {
            let lastMonthStart = cal.date(byAdding: .month, value: -1, to: monthStart)!
            return (Range(start: lastMonthStart, endExclusive: monthStart, label: "last month"), true)
        }
        if q.contains("this month") || q.contains("month") { return (Range(start: monthStart, endExclusive: tomorrow, label: "this month"), true) }
        if money { return (Range(start: monthStart, endExclusive: tomorrow, label: "this month"), false) }
        return (Range(start: days(-13, today), endExclusive: tomorrow, label: "the last two weeks"), false)
    }

    /** FTS4 query: prefix match on any keyword. */
    public static func ftsQuery(_ keywords: [String]) -> String? {
        let terms = keywords.map { NOT_WORD.replace($0, with: "") }.filter { $0.ktLength >= 3 }
        return terms.isEmpty ? nil : terms.map { "\($0)*" }.joined(separator: " OR ")
    }
}
