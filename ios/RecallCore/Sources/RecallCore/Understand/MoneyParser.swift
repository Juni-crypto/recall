import Foundation

/**
 * Reads money movements out of bank SMS, UPI app and card alerts.
 * Rule-based on purpose: amounts must be exact, so no model is involved.
 */
public enum MoneyParser {
    /** Bump when parsing changes; stored transactions are then rebuilt from saved messages. */
    public static let VERSION = 7

    public struct Parsed: Equatable, Hashable, Sendable {
        public let amountPaise: Int64
        public let direction: String
        public let merchant: String?
        public let account: String?

        public init(amountPaise: Int64, direction: String, merchant: String?, account: String?) {
            self.amountPaise = amountPaise
            self.direction = direction
            self.merchant = merchant
            self.account = account
        }
    }

    private static let SKIP = RulePattern(
        #"\b(otp|one[- ]time password|verification code|will be debited|to be debited|would be debited|is due|due on|due date|due by|payment due|min(imum)? (amt|amount)|total (amt|amount) due|bill (is )?generated|statement|requested|request(ing)? (money|₹|rs|inr)|collect request|has requested|(purchase|redemption|sip|switch) request|request (for|of) (rs|inr|₹)|offer|up ?to|get (₹|rs|flat)|win |apply now|pre-?approved|loan of|failed|declined|unsuccessful|reversed|reversal|mandate|auto-?pay (is )?(set|scheduled|registered)|reminder|expir(es|ing|y)|recharge (now|today)|avail (now|the)|passbook balance|balance (is|as on))\b"#,
        ignoreCase: true
    )
    private static let BALANCE = RulePattern(
        #"(avl\.?|avail(able)?\.?|a/c|total|current|closing|ledger|clr)?\s*(bal(ance)?|limit|lmt)\b\s*(is|:|-|of)?\s*(₹|rs\.?|inr)?\s*[0-9][0-9,]*(\.[0-9]{1,2})?"#,
        ignoreCase: true
    )
    private static let AMOUNT_BEFORE = RulePattern(#"(₹|\brs\.?|\binr)\s*([0-9][0-9,]*(\.[0-9]{1,2})?)"#, ignoreCase: true)
    private static let AMOUNT_AFTER = RulePattern(#"\b([0-9][0-9,]*(\.[0-9]{1,2})?)\s*(₹|rs\b|inr\b|rupees\b)"#, ignoreCase: true)

    private static let IN_EXPLICIT = RulePattern(#"\b(paid you|sent you|credited to your|credited in your|received in your|deposited in your|added to your|refund(ed)?|cashback of (₹|rs|inr)\s*[0-9]+ (has been )?credited)\b"#, ignoreCase: true)
    private static let OUT_STRONG = RulePattern(#"\b(debited|withdrawn|withdrawal|spent|deducted)\b"#, ignoreCase: true)
    private static let IN_WORDS = RulePattern(#"\b(credited|received|deposited)\b"#, ignoreCase: true)
    private static let OUT_WORDS = RulePattern(#"\b(paid|sent|purchase|transferred to|txn of|charged|payment of|payment to|paying)\b"#, ignoreCase: true)

    private static let STOP_AFTER =
        #"on|at|to|via|ref|upi|using|from|avl|avail|txn|info|for|thru|through|dated|date|is|was|has|with|by|and|if|not|your|a/c|ac|in|of|ending"#

    private static let TO_MERCHANT = RulePattern(
        #"\b(?:to|at|towards|for|on)\s+(?:vpa\s+|merchant\s+|m/s\.?\s+)?([A-Za-z0-9][A-Za-z0-9&.'@_\- ]{1,40}?)(?=\s*[,;:()\n]|\.(?=\s|$)|\s+(?:"#
            + STOP_AFTER + #")\b|\s*$)"#,
        ignoreCase: true
    )
    private static let FROM_MERCHANT = RulePattern(
        #"\b(?:from|by)\s+(?:vpa\s+)?([A-Za-z0-9][A-Za-z0-9&.'@_\- ]{1,40}?)(?=\s*[,;:()\n]|\.(?=\s|$)|\s+(?:"#
            + STOP_AFTER + #")\b|\s*$)"#,
        ignoreCase: true
    )
    private static let PAID_YOU = RulePattern(#"^(.{2,40}?)\s+(?:has\s+)?(?:paid|sent)\s+you"#, ignoreCase: true)
    private static let VPA = RulePattern(#"\b([a-z0-9][a-z0-9._\-]{2,})@[a-z]{2,}\b"#, ignoreCase: true)
    private static let ACCOUNT = RulePattern(
        #"\b(?:a/c|acct|account|card|ac)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?)?\s*[x*#.]*\s*(\d{3,4})\b"#,
        ignoreCase: true
    )
    private static let BAD_MERCHANT = RulePattern(
        #"^(your|you|a/c|ac|account|card|beneficiary|mobile|number|upi|bank|credit card|debit card|us|me|vpa|merchant|date|txn|ref|imps|neft|rtgs|info|self|report|making|block|call|complete|details)\b"#,
        ignoreCase: true
    )
    private static let CORP_SUFFIX = RulePattern(#"\s+(pvt\.?|priv\w*|pri|pr|private|ltd\.?|limited|llp|inc\.?)\b.*$"#, ignoreCase: true)

    private static let SPACES = RulePattern(#"\s+"#)

    public static func parse(_ text: String) -> Parsed? {
        let flat = SPACES.replace(text.replacingOccurrences(of: "\n", with: " "), with: " ").ktTrim()
        if SKIP.containsMatch(in: flat) { return nil }

        let direction: String
        if IN_EXPLICIT.containsMatch(in: flat) {
            direction = "in"
        } else if OUT_STRONG.containsMatch(in: flat) {
            direction = "out"
        } else if IN_WORDS.containsMatch(in: flat) {
            direction = "in"
        } else if OUT_WORDS.containsMatch(in: flat) {
            direction = "out"
        } else {
            return nil
        }

        let cleaned = BALANCE.replace(flat, with: " ")
        guard let amount = AMOUNT_BEFORE.find(cleaned)?.groupValue(2)
            ?? AMOUNT_AFTER.find(cleaned)?.groupValue(1)
        else { return nil }
        guard let rupees = Double(amount.replacingOccurrences(of: ",", with: "")) else { return nil }
        if rupees <= 0 || rupees > 1e8 { return nil }

        let merchant = merchant(cleaned, direction)
        let account = ACCOUNT.find(cleaned)?.groupValue(1)
        return Parsed(amountPaise: Int64((rupees * 100).rounded()), direction: direction, merchant: merchant, account: account)
    }

    // Bank formats that name the other party: salary credits, NEFT/IMPS, UPI paths.
    private static let SALARY = RulePattern(#"\bsalary[-: ]+([A-Za-z][A-Za-z0-9 &.]{2,40}?)(?=\s+(?:private|pvt|ltd|limited)\b|[.,]|$)"#, ignoreCase: true)
    private static let NEFT = RulePattern(#"\b(?:NEFT|IMPS|RTGS)[ -]?(?:Cr|Dr)?-[A-Z0-9]{4,}-([A-Za-z][A-Za-z0-9 &.]{2,40}?)\s*(?=-|$)"#, ignoreCase: true)
    private static let UPI_PATH = RulePattern(#"\bUPI/(?:P2A|P2M|CR|DR)/\d+/([^/]{2,40}?)(?=/|\s+not you|\s*$)"#, ignoreCase: true)

    private static func merchant(_ text: String, _ direction: String) -> String? {
        for p in [SALARY, NEFT, UPI_PATH] {
            if let g = p.find(text)?.groupValue(1), let c = clean(g) { return c }
        }
        if direction == "in" {
            // Title and body are joined with ". " upstream; the payer is in the last sentence.
            if let candidate = PAID_YOU.find(text)?.groupValue(1),
               let c = clean(candidate.components(separatedBy: ". ").last ?? candidate) {
                return c
            }
        }
        let pattern = direction == "in" ? FROM_MERCHANT : TO_MERCHANT
        for m in pattern.findAll(text) {
            if let c = clean(m.groupValue(1)) { return c }
        }
        if let v = VPA.find(text)?.groupValue(1) { return prettify(v) }
        return nil
    }

    // "HDFC Bank Card 1234", "a/c **5678": the user's own card or account, never a merchant.
    private static let OWN_INSTRUMENT = RulePattern(#"\b(card|a/c|acct|account|bank)\b.*\d{3,}|^[x*#]+\d+$"#, ignoreCase: true)
    private static let LEADING_THE = RulePattern(#"^the\s+"#, ignoreCase: true)

    private static func clean(_ raw: String) -> String? {
        var s = LEADING_THE.replace(raw.ktTrim().ktTrimEnd(".", "-", "_"), with: "")
        if s.ktLength < 2 || BAD_MERCHANT.containsMatch(in: s) || OWN_INSTRUMENT.containsMatch(in: s) { return nil }
        let letters = s.ktCount(KtChar.isLetter)
        if letters < 2 || letters < s.ktCount(KtChar.isDigit) { return nil }
        if let at = s.firstIndex(of: "@") { return prettify(String(s[..<at])) }
        s = CORP_SUFFIX.replace(s, with: "").ktTrim()
        let p = prettify(s)
        return p.ktLength >= 2 ? p : nil
    }

    private static let SEPARATORS = RulePattern(#"[._\-]+"#)

    private static func prettify(_ s: String) -> String {
        let words = SPACES.split(SEPARATORS.replace(s, with: " ").ktTrim())
        let allCaps = !s.ktAny(KtChar.isLowerCase)
        return words.map { w -> String in
            if w.ktLength <= 3 && w.ktAll(KtChar.isUpperCase) { return w } // HP, SBI, KFC
            if allCaps || w.ktAll(KtChar.isLowerCase) {
                let lower = w.lowercased()
                return lower.prefix(1).uppercased() + lower.dropFirst()
            }
            return w
        }.joined(separator: " ")
    }
}

public enum Categories {
    private static let RULES: [(String, RulePattern)] = [
        ("Food", RulePattern(#"swiggy|zomato|eatsure|domino|mcdonald|kfc|pizza|burger|starbucks|cafe|restaurant|biryani|chai|food|bakery|baking|juice|dunzo|hotel|tiffin|mess\b|dhaba|bhavan"#)),
        ("Groceries", RulePattern(#"blinkit|blink commerce|zepto|kiranakart|bigbasket|supermarket|super market|instamart|dmart|avenue supermarts|grofers|jiomart|more retail|kirana|fresh|natures basket|meat|chicken"#)),
        ("Travel", RulePattern(#"uber|ola|rapido|irctc|redbus|metro|makemytrip|goibibo|cleartrip|indigo|air india|vistara|akasa|fastag|parking|yatra"#)),
        ("Fuel", RulePattern(#"petrol|fuel|hpcl|\bhp\b|bpcl|iocl|indian oil|shell|bharat petroleum|filling station"#)),
        ("Shopping", RulePattern(#"amazon|flipkart|myntra|ajio|meesho|nykaa|croma|reliance digital|decathlon|ikea|tata cliq|lenskart"#)),
        ("Subscriptions", RulePattern(#"netflix|spotify|prime video|hotstar|youtube|apple|google play|google one|icloud|jio ?cinema|sonyliv|zee5|chatgpt|openai|anthropic|claude"#)),
        ("Bills", RulePattern(#"airtel|\bjio\b|\bvi\b|vodafone|bsnl|electricity|bescom|tata power|adani|mseb|gas|broadband|act fibernet|water|insurance|lic\b|postpaid|dth"#)),
        ("Health", RulePattern(#"pharma|apollo|medplus|1mg|netmeds|pharmeasy|hospital|clinic|diagnostic|lab\b|doctor"#)),
        ("Rent", RulePattern(#"\brent\b|landlord|nobroker|housing"#)),
    ]

    /** Money moved between your own accounts and investments: neither spending nor income. */
    public static let SAVINGS = "Savings"
    private static let SAVINGS_RE = RulePattern(
        #"\b(fd|fixed deposit|rd|recurring deposit|matured|maturity|auto[_ -]?redeem|prin(cipal)? and int|mutual fund|folio|sip|nps|ppf|zerodha|groww|kuvera|smallcase|demat|sweep|td|term deposit|flexi deposit|deposit prin|dep prin)\b|\bTO For \d{9,}"#,
        ignoreCase: true
    )
    public static let PEOPLE = "People"
    private static let P2A = RulePattern(#"\bUPI/P2A/"#, ignoreCase: true)

    /** Categories the on-device model may choose from for merchants the rules don't know. */
    public static let ALL = ["Food", "Groceries", "Travel", "Fuel", "Shopping", "Subscriptions", "Bills", "Health", "Rent", "Education", "Entertainment", PEOPLE, "Other"]

    private static let SALARY_RE = RulePattern(#"\bsalary\b"#, ignoreCase: true)

    private static func squash(_ s: String) -> String {
        String(String.UnicodeScalarView(s.lowercased().unicodeScalars.filter(KtChar.isLetter)))
    }

    private static let WORDS = RulePattern(#"[^a-z]+"#)

    /**
     * Banks cut and reorder names: "R Kumarasw", "R KUMARA", "Kumaraswamy R" all match "R KUMARASWAMY".
     * `ownerNames` are the account holder name(s), learned from bank messages (see OwnerName).
     */
    public static func isOwner(_ name: String?, ownerNames: [String]) -> Bool {
        guard let name else { return false }
        let n = squash(name)
        if n.ktLength < 5 { return false }
        return ownerNames.contains { o in
            let q = squash(o)
            if q.ktLength >= 5 && (q.hasPrefix(n) || n.hasPrefix(q)) { return true }
            // Same words in any order, each possibly cut short; the longest must be a real word.
            let mine = WORDS.split(o.lowercased()).filter { !$0.isEmpty }
            let theirs = WORDS.split(name.lowercased()).filter { !$0.isEmpty }
            return !theirs.isEmpty && theirs.map(\.ktLength).max()! >= 4 &&
                theirs.allSatisfy { t in mine.contains { m in m.hasPrefix(t) || (m.ktLength >= 4 && t.hasPrefix(m)) } }
        }
    }

    /** Money moved between the user's own accounts: never spending, never income. */
    public static let SELF = "Self transfer"
    private static let SELF_RE = RulePattern(#"\bto a/c\s*[x*#]+\d{3,4}|\bto self\b|\bown account|\bself[- ]transfer"#, ignoreCase: true)

    /** Money moving around (savings, investments, own accounts), not money spent or earned. */
    public static func isMovement(_ category: String) -> Bool { category == SAVINGS || category == SELF }

    private static let SWEEP = RulePattern(#"\bsweep\b"#)
    private static let FIXED = RulePattern(#"\b(fd|fixed deposit|td|term deposit|flexi deposit)\b|\bto for \d{9,}"#)
    private static let RECURRING = RulePattern(#"\b(rd|recurring deposit)\b"#)
    private static let FUNDS = RulePattern(#"mutual fund|folio|\bsip\b|\bnav\b|units allotted"#)
    private static let STOCKS = RulePattern(#"zerodha|groww|upstox|demat|shares|\bnse\b|\bbse\b|smallcase|angel one"#)
    private static let PENSION = RulePattern(#"\b(ppf|nps)\b"#)
    private static let MATURED = RulePattern(#"matured|maturity|auto[_ -]?redeem|prin(cipal)? and int"#)

    /** What kind of saving or investment a Savings movement is, read from its message. */
    public static func investmentType(_ raw: String?, merchant: String? = nil) -> String {
        let t = raw?.lowercased() ?? ""
        if SWEEP.containsMatch(in: t) { return "Sweep deposits" }
        if FIXED.containsMatch(in: t) { return "Fixed deposits" }
        if RECURRING.containsMatch(in: t) { return "Recurring deposits" }
        if FUNDS.containsMatch(in: t) { return "Mutual funds" }
        if STOCKS.containsMatch(in: t) { return "Stocks" }
        if PENSION.containsMatch(in: t) { return "PPF / NPS" }
        if MATURED.containsMatch(in: t) { return "Fixed deposits" }
        return "Other savings"
    }

    public static func of(_ merchant: String?, _ direction: String, _ raw: String = "", ownerNames: [String] = []) -> String {
        if isOwner(merchant, ownerNames: ownerNames) || SELF_RE.containsMatch(in: raw) { return SELF }
        if SAVINGS_RE.containsMatch(in: raw) { return SAVINGS }
        if direction == "in" { return SALARY_RE.containsMatch(in: raw) ? "Salary" : "Received" }
        if let m = merchant?.lowercased(), let rule = RULES.first(where: { $0.1.containsMatch(in: m) }) { return rule.0 }
        // UPI person-to-person payments ("P2A") go to a person, not a shop.
        if P2A.containsMatch(in: raw) { return PEOPLE }
        if merchant == nil, let rule = RULES.first(where: { $0.0 == "Bills" && $0.1.containsMatch(in: raw.lowercased()) }) { return rule.0 }
        return "Other"
    }
}
