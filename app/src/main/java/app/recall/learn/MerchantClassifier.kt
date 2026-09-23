package app.recall.learn

import app.recall.data.Repo
import app.recall.model.Brain
import app.recall.understand.Categories

/**
 * Asks the on-device model which category a merchant belongs to, for merchants the rules
 * don't recognise. The model only picks a label from a fixed list; amounts never go near it.
 * Each merchant is asked about once, and the answer is remembered.
 */
object MerchantClassifier {

    private val SYSTEM = """
        You sort payments from an Indian user's bank and UPI alerts into spending categories.
        For each numbered payee, pick exactly one category from this list:
        ${Categories.ALL.joinToString(", ")}.
        "People" means an individual person rather than a business.
        Reply with one line per payee, in the form "<number>. <Category>", and nothing else.
    """.trimIndent()

    private val LINE = Regex("""^\s*(\d+)[.):-]\s*([A-Za-z]+)""")

    /** Returns how many merchants got a real category. [onProgress] gets the number looked at so far. */
    suspend fun run(batch: Int = 25, rounds: Int = 4, onProgress: suspend (Int) -> Unit = {}): Int {
        if (!Brain.available()) return 0
        var done = 0
        var seen = 0
        repeat(rounds) { round ->
            if (!app.recall.model.Safety.check(app.recall.model.Safety.Load.Light).ok) return done
            if (round > 0 && !app.recall.model.Safety.power().charging) kotlinx.coroutines.delay(5_000)
            val todo = Repo.uncategorisedMerchants(batch)
            if (todo.isEmpty()) return done
            val user = buildString {
                todo.forEachIndexed { i, (merchant, raw) ->
                    appendLine("${i + 1}. $merchant — \"${raw.replace('\n', ' ').take(90)}\"")
                }
            }
            val out = Brain.generate(SYSTEM, user, maxTokens = 12 * todo.size + 20, temperature = 0.1f) ?: return done
            val picked = out.lines().mapNotNull { line ->
                LINE.find(line)?.let { m ->
                    val idx = m.groupValues[1].toInt() - 1
                    val cat = Categories.ALL.firstOrNull { it.equals(m.groupValues[2], ignoreCase = true) }
                    if (idx in todo.indices && cat != null) idx to cat else null
                }
            }.toMap()
            todo.forEachIndexed { i, (merchant, _) ->
                // Unanswered ones are stored as Other so they aren't asked about every night.
                Repo.setMerchantCategory(merchant, picked[i] ?: "Other", "model")
            }
            done += picked.count { it.value != "Other" }
            seen += todo.size
            onProgress(seen)
        }
        return done
    }
}
