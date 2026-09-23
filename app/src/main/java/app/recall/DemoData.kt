package app.recall

import app.recall.data.Captured
import app.recall.data.Digest
import app.recall.data.Kind
import app.recall.data.Profile
import app.recall.data.Repo
import app.recall.util.Time

/**
 * Made-up data for screenshots and demos. Debug builds only (see MainActivity):
 * `adb shell am start -n app.recall.offline/app.recall.ui.MainActivity --ez seed_demo true`
 */
object DemoData {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = Repo.DAY

    fun seed() {
        App.db.wipe()
        val db = App.db.w
        db.beginTransaction()
        try {
            fill()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        App.db.changed()
    }

    private fun fill() {
        App.prefs.onboarded = true
        App.prefs.demo = true
        App.prefs.ownerName = "R KUMAR"
        App.prefs.moneyParserVersion = app.recall.understand.MoneyParser.VERSION
        val now = Time.now()
        val today = Time.startOf(Time.today())
        fun at(h: Int, m: Int = 0) = today + h * HOUR + m * MIN
        var n = 0

        fun msg(pkg: String, app: String, conv: String, sender: String, text: String, time: Long, kind: String = Kind.CHAT, self: Boolean = false, group: Boolean = false): Long? =
            Repo.insertMessage(
                Captured("demo-${n++}", pkg, app, "$pkg|$conv", conv, if (self) "You" else sender, text, self, group, kind, null, time, null, false),
            )

        // A day of notifications
        val chatter = listOf(
            Triple("com.Slack", "Slack", "#eng-release"), Triple("com.whatsapp", "WhatsApp", "Family"),
            Triple("com.whatsapp", "WhatsApp", "College gang"), Triple("com.google.android.gm", "Gmail", "Newsletters"),
            Triple("com.instagram.android", "Instagram", "Instagram"), Triple("in.swiggy.android", "Swiggy", "Swiggy"),
        )
        repeat(64) { i ->
            val (pkg, app, conv) = chatter[i % chatter.size]
            msg(pkg, app, conv, listOf("Neha", "Vikram", "Asha", "Dev")[i % 4], listOf("Build 412 is green", "Lunch?", "Sent the photos", "Weekly digest", "New follower", "Order picked up")[i % 6], at(8 + i % 13, (i * 7) % 60), group = true)
        }

        // People waiting on you
        val arjun = msg("com.Slack", "Slack", "Arjun Mehta", "Arjun Mehta", "hey can u approve this pr quick? blocking the release", at(15, 10))
        msg("com.Slack", "Slack", "Arjun Mehta", "Arjun Mehta", "??", at(17, 2))
        val priya = msg("com.whatsapp", "WhatsApp", "Priya S", "Priya S", "can you send the invoice by today? 🙏", at(11, 2))
        val karthik = msg("com.google.android.gm", "Gmail", "Karthik R", "Karthik R", "Following up on the contract review", now - 2 * DAY, Kind.EMAIL)
        msg("com.whatsapp", "WhatsApp", "Rahul K", "Rahul K", "free on saturday for the site visit?", now - DAY - 2 * HOUR)
        msg("com.whatsapp", "WhatsApp", "Amma", "Amma", "Call me when free", at(18, 40))

        fun loop(conv: String, pkg: String, app: String, ask: String, msgId: Long?, first: Long, urgency: Int, due: String?, asks: Int = 1) {
            val id = Repo.insertLoop("$pkg|$conv", pkg, app, conv, ask, msgId, first, urgency, listOf("Qwen"), due, false, DoubleArray(12))
            Repo.markLoopAi(id)
            if (asks > 1) Repo.updateLoopFromModel(id, ask, urgency, due)
        }
        loop("Arjun Mehta", "com.Slack", "Slack", "Approve PR #212 before the release", arjun, at(15, 10), 92, "before release")
        loop("Priya S", "com.whatsapp", "WhatsApp", "Send the invoice", priya, at(11, 2), 58, "today")
        loop("Karthik R", "com.google.android.gm", "Gmail", "Review the contract", karthik, now - 2 * DAY, 45, null)

        // Money: three months of it
        val days = 95
        for (d in 0 until days) {
            val base = Time.startOf(Time.today().minusDays(d.toLong()))
            val dom = Time.today().minusDays(d.toLong()).dayOfMonth
            fun txn(amount: Double, dir: String, merchant: String?, cat: String, src: String, h: Int, raw: String = "") =
                Repo.insertOrMergeTxn(Math.round(amount * 100), dir, merchant, cat, "1234", src, "android.sms", -1, raw, base + h * HOUR + (d % 50) * MIN)
            if (dom == 1) txn(120000.0, "in", "Acme Labs", "Salary", "HDFCBK SMS", 9, "salary-ACME LABS")
            if (dom == 5) txn(28000.0, "out", "Green Homes", "Rent", "HDFCBK SMS", 10)
            if (dom == 7) txn(10000.0, "out", "Nippon India Mutual Fund", "Savings", "HDFCBK SMS", 9, "SIP mutual fund folio")
            if (dom == 12) txn(649.0, "out", "Netflix", "Subscriptions", "ICICI card", 20)
            if (dom == 15) txn(599.0, "out", "Airtel", "Bills", "GPay", 12)
            if (dom == 20) txn(20000.0, "out", "R Kumar", "Self transfer", "HDFCBK SMS", 11)
            if (d % 2 == 0) txn(180.0 + (d * 37) % 420, "out", listOf("Swiggy", "Zomato", "Third Wave Coffee")[d % 3], "Food", "GPay + SMS", 13)
            if (d % 4 == 1) txn(300.0 + (d * 53) % 700, "out", "Blinkit", "Groceries", "PhonePe", 19)
            if (d % 6 == 2) txn(150.0 + (d * 11) % 250, "out", "Uber", "Travel", "GPay", 9)
            if (d % 9 == 3) txn(2000.0, "out", "HP Petrol Pump", "Fuel", "HDFCBK SMS", 8)
            if (d % 11 == 4) txn(799.0 + (d * 97) % 2500, "out", "Amazon", "Shopping", "ICICI card", 21)
            if (d % 17 == 5) txn(1200.0, "in", "Rahul K", "Received", "GPay", 18)
            if (d == 40) txn(50000.0, "out", null, "Savings", "HDFCBK SMS", 10, "FD through MOBILE")
        }

        // What it learned
        Repo.upsertProfile(Profile("com.whatsapp|Amma", "Amma", "com.whatsapp", 120, 96, 8 * 60, 0, 0.94))
        Repo.upsertProfile(Profile("com.Slack|Arjun Mehta", "Arjun Mehta", "com.Slack", 64, 40, 25 * 60, 4, 0.62))
        Repo.upsertProfile(Profile("com.google.android.gm|Karthik R", "Karthik R", "com.google.android.gm", 18, 6, 2 * 86400, 3, 0.35))
        Repo.upsertFact("fast:amma", "people", "Amma: you usually reply within 8 min. Always important.")
        Repo.upsertFact("late:slack", "habits", "Slack messages after 6 PM often slip (4 this month).")
        Repo.upsertFact("slow:karthik", "people", "Karthik R: emails wait about 2 days for a reply.")
        Repo.upsertFact("clear:insta", "habits", "You clear Instagram without opening it, so it's ranked lower.")
        Repo.upsertFact("spend:food", "money", "Food is ₹6,420 this month, up 18% on last month.")
        Repo.setMerchantCategory("Third Wave Coffee", "Food", "model")
        Repo.setMerchantCategory("Green Homes", "Rent", "user")

        val (start, end) = Time.bounds(Time.today())
        val (spent, received) = Repo.moneyTotals(start, end)
        Repo.saveDigest(
            Digest(
                Time.key(Time.today()), now,
                "Arjun is waiting for you to approve PR #212 before the release, and Priya needs the invoice today. Most of the day was the release thread on Slack.",
                listOf("3rd time this week a Slack message after 6 PM went unanswered."), 1, 2, spent, received, 70,
                listOf("Slack" to 18, "WhatsApp" to 24, "Gmail" to 11), emptyList(), "Qwen3 4B Instruct",
            ),
            41_000,
        )
        Repo.addChat("user", "what did rahul want yesterday?")
        Repo.addChat(
            "assistant",
            "Yesterday on WhatsApp Rahul asked if you're free Saturday for the site visit. You haven't replied yet.",
            listOf("WhatsApp · Yesterday ${Time.clock(now - DAY - 2 * HOUR)}"),
        )
        Repo.addChat("user", "how much on food this month")
        Repo.addChat("assistant", "₹6,420 across 16 payments, mostly Swiggy. That's up 18% on last month.", listOf("16 payments"))
    }
}
