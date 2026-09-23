package app.recall.understand

/**
 * Decides whether a message asks something of the user.
 * Deliberately rule-based: fast, explainable, and it works before any model is downloaded.
 */
object RequestDetector {
    data class Result(val isRequest: Boolean, val reasons: List<String>)

    private val ASKS = listOf(
        Regex("""\b(can|could|would|will|pls|plz)\s+(you|u|ya|ye)\b"""),
        Regex("""\b(please|pls|plz|kindly)\b"""),
        Regex("""\b(approve|review|check|send|share|call|ping|reply|respond|confirm|merge|sign|pay|transfer|fix|update|look into|look at|take a look|forward|book|order|bring|pick up|drop)\b.*\b(me|this|it|the|my|pr|doc|file|invoice|link|details|asap|pls|please|us)\b"""),
        Regex("""\b(let me know|lmk|any update|any news|following up|follow(ing)? up|gentle reminder|reminder)\b"""),
        Regex("""\b(waiting (for|on) (you|your|u|ur))\b"""),
        Regex("""\b(need (you|your|u|ur))\b"""),
        Regex("""\b(are|r) (you|u) (free|available|there|around|coming|up|ok|home|awake|joining)\b"""),
        Regex("""\b(did|have|will) (you|u)\b"""),
        Regex("""\bwhen (can|will|are|r|do) (you|u)\b"""),
        Regex("""\b(what time|where are you|where r u|call me|call back|callback|ring me)\b"""),
    )

    private val ACK = Regex(
        """^(ok+|okay|k+|kk|sure|thanks|thank you|thx|ty|done|great|cool|nice|noted|yes|yeah|yep|no|nope|haha+|lol|👍|🙏|❤️|good night|gn|gm|good morning)[.! ]*$""",
    )

    fun detect(text: String): Result {
        val t = text.lowercase().trim()
        if (t.isEmpty() || ACK.matches(t)) return Result(false, emptyList())
        val reasons = mutableListOf<String>()
        if (ASKS.any { it.containsMatchIn(t) }) reasons += "ask"
        val words = t.split(Regex("""\s+""")).count { it.any(Char::isLetter) }
        if (t.contains('?') && words >= 2) reasons += "question"
        return Result(reasons.isNotEmpty(), reasons)
    }
}
