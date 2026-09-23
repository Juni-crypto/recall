package app.recall.understand

import app.recall.App
import app.recall.data.Captured
import app.recall.data.Kind
import app.recall.data.Repo
import app.recall.learn.Learner
import java.time.Instant
import java.time.ZoneId

/** Runs on every incoming message: money, requests, urgency. */
object Understand {
    private const val HOUR = 60 * 60 * 1000L

    fun onIncoming(c: Captured, msgId: Long, trackRequests: Boolean = true) {
        parseMoney(c, msgId)
        if (trackRequests) trackLoops(c, msgId)
    }

    // ------------------------------------------------------------------ money

    private val BANKY = Regex("""\b(a/c|acct|account|upi|card|debited|credited|bank|vpa|imps|neft)\b""", RegexOption.IGNORE_CASE)

    private fun moneyEligible(c: Captured): Boolean = when {
        c.pkg in AppKinds.PAYMENT -> true
        c.kind == Kind.SMS -> SenderHeuristics.isAutomated(c.sender, "") || BANKY.containsMatchIn(c.text)
        c.kind == Kind.EMAIL -> BANKY.containsMatchIn(c.text)
        AppKinds.isChat(c.pkg) -> false // "I paid 500 for dinner" in a chat is not your transaction
        else -> BANKY.containsMatchIn(c.text)
    }

    /** Rebuilds every transaction from saved messages (after a parser upgrade). */
    fun reparseMoney() {
        Repo.clearTxns()
        // Let the model look again at recent alerts the rules can't read.
        Repo.resetMoneyChecked(System.currentTimeMillis() - 3 * Repo.DAY)
        for (m in Repo.messagesBetween(0, Long.MAX_VALUE, includeSelf = false, limit = 200_000)) {
            val c = Captured(
                hash = "", pkg = m.pkg, app = m.app, convKey = m.convKey, convTitle = m.convTitle, sender = m.sender,
                text = m.text, isSelf = false, isGroup = m.isGroup, kind = m.kind, category = null, at = m.at,
                sbnKey = null, ongoing = false,
            )
            parseMoney(c, m.id)
        }
        Repo.pairSelfTransfers()
    }

    private fun parseMoney(c: Captured, msgId: Long) {
        if (!moneyEligible(c)) return
        val p = MoneyParser.parse(c.text)
            ?: c.convTitle?.takeIf { c.kind != Kind.SMS }?.let { MoneyParser.parse("$it. ${c.text}") }
            ?: return
        val source = when (c.kind) {
            Kind.SMS -> (c.sender?.let { smsSource(it) } ?: c.app) + " SMS"
            else -> c.app
        }
        // Your own correction wins, then the rules, then the model's remembered guess.
        val ruled = Categories.of(p.merchant, p.direction, c.text)
        val saved = p.merchant?.let { Repo.merchantCategory(it) }
        val category = when {
            saved?.second == "user" -> saved.first
            ruled != "Other" -> ruled
            else -> saved?.first ?: ruled
        }
        Repo.insertOrMergeTxn(
            amountPaise = p.amountPaise, direction = p.direction, merchant = p.merchant,
            category = category, account = p.account,
            source = source, pkg = c.pkg, msgId = msgId, raw = c.text, at = c.at,
        )
    }

    /** "AX-HDFCBK-S" -> "HDFCBK" */
    private fun smsSource(sender: String): String {
        val parts = sender.split('-')
        return if (parts.size >= 2 && parts[0].length == 2) parts[1] else sender
    }

    // ------------------------------------------------------------------ open loops

    fun mentionsUser(text: String): Boolean {
        val names = App.prefs.userNames.split(',').map { it.trim() }.filter { it.length >= 2 }
        if (names.isEmpty()) return false
        val t = text.lowercase()
        return names.any { n -> Regex("""(^|[^\p{L}])@?${Regex.escape(n.lowercase())}([^\p{L}]|$)""").containsMatchIn(t) }
    }

    private fun trackLoops(c: Captured, msgId: Long) {
        if (c.kind == Kind.OTHER) return
        val automated = SenderHeuristics.isAutomated(c.sender, c.text) || SenderHeuristics.isPromo(c.text) ||
            (c.kind == Kind.EMAIL && SenderHeuristics.isSystemMail(c.text))
        if (automated && c.kind != Kind.CALL) return

        val isCall = c.kind == Kind.CALL
        val direct = isCall || !c.isGroup || c.pkg in AppKinds.MENTION_ONLY || mentionsUser(c.text)
        if (!direct) return

        val request = RequestDetector.detect(c.text)
        val existing = Repo.openLoopForConv(c.convKey)
        if (!isCall && !request.isRequest && existing == null) return

        val pings = Repo.countIncomingSince(c.convKey, c.at - HOUR)
        val profile = Repo.profile(c.convKey)
        val hour = Instant.ofEpochMilli(c.at).atZone(ZoneId.systemDefault()).hour
        val input = UrgencyScorer.Input(
            text = c.text,
            pings = if (isCall) (existing?.asks ?: 0) + 1 else pings,
            importance = profile?.importance ?: 0.3,
            isQuestion = "question" in request.reasons,
            isGroup = c.isGroup,
            isEmail = c.kind == Kind.EMAIL,
            isCall = isCall,
            isEvening = hour >= 18,
            appOpenRate = Learner.appOpenRate(c.pkg),
            automated = automated,
        )
        val score = UrgencyScorer.score(input, Learner.model())
        val person = when {
            c.isGroup && c.convTitle != null && c.sender != null -> "${c.sender} · ${c.convTitle}"
            else -> c.convTitle ?: c.sender ?: c.app
        }

        if (existing == null) {
            val ask = if (isCall) "Missed call" else c.text
            Repo.insertLoop(
                convKey = c.convKey, pkg = c.pkg, app = c.app, person = person, askText = ask, msgId = msgId,
                at = c.at, urgency = score.value, reasons = score.reasons, dueHint = score.dueHint,
                isCall = isCall, features = score.features,
            )
        } else {
            val asks = existing.asks + 1
            val ask = when {
                isCall -> "$asks missed calls"
                request.isRequest -> c.text
                else -> existing.askText
            }
            Repo.updateLoop(
                id = existing.id, askText = ask, lastAt = c.at, asks = asks,
                urgency = maxOf(existing.urgency, score.value),
                reasons = (existing.reasons.filterNot { it.startsWith("asked ") } + score.reasons).distinct(),
                dueHint = score.dueHint ?: existing.dueHint, features = score.features,
            )
        }
    }
}
