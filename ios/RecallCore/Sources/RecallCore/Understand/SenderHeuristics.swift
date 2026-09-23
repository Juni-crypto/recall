import Foundation

/** Bank shortcodes, no-reply senders and promo channels. */
public enum SenderHeuristics {
    // Indian SMS headers look like "AX-HDFCBK", "VM-SWIGGY-S", "JD-ICICIT-T".
    private static let SMS_HEADER = RulePattern(#"^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$"#)
    private static let SHORT_CODE = RulePattern(#"^\d{3,6}$"#)
    private static let ALL_CAPS_ID = RulePattern(#"^[A-Z0-9]{5,11}$"#)
    private static let NO_REPLY = RulePattern(#"no-?reply|donotreply|notifications?@|alerts?@|mailer|newsletter"#, ignoreCase: true)
    private static let PROMO = RulePattern(
        #"\b(offer|sale|% off|flat \d+|coupon|cashback|deal of|limited time|shop now|buy now|use code|free delivery|win |congratulations|reward points|t&c)\b"#,
        ignoreCase: true
    )
    private static let OTP = RulePattern(#"\b(otp|one[- ]time password|verification code|security code|login code)\b"#, ignoreCase: true)

    public static func isAutomated(_ sender: String?, _ text: String) -> Bool {
        let s = sender?.ktTrim() ?? ""
        if SMS_HEADER.matches(s) || SHORT_CODE.matches(s) || ALL_CAPS_ID.matches(s) { return true }
        if NO_REPLY.containsMatch(in: s) { return true }
        return OTP.containsMatch(in: text)
    }

    public static func isPromo(_ text: String) -> Bool { PROMO.containsMatch(in: text) }

    // System mail: "[Beacon] Warehouse build finished", "3 alert(s) from …", CI and deploy notices.
    private static let SYSTEM_MAIL = RulePattern(
        #"^\s*\[[^\]]{2,40}]|\balert\(s\)|\b(build|deploy(ment)?|pipeline|job|run|workflow) (finished|failed|succeeded|passed|completed)\b|\b(weekly|daily|monthly) (report|digest|summary)\b|\bdo not reply\b"#,
        ignoreCase: true
    )

    public static func isSystemMail(_ text: String) -> Bool { SYSTEM_MAIL.containsMatch(in: text) }

    // An automated mail saying something broke or needs action: worth the model's look.
    private static let PROBLEM = RulePattern(
        #"\b(fail(ed|ing|ure|s)?|errors?|down|outage|crash(ed|ing)?|critical|incident|suspend(ed|ing)?|suspension|action required|immediate action|breach|expir(ed|es|ing)|overdue|blocked|rejected|declined|denied|unhealthy|timed? ?out)\b"#,
        ignoreCase: true
    )

    public static func isProblem(_ text: String) -> Bool { PROBLEM.containsMatch(in: text) }
}
