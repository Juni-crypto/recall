package app.recall.learn

import app.recall.App
import app.recall.data.Repo
import app.recall.understand.Categories

/**
 * Learns the account holder's name from bank messages ("Dear R KUMAR", "…:R KUMAR"), so money
 * moved between the user's own accounts isn't counted as spending or income.
 */
object OwnerName {
    private val PATTERNS = listOf(
        Regex("""\bDear\s+([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})\s*[,:]"""),
        Regex("""MOBILE-[X\d]+:([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})"""),
        Regex("""\bto\s+([A-Z][A-Z.]*(?:\s+[A-Z][A-Z.]*){1,3})\s+(?:A/c|a/c)\s+linked\b"""),
    )
    private val NOT_NAMES = setOf("CUSTOMER", "SIR", "MADAM", "USER", "MEMBER", "CARDHOLDER", "INVESTOR", "SIR/MADAM", "VALUED CUSTOMER")

    /** The user says this name is theirs (e.g. their UPI ID or another bank's spelling). */
    fun add(name: String) {
        val names = (App.prefs.ownerName.split('|') + name).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        App.prefs.ownerName = names.joinToString("|")
        load()
        Repo.markOwnName(name)
    }

    fun load() {
        Categories.ownerNames = App.prefs.ownerName.split('|').filter { it.isNotBlank() }
    }

    fun detect() {
        val counts = HashMap<String, Int>()
        for (m in Repo.bankMessages(4000)) {
            for (p in PATTERNS) {
                p.findAll(m).forEach { r ->
                    val name = r.groupValues[1].trim().replace(Regex("""\s+"""), " ")
                    if (name.uppercase() !in NOT_NAMES && name.length >= 5) counts[name] = (counts[name] ?: 0) + 1
                }
            }
        }
        val names = counts.filter { it.value >= 3 }.entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (names.isNotEmpty()) {
            App.prefs.ownerName = names.joinToString("|")
            load()
        }
    }
}
