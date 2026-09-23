package app.recall.understand

import kotlin.math.exp

/**
 * Starting urgency rules. After enough of the user's reactions, [LearnedModel] blends in a
 * personal model trained on what they actually treat as urgent.
 */
object UrgencyScorer {
    private val STRONG = Regex(
        """\b(urgent(ly)?|asap|a\.s\.a\.p|emergency|immediately|blocking|blocker|blocked|critical|hurry|right now|at the earliest|p0|sev ?1|911)\b""",
    )
    private val WEAK = Regex("""\b(quick(ly)?|now|important|priority|soon|fast|jaldi|jldi)\b""")
    private val DEADLINE = Regex(
        """\b(by\s+(\d{1,2}(:\d{2})?\s*(am|pm)?|today|tonight|tomorrow|eod|eow|noon|evening|morning|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|today|tonight|eod|end of (the )?day|before (the )?(call|meeting|standup)|deadline|in \d+\s*(min|mins|minutes|hr|hrs|hours))\b""",
    )
    private val DUE = Regex(
        """\b(by\s+\d{1,2}(:\d{2})?\s*(am|pm)?|by\s+(today|tonight|tomorrow|eod|noon|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|today|tonight|eod|end of (the )?day)\b""",
    )

    /** Feature vector layout, shared with the learned model. Index 0 is the bias. */
    const val F_BIAS = 0
    const val F_STRONG = 1
    const val F_WEAK = 2
    const val F_DEADLINE = 3
    const val F_REPEAT = 4
    const val F_IMPORTANCE = 5
    const val F_QUESTION = 6
    const val F_GROUP = 7
    const val F_EMAIL = 8
    const val F_CALL = 9
    const val F_EVENING = 10
    const val F_APP_OPEN_RATE = 11
    const val N_FEATURES = 12

    data class Input(
        val text: String,
        val pings: Int,
        val importance: Double,
        val isQuestion: Boolean,
        val isGroup: Boolean,
        val isEmail: Boolean,
        val isCall: Boolean,
        val isEvening: Boolean,
        val appOpenRate: Double,
        val automated: Boolean,
    )

    data class Score(val value: Int, val reasons: List<String>, val dueHint: String?, val features: DoubleArray)

    fun score(input: Input, learned: LearnedModel? = null): Score {
        val t = input.text.lowercase()
        val strong = STRONG.containsMatchIn(t)
        val weak = !strong && WEAK.containsMatchIn(t)
        val deadline = DEADLINE.containsMatchIn(t)
        val dueHint = DUE.find(t)?.value
        val repeat = input.pings >= 2

        val reasons = mutableListOf<String>()
        var s = 0.0
        if (input.isCall) { s += 45; reasons += "missed call" }
        if (strong) { s += 40; reasons += "urgent" } else if (weak) { s += 25; reasons += "quick" }
        if (repeat) { s += if (input.pings >= 4) 30 else 25; reasons += "asked ${input.pings}×" }
        if (deadline) { s += 20; reasons += (dueHint?.let { "due $it" } ?: "deadline") }
        if (input.isQuestion) s += 5
        s += 15 * input.importance
        if (input.automated) s -= 30

        val f = DoubleArray(N_FEATURES)
        f[F_BIAS] = 1.0
        f[F_STRONG] = if (strong) 1.0 else 0.0
        f[F_WEAK] = if (weak) 1.0 else 0.0
        f[F_DEADLINE] = if (deadline) 1.0 else 0.0
        f[F_REPEAT] = (input.pings.coerceAtMost(5) / 5.0)
        f[F_IMPORTANCE] = input.importance
        f[F_QUESTION] = if (input.isQuestion) 1.0 else 0.0
        f[F_GROUP] = if (input.isGroup) 1.0 else 0.0
        f[F_EMAIL] = if (input.isEmail) 1.0 else 0.0
        f[F_CALL] = if (input.isCall) 1.0 else 0.0
        f[F_EVENING] = if (input.isEvening) 1.0 else 0.0
        f[F_APP_OPEN_RATE] = input.appOpenRate

        var value = s
        if (learned != null && learned.n >= LearnedModel.MIN_EXAMPLES) {
            value = 0.6 * s + 0.4 * (100 * learned.predict(f))
        }
        return Score(value.toInt().coerceIn(0, 100), reasons, dueHint, f)
    }
}

/** Logistic regression over the urgency features, retrained each night on-device. */
class LearnedModel(val weights: DoubleArray, val n: Int) {
    fun predict(x: DoubleArray): Double {
        var z = 0.0
        for (i in weights.indices) z += weights[i] * x.getOrElse(i) { 0.0 }
        return 1.0 / (1.0 + exp(-z))
    }

    companion object {
        const val MIN_EXAMPLES = 30

        fun train(examples: List<Pair<DoubleArray, Int>>, epochs: Int = 300, lr: Double = 0.1, l2: Double = 0.01): LearnedModel {
            val w = DoubleArray(UrgencyScorer.N_FEATURES)
            if (examples.isEmpty()) return LearnedModel(w, 0)
            repeat(epochs) {
                for ((x, y) in examples) {
                    var z = 0.0
                    for (i in w.indices) z += w[i] * x.getOrElse(i) { 0.0 }
                    val p = 1.0 / (1.0 + exp(-z))
                    val err = p - y
                    for (i in w.indices) {
                        val reg = if (i == 0) 0.0 else l2 * w[i]
                        w[i] -= lr * (err * x.getOrElse(i) { 0.0 } + reg)
                    }
                }
            }
            return LearnedModel(w, examples.size)
        }
    }
}
