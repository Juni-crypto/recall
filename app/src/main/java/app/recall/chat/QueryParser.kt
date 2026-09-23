package app.recall.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pulls a time range, people, apps and intent out of a plain-language question. */
object QueryParser {
    data class Range(val start: LocalDate, val endExclusive: LocalDate, val label: String)

    data class Query(
        val range: Range,
        val rangeExplicit: Boolean,
        val money: Boolean,
        val waiting: Boolean,
        val people: List<String>,      // conv keys
        val peopleNames: List<String>,
        val pkgs: List<String>,
        val merchantTerm: String?,
        val keywords: List<String>,
    )

    private val MONEY = Regex("""\b(spen[dt]|spending|paid|pay|payments?|money|expenses?|₹|rs|rupees|cost|bought|buy|received|credited|debited|income|transactions?|how much)\b""")
    private val WAITING = Regex("""\b(waiting|pending|reply|replied|respond|responded|owe|missed|miss|unanswered|forgot|haven'?t)\b""")
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "from", "with", "about", "what", "who",
        "whom", "when", "where", "why", "how", "did", "do", "does", "is", "are", "was", "were", "be", "been", "i", "me",
        "my", "mine", "you", "your", "we", "us", "our", "it", "this", "that", "these", "those", "there", "any", "anything",
        "someone", "somebody", "today", "yesterday", "week", "month", "last", "this", "tonight", "morning", "evening",
        "want", "wanted", "say", "said", "tell", "told", "send", "sent", "message", "messages", "much", "many", "spend",
        "spent", "money", "have", "has", "had", "can", "could", "should", "would", "will", "get", "got", "show", "list",
        "all", "give", "recall", "please", "pls", "ask", "asked", "keep", "missing", "miss", "waiting", "reply", "days",
        "day", "ago", "lately", "recently", "just", "still", "yet", "not", "no", "yes", "up", "out", "so", "if", "then",
    )

    fun parse(
        question: String,
        today: LocalDate,
        people: List<Pair<String, String>>,
        apps: List<Pair<String, String>>,
        merchants: List<String>,
    ): Query {
        val q = question.lowercase()
        val money = MONEY.containsMatchIn(q)
        val (range, explicit) = range(q, today, money)

        val words = Regex("""[\p{L}\p{N}']+""").findAll(q).map { it.value }.toList()
        val wordSet = words.toSet()

        val matchedPeople = people.filter { (_, title) ->
            val parts = title.lowercase().split(Regex("""[\s·,]+""")).filter { it.length >= 3 }
            parts.any { it in wordSet }
        }
        val matchedApps = apps.filter { (_, label) -> label.lowercase() in wordSet || label.lowercase().replace(" ", "") in wordSet }
            .map { it.first }

        val merchant = merchants.firstOrNull { m ->
            m.lowercase().split(' ').filter { it.length >= 3 }.any { it in wordSet }
        } ?: Regex("""\b(?:on|at|for|to)\s+([\p{L}]{3,})""").find(q)?.groupValues?.get(1)?.takeIf { money && it !in STOP }

        val nameWords = matchedPeople.flatMap { it.second.lowercase().split(Regex("""\s+""")) }.toSet()
        val keywords = words.filter { it.length >= 3 && it !in STOP && it !in nameWords && !it.all(Char::isDigit) }.distinct().take(6)

        return Query(
            range = range, rangeExplicit = explicit, money = money, waiting = WAITING.containsMatchIn(q),
            people = matchedPeople.map { it.first }, peopleNames = matchedPeople.map { it.second }.distinct(),
            pkgs = matchedApps, merchantTerm = merchant, keywords = keywords,
        )
    }

    private fun range(q: String, today: LocalDate, money: Boolean): Pair<Range, Boolean> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val nDays = Regex("""(?:last|past)\s+(\d{1,3})\s+days""").find(q)?.groupValues?.get(1)?.toLongOrNull()
        return when {
            nDays != null -> Range(today.minusDays(nDays - 1), today.plusDays(1), "the last $nDays days") to true
            "yesterday" in q -> Range(today.minusDays(1), today, "yesterday") to true
            "today" in q || "tonight" in q || "this morning" in q || "this evening" in q -> Range(today, today.plusDays(1), "today") to true
            "last week" in q -> Range(monday.minusWeeks(1), monday, "last week") to true
            "this week" in q || "week" in q -> Range(monday, today.plusDays(1), "this week") to true
            "last month" in q -> Range(today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1), "last month") to true
            "this month" in q || "month" in q -> Range(today.withDayOfMonth(1), today.plusDays(1), "this month") to true
            money -> Range(today.withDayOfMonth(1), today.plusDays(1), "this month") to false
            else -> Range(today.minusDays(13), today.plusDays(1), "the last two weeks") to false
        }
    }

    /** FTS4 query: prefix match on any keyword. */
    fun ftsQuery(keywords: List<String>): String? =
        keywords.map { it.replace(Regex("""[^\p{L}\p{N}]"""), "") }.filter { it.length >= 3 }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" OR ") { "$it*" }
}
