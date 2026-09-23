package app.recall.learn

import app.recall.App
import app.recall.data.Profile
import app.recall.data.Repo
import app.recall.understand.LearnedModel
import app.recall.understand.SenderHeuristics
import app.recall.util.Time

/**
 * Learns the user's habits from their own reactions. Nothing here retrains the language
 * model; it keeps per-person reply habits, a small urgency model and plain-language facts.
 */
object Learner {
    private const val DAY = Repo.DAY
    private const val HOUR = 60 * 60 * 1000L

    @Volatile private var cachedModel: LearnedModel? = null
    @Volatile private var modelLoadedAt = 0L
    @Volatile private var openRates: Map<String, Double> = emptyMap()
    @Volatile private var openRatesAt = 0L

    fun model(): LearnedModel? {
        val now = Time.now()
        if (now - modelLoadedAt > HOUR) {
            cachedModel = Repo.loadWeights()?.let { (w, n) -> LearnedModel(w, n) }
            modelLoadedAt = now
        }
        return cachedModel
    }

    /** Share of this app's notifications the user opens (0..1). */
    fun appOpenRate(pkg: String): Double {
        val now = Time.now()
        if (now - openRatesAt > 6 * HOUR) refreshOpenRates(now)
        return openRates[pkg] ?: 0.5
    }

    private fun refreshOpenRates(now: Long) {
        openRates = Repo.signalCountsByPkg(now - 30 * DAY).mapValues { (_, k) ->
            val opened = (k["opened"] ?: 0) + (k["app_cancel"] ?: 0) + (k["reply"] ?: 0)
            val dismissed = k["dismissed"] ?: 0
            val total = opened + dismissed
            if (total < 5) 0.5 else opened.toDouble() / total
        }
        openRatesAt = now
    }

    /** Called by the nightly digest before it builds. */
    fun runNightly(now: Long = Time.now(), forceFacts: Boolean = false) {
        Repo.expireLoops(now)
        if (!App.prefs.learning) return
        OwnerName.detect()
        updateProfiles(now)
        train(now)
        refreshOpenRates(now)
        if (forceFacts || now - App.prefs.lastFactsAt > 6 * DAY || Repo.facts().isEmpty()) {
            InsightWriter.refreshFacts(now)
            App.prefs.lastFactsAt = now
        }
    }

    // ------------------------------------------------------------------ profiles

    private fun updateProfiles(now: Long) {
        val since = now - 30 * DAY
        val loopsByConv = Repo.loopsSince(since).groupBy { it.convKey }
        for ((key, title, pkg) in Repo.conversations(since)) {
            if (SenderHeuristics.isAutomated(title, "")) continue
            val msgs = Repo.messagesForConv(key, since)
            val incoming = msgs.count { !it.isSelf }
            if (incoming < 3) continue

            val latencies = mutableListOf<Long>()
            var pendingAt: Long? = null
            var bursts = 0
            for (m in msgs) {
                if (!m.isSelf) {
                    if (pendingAt == null) {
                        pendingAt = m.at
                        bursts++
                    }
                } else if (pendingAt != null) {
                    latencies += m.at - pendingAt
                    pendingAt = null
                }
            }
            val median = latencies.sorted().let { if (it.isEmpty()) null else it[it.size / 2] / 1000 }
            val loops = loopsByConv[key].orEmpty()
            val medianMs = (median ?: (6 * 3600)) * 1000
            val misses = loops.count {
                it.state == "expired" ||
                    (it.state == "open" && now - it.firstAt > maxOf(DAY, 3 * medianMs)) ||
                    (it.closedBy == "reply" && (it.closedAt ?: now) - it.firstAt > maxOf(6 * HOUR, 3 * medianMs))
            }
            val done = loops.count { it.closedBy == "done" }
            val notForMe = loops.count { it.closedBy == "not_for_me" }

            val replyRate = if (bursts == 0) 0.0 else latencies.size.toDouble() / bursts
            val speed = if (median == null) 0.0 else 1.0 / (1.0 + median / 1800.0)
            val volume = (incoming / 60.0).coerceAtMost(1.0)
            val importance = (0.35 * replyRate + 0.35 * speed + 0.15 * volume + 0.15 * (done / 3.0).coerceAtMost(1.0) - 0.2 * notForMe)
                .coerceIn(0.05, 1.0)

            Repo.upsertProfile(
                Profile(
                    personKey = key, display = title, pkg = pkg, msgsIn = incoming, replies = latencies.size,
                    medianReplyS = median, misses = misses, importance = importance,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ urgency model

    private fun train(now: Long) {
        val examples = Repo.loopsSince(now - 60 * DAY)
            .filter { it.features != null && it.label != null }
            .map { it.features!! to it.label!! }
        if (examples.size < LearnedModel.MIN_EXAMPLES) return
        val model = LearnedModel.train(examples)
        Repo.saveWeights(model.weights, model.n)
        cachedModel = model
        modelLoadedAt = now
    }
}
