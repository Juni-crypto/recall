import Foundation

/**
 * An email conversation is one thread (sender + subject), not everything from a sender:
 * ten different alerts or PRs from one address are ten separate things.
 */
public enum MailThread {
    private static let PREFIX = RulePattern(#"^\s*((re|fwd?|aw|wg)\s*(\[\d+])?\s*:\s*)+"#, ignoreCase: true)
    private static let SPACE = RulePattern(#"\s+"#)

    /** "Re: Fwd: [Beacon] ETL run failed…" -> "[beacon] etl run failed" */
    public static func subject(_ text: String) -> String {
        let first = text.ktLines().first { !$0.ktIsBlank } ?? ""
        let s = SPACE.replace(PREFIX.replace(first, with: ""), with: " ").ktTrim().ktTrimEnd("…", ".").ktTrim()
        return s.lowercased().ktTake(60).ktTrim()
    }

    public static func key(_ pkg: String, _ sender: String?, _ text: String) -> String {
        "\(pkg)|\(sender ?? "")|\(subject(text))"
    }
}
