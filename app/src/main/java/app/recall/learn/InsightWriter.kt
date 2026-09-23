package app.recall.learn

import app.recall.App
import app.recall.capture.Normalizer
import app.recall.data.Repo
import app.recall.util.Fmt
import app.recall.util.Time
import java.time.format.TextStyle
import java.util.Locale

/** Turns what Recall has learned into sentences the user can read (and delete). */
object InsightWriter {
    private const val DAY = Repo.DAY
    private const val HOUR = 60 * 60 * 1000L

    /** Short "Noticed" lines about today, for the orb, Today screen and nightly digest. */
    fun noticed(now: Long = Time.now()): List<String> {
        val out = mutableListOf<String>()

        // 1. Someone has waited much longer than you usually take with them.
        for (loop in Repo.openLoops(now)) {
            val p = Repo.profile(loop.convKey) ?: continue
            val median = p.medianReplyS?.times(1000) ?: continue
            if (p.replies < 3 || median > 2 * HOUR) continue
            val age = now - loop.firstAt
            if (age > maxOf(3 * median, 45 * 60 * 1000L)) {
                out += "You usually answer ${p.display} within ${Fmt.duration(median)}. Today they've been waiting ${Fmt.duration(age)}."
            }
            if (out.size >= 2) break
        }

        // 2. An app where evening messages keep slipping.
        val weekLoops = Repo.loopsSince(now - 7 * DAY)
        weekLoops
            .filter { Time.hour(it.firstAt) >= 18 && slipped(it, now, 12 * HOUR) }
            .groupBy { it.pkg }
            .filter { it.value.size >= 3 }
            .maxByOrNull { it.value.size }
            ?.let { (pkg, loops) ->
                out += "${Fmt.ordinal(loops.size).replaceFirstChar(Char::uppercase)} time this week a ${label(pkg)} message after 6 PM went unanswered."
            }

        // 3. Spending well above the usual day.
        val (start, end) = Time.bounds(Time.today())
        val spentToday = Repo.moneyTotals(start, end).first
        val firstTxn = Repo.firstTxnAt()
        if (firstTxn != null && now - firstTxn > 7 * DAY && spentToday >= 50_000) {
            val days = ((now - maxOf(firstTxn, now - 30 * DAY)) / DAY).coerceAtLeast(7)
            val avg = Repo.moneyTotals(now - days * DAY, start).first / days
            if (avg > 0 && spentToday > 2 * avg) {
                out += "You spent %.1f× your usual daily amount today.".format(spentToday.toDouble() / avg)
            }
        }
        return out
    }

    private fun slipped(l: Repo.LoopRow, now: Long, after: Long) =
        l.state == "expired" ||
            (l.state == "open" && now - l.firstAt > after) ||
            (l.closedBy == "reply" && (l.closedAt ?: now) - l.firstAt > after)

    private fun label(pkg: String) = Normalizer.appLabel(App.ctx, pkg)

    fun refreshFacts(now: Long = Time.now()) {
        val keep = mutableSetOf<String>()
        fun fact(key: String, kind: String, text: String) {
            Repo.upsertFact(key, kind, text)
            keep += key
        }

        // People
        var people = 0
        for (p in Repo.profiles()) {
            if (people >= 8) break
            val median = p.medianReplyS?.times(1000)
            when {
                p.replies >= 3 && median != null && median <= 30 * 60 * 1000L -> {
                    val always = if (p.importance >= 0.7) " Always important." else ""
                    fact("fast:${p.personKey}", "people", "${p.display}: you usually reply within ${Fmt.duration(median)}.$always")
                    people++
                }
                p.replies >= 2 && median != null && median >= 12 * HOUR -> {
                    fact("slow:${p.personKey}", "people", "${p.display}: messages wait about ${Fmt.duration(median)} for a reply.")
                    people++
                }
            }
            if (p.misses >= 2) {
                fact("miss:${p.personKey}", "people", "${p.display}: ${p.misses} requests went unanswered this month.")
                people++
            }
        }

        // Habits
        for ((pkg, k) in Repo.signalCountsByPkg(now - 30 * DAY)) {
            val dismissed = k["dismissed"] ?: 0
            val opened = (k["opened"] ?: 0) + (k["app_cancel"] ?: 0)
            if (dismissed + opened >= 20 && dismissed.toDouble() / (dismissed + opened) >= 0.8) {
                fact("clear:$pkg", "habits", "You clear ${label(pkg)} without opening it, so it's ranked lower.")
            }
        }
        Repo.loopsSince(now - 30 * DAY)
            .filter { Time.hour(it.firstAt) >= 18 && slipped(it, now, 12 * HOUR) }
            .groupBy { it.pkg }
            .filter { it.value.size >= 4 }
            .forEach { (pkg, loops) ->
                fact("late:$pkg", "habits", "${label(pkg)} messages after 6 PM often slip (${loops.size} this month).")
            }

        // Money: this month so far vs the same stretch of last month.
        val today = Time.today()
        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        val lastMonthSameDay = lastMonthStart.plusDays((today.dayOfMonth - 1).toLong().coerceAtMost(lastMonthStart.lengthOfMonth() - 1L))
        val thisMonth = Repo.txnsBetween(Time.startOf(monthStart), Time.startOf(today.plusDays(1))).filter { it.isOut && !app.recall.understand.Categories.isMovement(it.category) }
            .groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }
        val lastMonth = Repo.txnsBetween(Time.startOf(lastMonthStart), Time.startOf(lastMonthSameDay.plusDays(1))).filter { it.isOut && !app.recall.understand.Categories.isMovement(it.category) }
            .groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }
        val lastName = lastMonthStart.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        for ((cat, amount) in thisMonth) {
            if (cat == "Other" || amount < 50_000) continue
            val before = lastMonth[cat] ?: continue
            if (before < 30_000) continue
            val change = (amount - before) * 100 / before
            if (kotlin.math.abs(change) >= 20) {
                val dir = if (change > 0) "up" else "down"
                fact("spend:$cat", "money", "$cat is ${Fmt.rupees(amount)} this month, $dir ${kotlin.math.abs(change)}% on $lastName.")
            }
        }
        if (lastMonth.isEmpty()) {
            thisMonth.filterKeys { it != "Other" }.maxByOrNull { it.value }?.takeIf { it.value >= 100_000 }?.let { (cat, amount) ->
                fact("spendtop", "money", "Most of your spending this month is on $cat (${Fmt.rupees(amount)}).")
            }
        }

        Repo.pruneFacts(keep)
    }

    /** Lines for Sunday night's "Your week" notification. */
    fun weekly(now: Long = Time.now()): List<String> {
        val out = mutableListOf<String>()
        val loops = Repo.loopsSince(now - 7 * DAY)
        val missed = loops.filter { slipped(it, now, 12 * HOUR) }.groupBy { it.convKey }
        missed.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 2 }?.let { (key, l) ->
            val name = Repo.profile(key)?.display ?: Repo.people().firstOrNull { it.first == key }?.second
            if (name != null) out += "Missed most: $name (${l.size})"
        }
        val slipApp = loops.filter { Time.hour(it.firstAt) >= 18 && slipped(it, now, 12 * HOUR) }.groupBy { it.pkg }.maxByOrNull { it.value.size }
        if (slipApp != null && slipApp.value.size >= 2) out += "${label(slipApp.key)} after 6 PM slipped ${slipApp.value.size}×"
        val week = Repo.moneyTotals(now - 7 * DAY, now).first
        val prev = Repo.moneyTotals(now - 14 * DAY, now - 7 * DAY).first
        if (week > 0) out += "Spent ${Fmt.rupees(week)} this week" + if (prev > 0) " (${Fmt.rupees(prev)} the week before)" else ""
        val closed = loops.count { it.closedBy == "reply" || it.closedBy == "done" }
        if (loops.isNotEmpty()) out += "Handled $closed of ${loops.size} requests"
        return out
    }
}
