package app.recall.understand

import kotlin.math.roundToLong

/**
 * Reads money movements out of bank SMS, UPI app and card alerts.
 * Rule-based on purpose: amounts must be exact, so no model is involved.
 */
object MoneyParser {
    /** Bump when parsing changes; stored transactions are then rebuilt from saved messages. */
    const val VERSION = 7

    data class Parsed(val amountPaise: Long, val direction: String, val merchant: String?, val account: String?)

    private val I = RegexOption.IGNORE_CASE

    private val SKIP = Regex(
        """\b(otp|one[- ]time password|verification code|will be debited|to be debited|would be debited|is due|due on|due date|due by|payment due|min(imum)? (amt|amount)|total (amt|amount) due|bill (is )?generated|statement|requested|request(ing)? (money|₹|rs|inr)|collect request|has requested|(purchase|redemption|sip|switch) request|request (for|of) (rs|inr|₹)|offer|up ?to|get (₹|rs|flat)|win |apply now|pre-?approved|loan of|failed|declined|unsuccessful|reversed|reversal|mandate|auto-?pay (is )?(set|scheduled|registered)|reminder|expir(es|ing|y)|recharge (now|today)|avail (now|the)|passbook balance|balance (is|as on))\b""",
        I,
    )
    private val BALANCE = Regex(
        """(avl\.?|avail(able)?\.?|a/c|total|current|closing|ledger|clr)?\s*(bal(ance)?|limit|lmt)\b\s*(is|:|-|of)?\s*(₹|rs\.?|inr)?\s*[0-9][0-9,]*(\.[0-9]{1,2})?""",
        I,
    )
    private val AMOUNT_BEFORE = Regex("""(₹|\brs\.?|\binr)\s*([0-9][0-9,]*(\.[0-9]{1,2})?)""", I)
    private val AMOUNT_AFTER = Regex("""\b([0-9][0-9,]*(\.[0-9]{1,2})?)\s*(₹|rs\b|inr\b|rupees\b)""", I)

    private val IN_EXPLICIT = Regex("""\b(paid you|sent you|credited to your|credited in your|received in your|deposited in your|added to your|refund(ed)?|cashback of (₹|rs|inr)\s*[0-9]+ (has been )?credited)\b""", I)
    private val OUT_STRONG = Regex("""\b(debited|withdrawn|withdrawal|spent|deducted)\b""", I)
    private val IN_WORDS = Regex("""\b(credited|received|deposited)\b""", I)
    private val OUT_WORDS = Regex("""\b(paid|sent|purchase|transferred to|txn of|charged|payment of|payment to|paying)\b""", I)

    private const val STOP_AFTER =
        """on|at|to|via|ref|upi|using|from|avl|avail|txn|info|for|thru|through|dated|date|is|was|has|with|by|and|if|not|your|a/c|ac|in|of|ending"""

    private val TO_MERCHANT = Regex(
        """\b(?:to|at|towards|for|on)\s+(?:vpa\s+|merchant\s+|m/s\.?\s+)?([A-Za-z0-9][A-Za-z0-9&.'@_\- ]{1,40}?)(?=\s*[,;:()\n]|\.(?=\s|$)|\s+(?:$STOP_AFTER)\b|\s*$)""",
        I,
    )
    private val FROM_MERCHANT = Regex(
        """\b(?:from|by)\s+(?:vpa\s+)?([A-Za-z0-9][A-Za-z0-9&.'@_\- ]{1,40}?)(?=\s*[,;:()\n]|\.(?=\s|$)|\s+(?:$STOP_AFTER)\b|\s*$)""",
        I,
    )
    private val PAID_YOU = Regex("""^(.{2,40}?)\s+(?:has\s+)?(?:paid|sent)\s+you""", I)
    private val VPA = Regex("""\b([a-z0-9][a-z0-9._\-]{2,})@[a-z]{2,}\b""", I)
    private val ACCOUNT = Regex(
        """\b(?:a/c|acct|account|card|ac)\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?)?\s*[x*#.]*\s*(\d{3,4})\b""",
        I,
    )
    private val BAD_MERCHANT = Regex(
        """^(your|you|a/c|ac|account|card|beneficiary|mobile|number|upi|bank|credit card|debit card|us|me|vpa|merchant|date|txn|ref|imps|neft|rtgs|info|self|report|making|block|call|complete|details)\b""",
        I,
    )
    private val CORP_SUFFIX = Regex("""\s+(pvt\.?|priv\w*|pri|pr|private|ltd\.?|limited|llp|inc\.?)\b.*$""", I)

    fun parse(text: String): Parsed? {
        val flat = text.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (SKIP.containsMatchIn(flat)) return null

        val direction = when {
            IN_EXPLICIT.containsMatchIn(flat) -> "in"
            OUT_STRONG.containsMatchIn(flat) -> "out"
            IN_WORDS.containsMatchIn(flat) -> "in"
            OUT_WORDS.containsMatchIn(flat) -> "out"
            else -> return null
        }

        val cleaned = BALANCE.replace(flat, " ")
        val amount = AMOUNT_BEFORE.find(cleaned)?.groupValues?.get(2)
            ?: AMOUNT_AFTER.find(cleaned)?.groupValues?.get(1)
            ?: return null
        val rupees = amount.replace(",", "").toDoubleOrNull() ?: return null
        if (rupees <= 0 || rupees > 1e8) return null

        val merchant = merchant(cleaned, direction)
        val account = ACCOUNT.find(cleaned)?.groupValues?.get(1)
        return Parsed((rupees * 100).roundToLong(), direction, merchant, account)
    }

    // Bank formats that name the other party: salary credits, NEFT/IMPS, UPI paths.
    private val SALARY = Regex("""\bsalary[-: ]+([A-Za-z][A-Za-z0-9 &.]{2,40}?)(?=\s+(?:private|pvt|ltd|limited)\b|[.,]|$)""", I)
    private val NEFT = Regex("""\b(?:NEFT|IMPS|RTGS)[ -]?(?:Cr|Dr)?-[A-Z0-9]{4,}-([A-Za-z][A-Za-z0-9 &.]{2,40}?)\s*(?=-|$)""", I)
    private val UPI_PATH = Regex("""\bUPI/(?:P2A|P2M|CR|DR)/\d+/([^/]{2,40}?)(?=/|\s+not you|\s*$)""", I)

    private fun merchant(text: String, direction: String): String? {
        for (p in listOf(SALARY, NEFT, UPI_PATH)) {
            p.find(text)?.groupValues?.get(1)?.let { clean(it) }?.let { return it }
        }
        if (direction == "in") {
            // Title and body are joined with ". " upstream; the payer is in the last sentence.
            PAID_YOU.find(text)?.groupValues?.get(1)?.let { candidate ->
                clean(candidate.split(". ").last())?.let { return it }
            }
        }
        val pattern = if (direction == "in") FROM_MERCHANT else TO_MERCHANT
        for (m in pattern.findAll(text)) {
            clean(m.groupValues[1])?.let { return it }
        }
        VPA.find(text)?.groupValues?.get(1)?.let { return prettify(it) }
        return null
    }

    // "HDFC Bank Card 1234", "a/c **5678": the user's own card or account, never a merchant.
    private val OWN_INSTRUMENT = Regex("""\b(card|a/c|acct|account|bank)\b.*\d{3,}|^[x*#]+\d+$""", I)

    private fun clean(raw: String): String? {
        var s = raw.trim().trimEnd('.', '-', '_').replace(Regex("""^the\s+""", I), "")
        if (s.length < 2 || BAD_MERCHANT.containsMatchIn(s) || OWN_INSTRUMENT.containsMatchIn(s)) return null
        val letters = s.count(Char::isLetter)
        if (letters < 2 || letters < s.count(Char::isDigit)) return null
        if (s.contains('@')) return prettify(s.substringBefore('@'))
        s = CORP_SUFFIX.replace(s, "").trim()
        return prettify(s).takeIf { it.length >= 2 }
    }

    private fun prettify(s: String): String {
        val words = s.replace(Regex("""[._\-]+"""), " ").trim().split(Regex("""\s+"""))
        val allCaps = s.none(Char::isLowerCase)
        return words.joinToString(" ") { w ->
            when {
                w.length <= 3 && w.all(Char::isUpperCase) -> w // HP, SBI, KFC
                allCaps || w.all(Char::isLowerCase) -> w.lowercase().replaceFirstChar(Char::uppercase)
                else -> w
            }
        }
    }
}

object Categories {
    private val RULES = listOf(
        "Food" to Regex("""swiggy|zomato|eatsure|domino|mcdonald|kfc|pizza|burger|starbucks|cafe|restaurant|biryani|chai|food|bakery|baking|juice|dunzo|hotel|tiffin|mess\b|dhaba|bhavan"""),
        "Groceries" to Regex("""blinkit|blink commerce|zepto|kiranakart|bigbasket|supermarket|super market|instamart|dmart|avenue supermarts|grofers|jiomart|more retail|kirana|fresh|natures basket|meat|chicken"""),
        "Travel" to Regex("""uber|ola|rapido|irctc|redbus|metro|makemytrip|goibibo|cleartrip|indigo|air india|vistara|akasa|fastag|parking|yatra"""),
        "Fuel" to Regex("""petrol|fuel|hpcl|\bhp\b|bpcl|iocl|indian oil|shell|bharat petroleum|filling station"""),
        "Shopping" to Regex("""amazon|flipkart|myntra|ajio|meesho|nykaa|croma|reliance digital|decathlon|ikea|tata cliq|lenskart"""),
        "Subscriptions" to Regex("""netflix|spotify|prime video|hotstar|youtube|apple|google play|google one|icloud|jio ?cinema|sonyliv|zee5|chatgpt|openai|anthropic|claude"""),
        "Bills" to Regex("""airtel|\bjio\b|\bvi\b|vodafone|bsnl|electricity|bescom|tata power|adani|mseb|gas|broadband|act fibernet|water|insurance|lic\b|postpaid|dth"""),
        "Health" to Regex("""pharma|apollo|medplus|1mg|netmeds|pharmeasy|hospital|clinic|diagnostic|lab\b|doctor"""),
        "Rent" to Regex("""\brent\b|landlord|nobroker|housing"""),
    )

    /** Money moved between your own accounts and investments: neither spending nor income. */
    const val SAVINGS = "Savings"
    private val SAVINGS_RE = Regex(
        """\b(fd|fixed deposit|rd|recurring deposit|matured|maturity|auto[_ -]?redeem|prin(cipal)? and int|mutual fund|folio|sip|nps|ppf|zerodha|groww|kuvera|smallcase|demat|sweep|td|term deposit|flexi deposit|deposit prin|dep prin)\b|\bTO For \d{9,}""",
        RegexOption.IGNORE_CASE,
    )
    const val PEOPLE = "People"
    private val P2A = Regex("""\bUPI/P2A/""", RegexOption.IGNORE_CASE)

    /** Categories the on-device model may choose from for merchants the rules don't know. */
    val ALL = listOf("Food", "Groceries", "Travel", "Fuel", "Shopping", "Subscriptions", "Bills", "Health", "Rent", "Education", "Entertainment", PEOPLE, "Other")

    private val SALARY_RE = Regex("""\bsalary\b""", RegexOption.IGNORE_CASE)

    /** Account holder name(s), learned from bank messages (see OwnerName). */
    @Volatile var ownerNames: List<String> = emptyList()

    private fun squash(s: String) = s.lowercase().filter { it.isLetter() }

    private val WORDS = Regex("""[^a-z]+""")

    /** Banks cut and reorder names: "R Kumarasw", "R KUMARA", "Kumaraswamy R" all match "R KUMARASWAMY". */
    fun isOwner(name: String?): Boolean {
        val n = squash(name ?: return false)
        if (n.length < 5) return false
        return ownerNames.any { o ->
            val q = squash(o)
            if (q.length >= 5 && (q.startsWith(n) || n.startsWith(q))) return@any true
            // Same words in any order, each possibly cut short; the longest must be a real word.
            val mine = o.lowercase().split(WORDS).filter { it.isNotEmpty() }
            val theirs = name.lowercase().split(WORDS).filter { it.isNotEmpty() }
            theirs.isNotEmpty() && theirs.maxOf { it.length } >= 4 &&
                theirs.all { t -> mine.any { m -> m.startsWith(t) || (m.length >= 4 && t.startsWith(m)) } }
        }
    }

    /** Money moved between the user's own accounts: never spending, never income. */
    const val SELF = "Self transfer"
    private val SELF_RE = Regex("""\bto a/c\s*[x*#]+\d{3,4}|\bto self\b|\bown account|\bself[- ]transfer""", RegexOption.IGNORE_CASE)

    /** Money moving around (savings, investments, own accounts), not money spent or earned. */
    fun isMovement(category: String) = category == SAVINGS || category == SELF

    /** What kind of saving or investment a Savings movement is, read from its message. */
    fun investmentType(raw: String?, merchant: String? = null): String {
        val t = raw?.lowercase().orEmpty()
        return when {
            Regex("""\bsweep\b""").containsMatchIn(t) -> "Sweep deposits"
            Regex("""\b(fd|fixed deposit|td|term deposit|flexi deposit)\b|\bto for \d{9,}""").containsMatchIn(t) -> "Fixed deposits"
            Regex("""\b(rd|recurring deposit)\b""").containsMatchIn(t) -> "Recurring deposits"
            Regex("""mutual fund|folio|\bsip\b|\bnav\b|units allotted""").containsMatchIn(t) -> "Mutual funds"
            Regex("""zerodha|groww|upstox|demat|shares|\bnse\b|\bbse\b|smallcase|angel one""").containsMatchIn(t) -> "Stocks"
            Regex("""\b(ppf|nps)\b""").containsMatchIn(t) -> "PPF / NPS"
            Regex("""matured|maturity|auto[_ -]?redeem|prin(cipal)? and int""").containsMatchIn(t) -> "Fixed deposits"
            else -> "Other savings"
        }
    }

    fun of(merchant: String?, direction: String, raw: String = ""): String {
        if (isOwner(merchant) || SELF_RE.containsMatchIn(raw)) return SELF
        if (SAVINGS_RE.containsMatchIn(raw)) return SAVINGS
        if (direction == "in") return if (SALARY_RE.containsMatchIn(raw)) "Salary" else "Received"
        merchant?.lowercase()?.let { m -> RULES.firstOrNull { it.second.containsMatchIn(m) }?.let { return it.first } }
        // UPI person-to-person payments ("P2A") go to a person, not a shop.
        if (P2A.containsMatchIn(raw)) return PEOPLE
        if (merchant == null) RULES.firstOrNull { it.first == "Bills" && it.second.containsMatchIn(raw.lowercase()) }?.let { return it.first }
        return "Other"
    }
}
