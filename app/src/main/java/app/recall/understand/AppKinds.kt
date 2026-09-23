package app.recall.understand

/** Known app packages, used to tell chats from payments from calls. */
object AppKinds {
    val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
        "com.Slack", "com.microsoft.teams", "com.discord", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.instagram.android", "com.google.android.apps.dynamite",
        "com.snapchat.android", "com.linkedin.android", "im.vector.app", "com.viber.voip",
        "jp.naver.line.android", "com.skype.raider", "us.zoom.videomeetings",
    )

    val EMAIL = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android",
        "com.yahoo.mobile.client.android.mail", "com.readdle.spark", "com.fsck.k9", "com.samsung.android.email.provider",
    )

    val DIALER = setOf(
        "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom", "com.samsung.android.dialer",
        "com.android.incallui", "com.oplus.dialer", "com.oneplus.dialer", "com.android.contacts", "com.truecaller",
    )

    /** Apps where a channel notification only fires when you're mentioned. */
    val MENTION_ONLY = setOf("com.Slack", "com.microsoft.teams", "com.google.android.apps.dynamite", "com.discord")

    val WORK = setOf(
        "com.Slack", "com.microsoft.teams", "com.google.android.apps.dynamite", "com.google.android.gm",
        "com.microsoft.office.outlook", "com.github.android", "com.atlassian.android.jira.core",
        "com.linear", "us.zoom.videomeetings", "com.notion.id", "com.asana.app", "com.trello",
    )

    val PAYMENT = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.dreamplug.androidapp", "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.freecharge.android",
        "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.sbi.lotusintouch", "com.axis.mobile",
        "com.msf.kbank.mobile", "com.fss.indus", "com.bankofbaroda.mconnect", "com.infrasofttech.indianBank",
        "com.idfcfirstbank.optimus", "com.fi.money", "com.jupiter.money", "com.slice", "money.super.payments",
        "com.samsung.android.spay", "com.mipay.wallet.in",
    )

    fun isSms(pkg: String) =
        pkg == "com.google.android.apps.messaging" || pkg.endsWith(".mms") || pkg.contains("messaging") || pkg == "com.android.mms"

    fun isChat(pkg: String) = pkg in CHAT
    fun isEmail(pkg: String) = pkg in EMAIL
    fun isDialer(pkg: String) = pkg in DIALER || pkg.contains("dialer") || pkg.contains("incallui")
    fun isWork(pkg: String) = pkg in WORK
}

/** Bank shortcodes, no-reply senders and promo channels. */
object SenderHeuristics {
    // Indian SMS headers look like "AX-HDFCBK", "VM-SWIGGY-S", "JD-ICICIT-T".
    private val SMS_HEADER = Regex("""^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$""")
    private val SHORT_CODE = Regex("""^\d{3,6}$""")
    private val ALL_CAPS_ID = Regex("""^[A-Z0-9]{5,11}$""")
    private val NO_REPLY = Regex("""no-?reply|donotreply|notifications?@|alerts?@|mailer|newsletter""", RegexOption.IGNORE_CASE)
    private val PROMO = Regex(
        """\b(offer|sale|% off|flat \d+|coupon|cashback|deal of|limited time|shop now|buy now|use code|free delivery|win |congratulations|reward points|t&c)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OTP = Regex("""\b(otp|one[- ]time password|verification code|security code|login code)\b""", RegexOption.IGNORE_CASE)

    fun isAutomated(sender: String?, text: String): Boolean {
        val s = sender?.trim().orEmpty()
        if (SMS_HEADER.matches(s) || SHORT_CODE.matches(s) || ALL_CAPS_ID.matches(s)) return true
        if (NO_REPLY.containsMatchIn(s)) return true
        return OTP.containsMatchIn(text)
    }

    fun isPromo(text: String) = PROMO.containsMatchIn(text)

    // System mail: "[Cosmos] Warehouse build finished", "3 alert(s) from …", CI and deploy notices.
    private val SYSTEM_MAIL = Regex(
        """^\s*\[[^\]]{2,40}]|\balert\(s\)|\b(build|deploy(ment)?|pipeline|job|run|workflow) (finished|failed|succeeded|passed|completed)\b|\b(weekly|daily|monthly) (report|digest|summary)\b|\bdo not reply\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isSystemMail(text: String) = SYSTEM_MAIL.containsMatchIn(text)

    // An automated mail saying something broke or needs action: worth the model's look.
    private val PROBLEM = Regex(
        """\b(fail(ed|ing|ure|s)?|errors?|down|outage|crash(ed|ing)?|critical|incident|suspend(ed|ing)?|suspension|action required|immediate action|breach|expir(ed|es|ing)|overdue|blocked|rejected|declined|denied|unhealthy|timed? ?out)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isProblem(text: String) = PROBLEM.containsMatchIn(text)
}
