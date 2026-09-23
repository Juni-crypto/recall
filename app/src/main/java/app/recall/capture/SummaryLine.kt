package app.recall.capture

/**
 * Splits a line of a bundled summary ("12 new emails" lists one line per email).
 * No Android types here, so it's unit-tested on the JVM.
 */
object SummaryLine {
    // "Beacon   [Beacon] Replica updated", "Priya: can you send it", "Karthik - Contract"
    private val SPLIT = Regex("""^(.{1,60}?)(?::\s|\s{2,}|\s[-–·|]\s)(.+)$""")

    /** Sender and text from a line with no formatting left. The sender is null if there isn't one. */
    fun split(line: String): Pair<String?, String> {
        val s = line.trim()
        SPLIT.find(s)?.let { m ->
            val sender = m.groupValues[1].trim()
            val text = m.groupValues[2].trim()
            if (sender.isNotEmpty() && text.isNotEmpty()) return sender to text
        }
        return null to s
    }
}
