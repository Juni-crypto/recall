import Foundation

/**
 * Splits a line of a bundled summary ("12 new emails" lists one line per email).
 * No platform types here, so it's unit-tested on the Mac.
 */
public enum SummaryLine {
    // "Beacon   [Beacon] Replica updated", "Priya: can you send it", "Karthik - Contract"
    private static let SPLIT = RulePattern(#"^(.{1,60}?)(?::\s|\s{2,}|\s[-–·|]\s)(.+)$"#)

    /** Sender and text from a line with no formatting left. The sender is nil if there isn't one. */
    public static func split(_ line: String) -> (sender: String?, text: String) {
        let s = line.ktTrim()
        if let m = SPLIT.find(s) {
            let sender = m.groupValue(1).ktTrim()
            let text = m.groupValue(2).ktTrim()
            if !sender.isEmpty && !text.isEmpty { return (sender, text) }
        }
        return (nil, s)
    }
}
