import Foundation

/**
 * Learns the account holder's name from bank messages ("Dear R KUMAR", "…:R KUMAR"), so money
 * moved between the user's own accounts isn't counted as spending or income.
 * Pure: the app stores the names (joined with "|") and passes them to Categories.
 */
public enum OwnerName {
    private static let PATTERNS = [
        RulePattern(#"\bDear\s+([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})\s*[,:]"#),
        RulePattern(#"MOBILE-[X\d]+:([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})"#),
        RulePattern(#"\bto\s+([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})\s+(?:A/c|a/c)\s+linked\b"#),
    ]
    private static let NOT_NAMES: Set<String> = ["CUSTOMER", "SIR", "MADAM", "USER", "MEMBER", "CARDHOLDER", "INVESTOR", "SIR/MADAM", "VALUED CUSTOMER"]
    private static let SPACES = RulePattern(#"\s+"#)

    /** Names from the stored pref ("A KUMAR|KUMAR A"). */
    public static func load(_ stored: String) -> [String] {
        stored.components(separatedBy: "|").filter { !$0.ktIsBlank }
    }

    /** The user says this name is theirs (e.g. their UPI ID or another bank's spelling). Returns the new stored pref. */
    public static func add(_ name: String, to stored: String) -> String {
        var names: [String] = []
        for n in (stored.components(separatedBy: "|") + [name]).map({ $0.ktTrim() }) where !n.isEmpty && !names.contains(n) {
            names.append(n)
        }
        return names.joined(separator: "|")
    }

    /** Names seen in at least three bank messages, most frequent first, at most three. Empty if none. */
    public static func detect(_ bankMessages: [String]) -> [String] {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for m in bankMessages {
            for p in PATTERNS {
                for r in p.findAll(m) {
                    let name = SPACES.replace(r.groupValue(1).ktTrim(), with: " ")
                    if !NOT_NAMES.contains(name.uppercased()) && name.ktLength >= 5 {
                        if counts[name] == nil { order.append(name) }
                        counts[name, default: 0] += 1
                    }
                }
            }
        }
        // Stable sort: ties keep first-seen order.
        let ranked = order.enumerated()
            .filter { counts[$0.element]! >= 3 }
            .sorted { a, b in
                let ca = counts[a.element]!, cb = counts[b.element]!
                return ca != cb ? ca > cb : a.offset < b.offset
            }
        return ranked.prefix(3).map(\.element)
    }
}
