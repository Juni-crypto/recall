import Foundation

/**
 * Decides whether a message asks something of the user.
 * Deliberately rule-based: fast, explainable, and it works before any model is downloaded.
 */
public enum RequestDetector {
    public struct Result: Equatable, Sendable {
        public let isRequest: Bool
        public let reasons: [String]

        public init(isRequest: Bool, reasons: [String]) {
            self.isRequest = isRequest
            self.reasons = reasons
        }
    }

    private static let ASKS = [
        RulePattern(#"\b(can|could|would|will|pls|plz)\s+(you|u|ya|ye)\b"#),
        RulePattern(#"\b(please|pls|plz|kindly)\b"#),
        RulePattern(#"\b(approve|review|check|send|share|call|ping|reply|respond|confirm|merge|sign|pay|transfer|fix|update|look into|look at|take a look|forward|book|order|bring|pick up|drop)\b.*\b(me|this|it|the|my|pr|doc|file|invoice|link|details|asap|pls|please|us)\b"#),
        RulePattern(#"\b(let me know|lmk|any update|any news|following up|follow(ing)? up|gentle reminder|reminder)\b"#),
        RulePattern(#"\b(waiting (for|on) (you|your|u|ur))\b"#),
        RulePattern(#"\b(need (you|your|u|ur))\b"#),
        RulePattern(#"\b(are|r) (you|u) (free|available|there|around|coming|up|ok|home|awake|joining)\b"#),
        RulePattern(#"\b(did|have|will) (you|u)\b"#),
        RulePattern(#"\bwhen (can|will|are|r|do) (you|u)\b"#),
        RulePattern(#"\b(what time|where are you|where r u|call me|call back|callback|ring me)\b"#),
    ]

    private static let ACK = RulePattern(
        #"^(ok+|okay|k+|kk|sure|thanks|thank you|thx|ty|done|great|cool|nice|noted|yes|yeah|yep|no|nope|haha+|lol|👍|🙏|❤️|good night|gn|gm|good morning)[.! ]*$"#
    )
    private static let SPACES = RulePattern(#"\s+"#)

    public static func detect(_ text: String) -> Result {
        let t = text.lowercased().ktTrim()
        if t.isEmpty || ACK.matches(t) { return Result(isRequest: false, reasons: []) }
        var reasons: [String] = []
        if ASKS.contains(where: { $0.containsMatch(in: t) }) { reasons.append("ask") }
        let words = SPACES.split(t).filter { $0.ktAny(KtChar.isLetter) }.count
        if t.contains("?") && words >= 2 { reasons.append("question") }
        return Result(isRequest: !reasons.isEmpty, reasons: reasons)
    }
}
