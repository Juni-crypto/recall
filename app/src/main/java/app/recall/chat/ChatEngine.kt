package app.recall.chat

import app.recall.data.Message
import app.recall.data.Repo
import app.recall.model.Brain
import app.recall.model.Prompts
import app.recall.util.Fmt
import app.recall.util.Time

/** Answers questions about the user's notifications, entirely on the phone. */
object ChatEngine {

    data class Answer(val text: String, val cites: List<String>)

    suspend fun ask(question: String, onPartial: (String) -> Unit): Answer {
        Repo.addChat("user", question)
        val today = Time.today()
        val q = QueryParser.parse(question, today, Repo.people(), Repo.appLabels(), Repo.merchants())
        val start = Time.startOf(q.range.start)
        val end = Time.startOf(q.range.endExclusive)

        // --- money: computed here, never by the model
        val moneyLines = mutableListOf<String>()
        if (q.money) {
            val (spent, received) = Repo.moneyTotals(start, end)
            val txns = Repo.txnsBetween(start, end)
            moneyLines += "Total spent ${q.range.label}: ${Fmt.rupees(spent)} across ${txns.count { it.isOut }} payments."
            moneyLines += "Total received ${q.range.label}: ${Fmt.rupees(received)}."
            q.merchantTerm?.let { term ->
                val matches = Repo.spentMatching(term, start, end)
                if (matches.isNotEmpty()) {
                    val biggest = matches.maxBy { it.amountPaise }
                    moneyLines += "Spent on \"$term\" ${q.range.label}: ${Fmt.rupees(matches.sumOf { it.amountPaise })} " +
                        "across ${matches.size} payments. Biggest: ${Fmt.rupees(biggest.amountPaise)} on ${Time.shortDate(Time.date(biggest.at))}."
                } else {
                    moneyLines += "No payments matching \"$term\" ${q.range.label}."
                }
            }
            val byCat = txns.filter { it.isOut && !app.recall.understand.Categories.isMovement(it.category) }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }
                .entries.sortedByDescending { it.value }.take(5)
            if (byCat.isNotEmpty()) moneyLines += "By category: " + byCat.joinToString("; ") { "${it.key} ${Fmt.rupees(it.value)}" }
            txns.take(8).forEach { t ->
                moneyLines += "${Time.relative(t.at)}: ${if (t.isOut) "paid" else "received"} ${Fmt.rupees(t.amountPaise)}" +
                    (t.merchant?.let { if (t.isOut) " to $it" else " from $it" } ?: "")
            }
        }

        // --- messages
        val messages: List<Message> = when {
            q.people.isNotEmpty() -> Repo.messagesForConvs(q.people, start, end, 40)
            q.money && q.keywords.isEmpty() -> emptyList()
            else -> {
                val fts = QueryParser.ftsQuery(q.keywords)
                val found = fts?.let { Repo.search(it, start, end, 40) }.orEmpty()
                val filtered = if (q.pkgs.isNotEmpty()) found.filter { it.pkg in q.pkgs } else found
                when {
                    filtered.isNotEmpty() -> filtered.sortedBy { it.at }
                    q.pkgs.isNotEmpty() -> Repo.recentMessages(300, start, end).filter { it.pkg in q.pkgs }.take(40).sortedBy { it.at }
                    fts == null && !q.waiting -> Repo.recentMessages(30, start, end).sortedBy { it.at }
                    else -> emptyList()
                }
            }
        }

        val loops = Repo.openLoops().let { all ->
            if (q.people.isNotEmpty()) all.filter { it.convKey in q.people } else if (q.waiting) all else all.take(3)
        }
        val profiles = if (q.people.isNotEmpty()) q.people.mapNotNull { Repo.profile(it) } else emptyList()
        val facts = Repo.facts()

        val cites = messages.takeLast(4).map { "${it.app} · ${Time.relative(it.at)}" }.distinct()
        val context = buildString {
            if (messages.isNotEmpty()) {
                appendLine("Notification records (${q.range.label}):")
                messages.forEach { m -> appendLine(record(m)) }
                appendLine()
            }
            if (loops.isNotEmpty()) {
                appendLine("Still waiting on the user:")
                loops.take(8).forEach { l ->
                    appendLine("- ${l.person} (${l.app}), since ${Time.relative(l.firstAt)}: ${l.askText.take(140)}")
                }
                appendLine()
            }
            if (moneyLines.isNotEmpty()) {
                appendLine("Money figures (exact):")
                moneyLines.forEach { appendLine("- $it") }
                appendLine()
            }
            if (profiles.isNotEmpty() || facts.isNotEmpty()) {
                appendLine("What Recall has learned:")
                profiles.forEach { p ->
                    p.medianReplyS?.let { appendLine("- You usually reply to ${p.display} within ${Fmt.duration(it * 1000)}.") }
                }
                facts.take(8).forEach { appendLine("- ${it.text}") }
                appendLine()
            }
            val history = Repo.chat(6).dropLast(1)
            if (history.isNotEmpty()) {
                appendLine("Earlier in this chat:")
                history.forEach { appendLine("${if (it.role == "user") "User" else "Recall"}: ${it.text.take(200)}") }
                appendLine()
            }
            appendLine("Today is ${Time.longDate(today)}, ${Time.clock(Time.now())}.")
        }

        val modelAnswer = if (Brain.available()) {
            Brain.generate(
                system = Prompts.CHAT_SYSTEM,
                user = "Question: $question\n\n$context\nAnswer the question.",
                maxTokens = 220,
                temperature = 0.3f,
                load = app.recall.model.Safety.Load.Interactive,
                onText = onPartial,
            )
        } else {
            null
        }

        val text = modelAnswer?.takeIf { it.isNotBlank() } ?: fallback(q, messages, moneyLines, loops.map { "${it.person} (${it.app}): ${it.askText.take(100)}" })
        Repo.addChat("assistant", text, cites)
        return Answer(text, cites)
    }

    private fun record(m: Message): String {
        val who = when {
            m.isSelf -> "you → ${m.convTitle ?: "them"}"
            m.isGroup -> "${m.sender ?: "someone"} in ${m.convTitle}"
            else -> m.sender ?: m.convTitle ?: m.app
        }
        return "[${Time.relative(m.at)} · ${m.app} · $who] ${m.text.replace('\n', ' ').take(220)}"
    }

    /** Without a model, answer with the facts themselves. */
    private fun fallback(q: QueryParser.Query, messages: List<Message>, money: List<String>, loops: List<String>): String = buildString {
        if (money.isNotEmpty()) {
            append(money.take(3).joinToString("\n"))
            return@buildString
        }
        if (q.waiting && loops.isNotEmpty()) {
            append("Waiting on you:\n")
            append(loops.take(5).joinToString("\n") { "• $it" })
            return@buildString
        }
        if (messages.isEmpty()) {
            append("I couldn't find anything about that in your notifications.")
            return@buildString
        }
        append("Here's what I found:\n")
        append(messages.takeLast(5).joinToString("\n") { m ->
            "• ${Time.relative(m.at)}, ${m.sender ?: m.app}: ${m.text.replace('\n', ' ').take(120)}"
        })
        append("\n\nDownload a model for full answers.")
    }
}
