package app.recall.learn

import app.recall.data.Kind
import app.recall.data.Message
import app.recall.data.Repo
import app.recall.model.Brain
import app.recall.model.Safety
import app.recall.understand.AppKinds
import app.recall.understand.Categories
import app.recall.understand.MoneyParser
import app.recall.understand.SenderHeuristics
import app.recall.understand.Understand
import app.recall.understand.UrgencyScorer
import app.recall.util.Time

/**
 * The on-device model reads what people sent you, with the conversation around it, and
 * decides what actually needs you. The rules react instantly; this pass is the judgement.
 *
 * It also rescues bank alerts the rules couldn't parse. Any amount it returns must appear
 * verbatim in the message, so it can't invent money.
 */
object Triage {
    private const val BATCH = 10
    private const val WINDOW = 3 * Repo.DAY

    private val TRIAGE_SYSTEM = """
        You read messages sent to the user and decide which ones need the user.
        Messages can be in English, Tamil, Hindi, Kannada, Telugu or mixed (Tanglish, Hinglish).
        Each numbered item shows the latest message and a few earlier lines of that conversation.
        Lines starting "You:" were written by the user; if the user already answered, it doesn't need them.

        NEED = the sender wants the user to do, answer, decide, send, pay, call or approve something.
        FYI = news, thanks, jokes, forwards, greetings, automated alerts, or already answered.

        Urgency: 0 whenever, 1 soon, 2 today, 3 right now (emergency, blocking, repeated pings).

        Reply with exactly one line per item and nothing else:
        <number> | NEED or FYI | <urgency 0-3> | <deadline in 1-3 words, or -> | <what they want, max 8 words, in English>
        Example: 2 | NEED | 3 | before release | Approve PR #212
    """.trimIndent()

    private val TRIAGE_LINE = Regex("""^\s*(\d+)\s*[|:.)-]\s*(NEED|FYI)\s*\|\s*([0-3])\s*\|\s*([^|]*)\|\s*(.*)$""", RegexOption.IGNORE_CASE)

    private val MONEY_SYSTEM = """
        You read bank, card and UPI messages and extract the money movement for the account holder.
        For each numbered message reply with exactly one line and nothing else:
        <number> | DEBIT or CREDIT or NONE | <amount as digits, e.g. 1299.00> | <who was paid or who paid, or ->
        Use NONE for OTPs, reminders, offers, bills due, requests and failed or future payments.
    """.trimIndent()

    private val MONEY_LINE = Regex("""^\s*(\d+)\s*[|:.)-]\s*(DEBIT|CREDIT|NONE)\s*\|\s*([0-9.,]+|-)\s*\|\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val MONEY_HINT = Regex("""(₹|\brs\.?|\binr)\s*[0-9]""", RegexOption.IGNORE_CASE)
    private val MOVE_HINT = Regex("""\b(debit|credit|spent|paid|sent|received|withdraw|deposit|transfer|purchase|txn)""", RegexOption.IGNORE_CASE)

    data class Report(val reviewed: Int, val needs: Int, val dismissed: Int, val moneyFound: Int, val ms: Long)

    suspend fun run(maxBatches: Int = 6): Report {
        val t0 = System.currentTimeMillis()
        if (!Brain.available()) return Report(0, 0, 0, 0, 0)
        var reviewed = 0; var needs = 0; var dismissed = 0; var money = 0
        for (i in 0 until maxBatches) {
            if (!Safety.check(Safety.Load.Light).ok) break
            if (i > 0 && !Safety.power().charging) kotlinx.coroutines.delay(5_000)
            val r = triageBatch() ?: break
            reviewed += r.first; needs += r.second; dismissed += r.third
        }
        repeat(2) { if (Safety.check(Safety.Load.Light).ok) money += moneyBatch() }
        return Report(reviewed, needs, dismissed, money, System.currentTimeMillis() - t0)
    }

    // ------------------------------------------------------------------ requests

    private fun fromPerson(m: Message): Boolean {
        if (SenderHeuristics.isAutomated(m.sender, m.text) || SenderHeuristics.isPromo(m.text)) return false
        if (m.kind == Kind.EMAIL && SenderHeuristics.isSystemMail(m.text)) return false
        return !m.isGroup || m.pkg in AppKinds.MENTION_ONLY || Understand.mentionsUser(m.text)
    }

    /** Returns (reviewed, needs, dismissed), or null when nothing is left. */
    private suspend fun triageBatch(): Triple<Int, Int, Int>? {
        val now = Time.now()
        val pool = Repo.triageCandidates(now - WINDOW, 60)
        if (pool.isEmpty()) return null
        val (people, skip) = pool.partition(::fromPerson)
        Repo.markTriaged(skip.map { it.id }, 2)
        // One item per conversation: its latest unreviewed message, with context.
        val latest = people.groupBy { it.convKey }.values.map { it.last() }.take(BATCH)
        if (latest.isEmpty()) return if (skip.isEmpty()) null else Triple(0, 0, 0)

        val user = buildString {
            latest.forEachIndexed { i, m ->
                val ctx = Repo.messagesForConv(m.convKey, now - 2 * Repo.DAY).takeLast(6)
                val title = if (m.isGroup) "${m.convTitle} (group)" else (m.convTitle ?: m.sender ?: m.app)
                appendLine("${i + 1}. ${m.app} · $title")
                ctx.forEach { c ->
                    val who = if (c.isSelf) "You" else (c.sender ?: title)
                    appendLine("   $who: ${c.text.replace('\n', ' ').take(160)}")
                }
            }
        }
        val out = Brain.generate(TRIAGE_SYSTEM, user, maxTokens = 26 * latest.size + 30, temperature = 0.1f)
            ?: return null
        val answers = out.lines().mapNotNull { TRIAGE_LINE.find(it) }.associateBy { it.groupValues[1].toInt() - 1 }

        var needs = 0; var dismissed = 0
        latest.forEachIndexed { i, m ->
            val a = answers[i] ?: return@forEachIndexed
            val need = a.groupValues[2].equals("NEED", true)
            val urgency = a.groupValues[3].toInt()
            val due = a.groupValues[4].trim().takeIf { it.isNotEmpty() && it != "-" }
            val ask = a.groupValues[5].trim().trimEnd('.').ifEmpty { m.text.take(80) }
            val existing = Repo.openLoopForConv(m.convKey)
            if (need) {
                needs++
                val modelScore = intArrayOf(20, 45, 72, 92)[urgency]
                val score = if (existing != null && existing.asks >= 2) maxOf(modelScore, existing.urgency) else modelScore
                if (existing != null) {
                    Repo.updateLoopFromModel(existing.id, ask, score, due)
                } else {
                    val person = if (m.isGroup && m.convTitle != null && m.sender != null) "${m.sender} · ${m.convTitle}" else (m.convTitle ?: m.sender ?: m.app)
                    val features = UrgencyScorer.score(
                        UrgencyScorer.Input(m.text, 1, Repo.profile(m.convKey)?.importance ?: 0.3, m.text.contains('?'), m.isGroup,
                            m.kind == Kind.EMAIL, false, Time.hour(m.at) >= 18, Learner.appOpenRate(m.pkg), false),
                    ).features
                    val id = Repo.insertLoop(m.convKey, m.pkg, m.app, person, ask, m.id, m.at, score, listOf("Qwen"), due, false, features)
                    Repo.markLoopAi(id)
                }
            } else if (existing != null && !existing.isCall && existing.asks <= 1) {
                // The rules flagged it; the model, seeing the conversation, says it doesn't need you.
                Repo.setLoopState(existing.id, "dismissed", "model_fyi", label = null)
                dismissed++
            }
        }
        // Everything in these conversations up to now has been reviewed.
        Repo.markTriaged(people.filter { p -> latest.any { it.convKey == p.convKey } }.map { it.id }, 1)
        return Triple(latest.size, needs, dismissed)
    }

    // ------------------------------------------------------------------ money

    private suspend fun moneyBatch(): Int {
        val now = Time.now()
        val pool = Repo.moneyCandidates(now - WINDOW, 80)
        if (pool.isEmpty()) return 0
        val (maybe, not) = pool.partition { MONEY_HINT.containsMatchIn(it.text) && MOVE_HINT.containsMatchIn(it.text) && !SenderHeuristics.isPromo(it.text) }
        Repo.markMoneyChecked(not.map { it.id })
        val batch = maybe.take(BATCH)
        if (batch.isEmpty()) return 0
        val user = batch.mapIndexed { i, m -> "${i + 1}. ${m.text.replace('\n', ' ').take(260)}" }.joinToString("\n")
        val out = Brain.generate(MONEY_SYSTEM, user, maxTokens = 22 * batch.size + 20, temperature = 0.0f)
        Repo.markMoneyChecked(batch.map { it.id })
        if (out == null) return 0
        var found = 0
        for (line in out.lines()) {
            val a = MONEY_LINE.find(line) ?: continue
            val m = batch.getOrNull(a.groupValues[1].toInt() - 1) ?: continue
            val dir = when (a.groupValues[2].uppercase()) { "DEBIT" -> "out"; "CREDIT" -> "in"; else -> continue }
            val amountText = a.groupValues[3].replace(",", "")
            val rupees = amountText.toDoubleOrNull() ?: continue
            // Guard: the digits must literally be in the message, so the model can't invent an amount.
            val digitsInText = m.text.replace(",", "")
            val whole = amountText.substringBefore('.')
            if (rupees <= 0 || !digitsInText.contains(whole)) continue
            val merchant = a.groupValues[4].trim().takeIf { it.isNotEmpty() && it != "-" }?.take(40)
            val saved = merchant?.let { Repo.merchantCategory(it) }
            val ruled = Categories.of(merchant, dir, m.text)
            val category = when {
                saved?.second == "user" -> saved.first
                ruled != "Other" -> ruled
                else -> saved?.first ?: ruled
            }
            val source = if (m.kind == Kind.SMS) (m.sender ?: m.app) + " SMS" else m.app
            Repo.insertOrMergeTxn(Math.round(rupees * 100), dir, merchant, category, null, source, m.pkg, m.id, m.text, m.at)
            found++
        }
        if (found > 0) Repo.pairSelfTransfers()
        return found
    }

    @Suppress("unused")
    private fun parserMissed(m: Message) = MoneyParser.parse(m.text) == null
}
