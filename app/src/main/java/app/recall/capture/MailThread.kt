package app.recall.capture

/**
 * An email conversation is one thread (sender + subject), not everything from a sender:
 * ten different alerts or PRs from one address are ten separate things.
 */
object MailThread {
    private val PREFIX = Regex("""^\s*((re|fwd?|aw|wg)\s*(\[\d+])?\s*:\s*)+""", RegexOption.IGNORE_CASE)
    private val SPACE = Regex("""\s+""")

    /** "Re: Fwd: [Cosmos] ETL run failed…" -> "[cosmos] etl run failed" */
    fun subject(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(PREFIX, "").replace(SPACE, " ").trim().trimEnd('…', '.').trim()
            .lowercase().take(60).trim()

    fun key(pkg: String, sender: String?, text: String): String = "$pkg|${sender.orEmpty()}|${subject(text)}"
}
