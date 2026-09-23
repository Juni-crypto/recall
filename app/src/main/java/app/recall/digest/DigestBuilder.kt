package app.recall.digest

import app.recall.data.Digest
import app.recall.data.Message
import app.recall.data.OpenLoop
import app.recall.data.Repo
import app.recall.learn.InsightWriter
import app.recall.model.Brain
import app.recall.model.Prompts
import app.recall.understand.SenderHeuristics
import app.recall.util.Fmt
import app.recall.util.Time
import java.time.LocalDate

object DigestBuilder {

    suspend fun build(date: LocalDate, useModel: Boolean): Digest {
        val (start, end) = Time.bounds(date)
        val now = Time.now()
        val total = Repo.countMessages(start, end)
        val apps = Repo.appCounts(start, end)
        val loops = Repo.openLoops(now)
        val urgent = loops.filter { it.isUrgent }
        val waiting = loops.filterNot { it.isUrgent }
        val (spent, received) = Repo.moneyTotals(start, end)
        val noticed = InsightWriter.noticed(now)

        val lines = buildList {
            urgent.take(2).forEach { add("● " + line(it)) }
            waiting.take(3 - minOf(2, urgent.size)).forEach { add("○ " + line(it)) }
            noticed.firstOrNull()?.let { add("↺ $it") }
            if (spent > 0 || received > 0) add("₹ Spent ${Fmt.rupees(spent)} · received ${Fmt.rupees(received)}")
        }

        var summary = fallbackSummary(total, apps.size, apps.firstOrNull()?.let { it.app to it.count }, urgent.size, waiting.size)
        var model: String? = null
        var modelMs: Long? = null
        if (useModel && total > 0 && Brain.available()) {
            val t0 = System.currentTimeMillis()
            val out = Brain.generate(
                system = Prompts.DIGEST_SYSTEM,
                user = prompt(date, start, end, total, apps.size, urgent, waiting, spent, received),
                maxTokens = 160,
                temperature = 0.5f,
            )
            if (!out.isNullOrBlank()) {
                summary = out.replace("\"", "").lines().joinToString(" ").trim()
                model = Brain.activeName()
                modelMs = System.currentTimeMillis() - t0
            }
        }

        val digest = Digest(
            date = Time.key(date), createdAt = now, summary = summary, noticed = noticed,
            urgent = urgent.size, waiting = waiting.size, spentPaise = spent, receivedPaise = received,
            total = total, apps = apps.take(12).map { it.app to it.count }, lines = lines, model = model,
        )
        Repo.saveDigest(digest, modelMs)
        return digest
    }

    fun line(l: OpenLoop): String {
        val ask = l.askText.replace('\n', ' ').let { if (it.length > 70) it.take(68).trimEnd() + "…" else it }
        val times = if (l.asks > 1 && !l.isCall) " · asked ${l.asks}×" else ""
        return "${l.person} (${l.app}): $ask$times"
    }

    private fun fallbackSummary(total: Int, appCount: Int, busiest: Pair<String, Int>?, urgent: Int, waiting: Int): String {
        if (total == 0) return "A quiet day so far. Nothing has come in yet."
        val needs = when {
            urgent > 0 && waiting > 0 -> "$urgent urgent and $waiting more waiting on you."
            urgent > 0 -> "$urgent urgent thing${if (urgent > 1) "s" else ""} need${if (urgent == 1) "s" else ""} you."
            waiting > 0 -> "$waiting ${if (waiting == 1) "person is" else "people are"} waiting on you."
            else -> "Nobody is waiting on you."
        }
        val busy = busiest?.let { " Busiest: ${it.first} (${it.second})." } ?: ""
        return "$needs $total notifications from $appCount apps.$busy"
    }

    private fun prompt(
        date: LocalDate, start: Long, end: Long, total: Int, appCount: Int,
        urgent: List<OpenLoop>, waiting: List<OpenLoop>, spent: Long, received: Long,
    ): String = buildString {
        appendLine("Date: ${Time.longDate(date)}.")
        appendLine("Figures (already computed): $total notifications from $appCount apps. Spent ${Fmt.rupees(spent)}. Received ${Fmt.rupees(received)}.")
        appendLine()
        if (urgent.isEmpty() && waiting.isEmpty()) {
            appendLine("Nobody is waiting on the user.")
        } else {
            appendLine("Waiting on the user — each of these people wants the user to reply or act (most important first):")
            (urgent + waiting).take(6).forEach { l ->
                val tag = if (l.isUrgent) "[urgent] " else ""
                appendLine("- $tag${line(l)} (since ${Time.clock(l.firstAt)})")
            }
        }
        val facts = Repo.facts().take(5)
        if (facts.isNotEmpty()) {
            appendLine()
            appendLine("What Recall knows about the user:")
            facts.forEach { appendLine("- ${it.text}") }
        }
        appendLine()
        appendLine("Today's conversations (busiest first):")
        append(conversations(Repo.messagesBetween(start, end, includeSelf = false), 5500))
        appendLine()
        append("Write the summary now.")
    }

    /** Compresses a day of messages into one or two lines per conversation. */
    fun conversations(messages: List<Message>, budget: Int): String {
        val groups = messages
            .filterNot { SenderHeuristics.isAutomated(it.sender, it.text) }
            .groupBy { it.convKey }
            .values
            .sortedByDescending { it.size }
        val sb = StringBuilder()
        for (g in groups) {
            val first = g.first()
            val title = first.convTitle ?: first.sender ?: first.app
            val group = if (first.isGroup) " (group)" else ""
            val samples = g.takeLast(3).joinToString(" | ") { m ->
                val who = if (first.isGroup && m.sender != null) "${m.sender}: " else ""
                who + m.text.replace('\n', ' ').take(90)
            }
            val line = "- ${first.app} · $title$group, ${g.size} msg: $samples\n"
            if (sb.length + line.length > budget) break
            sb.append(line)
        }
        return sb.toString()
    }
}
