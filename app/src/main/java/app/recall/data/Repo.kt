package app.recall.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.recall.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/** All reads and writes. Every function is blocking; call from Dispatchers.IO. */
object Repo {
    private val db get() = App.db
    private val w: SQLiteDatabase get() = db.w
    private val r: SQLiteDatabase get() = db.readableDatabase

    /** Re-runs [block] after every database write. */
    fun <T> watch(block: () -> T): Flow<T> =
        db.changes.map { block() }.conflate().distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun now() = System.currentTimeMillis()

    // ---------------------------------------------------------------- messages

    /** Returns the new row id, or null if this exact message was already stored. */
    fun insertMessage(c: Captured): Long? {
        val cv = ContentValues().apply {
            put("hash", c.hash)
            put("pkg", c.pkg)
            put("app", c.app)
            put("conv_key", c.convKey)
            put("conv_title", c.convTitle)
            put("sender", c.sender)
            put("text", c.text)
            put("is_self", if (c.isSelf) 1 else 0)
            put("is_group", if (c.isGroup) 1 else 0)
            put("kind", c.kind)
            put("category", c.category)
            put("at", c.at)
            put("sbn_key", c.sbnKey)
            put("ongoing", if (c.ongoing) 1 else 0)
        }
        val id = w.insertWithOnConflict("message", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return null
        w.insert("message_fts", null, ContentValues().apply {
            put("docid", id)
            put("sender", c.sender ?: "")
            put("conv_title", c.convTitle ?: "")
            put("text", c.text)
        })
        if (!c.ongoing) {
            val cv2 = ContentValues().apply {
                put("conv_key", c.convKey)
                put("pkg", c.pkg)
                put("app", c.app)
                put("title", c.convTitle ?: c.sender)
                put("kind", c.kind)
                put("is_group", if (c.isGroup) 1 else 0)
                put("last_text", c.text.take(200))
            }
            val col = if (c.isSelf) "last_out_at" else "last_in_at"
            val updated = w.update("conversation", cv2.apply { put(col, c.at) }, "conv_key=?", arrayOf(c.convKey))
            if (updated == 0) w.insert("conversation", null, cv2)
        }
        db.changed()
        return id
    }

    private fun Cursor.toMessage() = Message(
        id = getLong(0), pkg = getString(1), app = getString(2), convKey = getString(3),
        convTitle = getStringOrNull(4), sender = getStringOrNull(5), text = getString(6),
        isSelf = getInt(7) == 1, isGroup = getInt(8) == 1, kind = getString(9), at = getLong(10),
        ongoing = getInt(11) == 1,
    )

    private const val MSG_COLS = "m.id, m.pkg, m.app, m.conv_key, m.conv_title, m.sender, m.text, m.is_self, m.is_group, m.kind, m.at, m.ongoing"

    fun messagesBetween(start: Long, end: Long, includeSelf: Boolean = true, limit: Int = 5000): List<Message> =
        r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.at >= ? AND m.at < ? AND m.ongoing = 0" +
                (if (includeSelf) "" else " AND m.is_self = 0") +
                " ORDER BY m.at ASC LIMIT $limit",
            arrayOf(start.toString(), end.toString()),
        ).use { c -> c.map { it.toMessage() } }

    fun messagesForConv(convKey: String, since: Long): List<Message> =
        r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.conv_key = ? AND m.at >= ? AND m.ongoing = 0 ORDER BY m.at ASC",
            arrayOf(convKey, since.toString()),
        ).use { c -> c.map { it.toMessage() } }

    fun messagesForConvs(convKeys: List<String>, start: Long, end: Long, limit: Int): List<Message> {
        if (convKeys.isEmpty()) return emptyList()
        val marks = convKeys.joinToString(",") { "?" }
        return r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.conv_key IN ($marks) AND m.at >= ? AND m.at < ? AND m.ongoing = 0 ORDER BY m.at DESC LIMIT $limit",
            (convKeys + listOf(start.toString(), end.toString())).toTypedArray(),
        ).use { c -> c.map { it.toMessage() } }.reversed()
    }

    fun recentMessages(limit: Int, start: Long = 0, end: Long = Long.MAX_VALUE): List<Message> =
        r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.at >= ? AND m.at < ? ORDER BY m.at DESC LIMIT $limit",
            arrayOf(start.toString(), end.toString()),
        ).use { c -> c.map { it.toMessage() } }

    /** Full-text search. [query] is FTS4 syntax, e.g. `rahul* OR site*`. */
    fun search(query: String, start: Long = 0, end: Long = Long.MAX_VALUE, limit: Int = 200): List<Message> =
        try {
            r.rawQuery(
                "SELECT $MSG_COLS FROM message_fts f JOIN message m ON m.id = f.docid " +
                    "WHERE message_fts MATCH ? AND m.at >= ? AND m.at < ? ORDER BY m.at DESC LIMIT $limit",
                arrayOf(query, start.toString(), end.toString()),
            ).use { c -> c.map { it.toMessage() } }
        } catch (e: Exception) {
            emptyList()
        }

    fun lastIncomingBefore(convKey: String, at: Long): Long? =
        r.rawQuery(
            "SELECT MAX(at) FROM message WHERE conv_key = ? AND is_self = 0 AND at <= ? AND ongoing = 0",
            arrayOf(convKey, at.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun countIncomingSince(convKey: String, since: Long): Int =
        r.rawQuery(
            "SELECT COUNT(*) FROM message WHERE conv_key = ? AND is_self = 0 AND at >= ? AND ongoing = 0",
            arrayOf(convKey, since.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun hasSelfMessageSince(convKey: String, since: Long): Boolean =
        r.rawQuery(
            "SELECT 1 FROM message WHERE conv_key = ? AND is_self = 1 AND at >= ? LIMIT 1",
            arrayOf(convKey, since.toString()),
        ).use { it.moveToFirst() }

    fun countMessages(start: Long, end: Long): Int =
        r.rawQuery(
            "SELECT COUNT(*) FROM message WHERE at >= ? AND at < ? AND ongoing = 0 AND is_self = 0",
            arrayOf(start.toString(), end.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun countByPkg(pkg: String): Int =
        r.rawQuery("SELECT COUNT(*) FROM message WHERE pkg = ?", arrayOf(pkg)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun countImportedChats(): Int =
        r.rawQuery("SELECT COUNT(DISTINCT conv_key) FROM message WHERE conv_key LIKE '%|import|%'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun countAllMessages(): Int =
        r.rawQuery("SELECT COUNT(*) FROM message", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun appCounts(start: Long, end: Long): List<AppCount> =
        r.rawQuery(
            "SELECT app, pkg, COUNT(*) n FROM message WHERE at >= ? AND at < ? AND ongoing = 0 AND is_self = 0 " +
                "GROUP BY pkg ORDER BY n DESC",
            arrayOf(start.toString(), end.toString()),
        ).use { c -> c.map { AppCount(it.getString(0), it.getString(1), it.getInt(2)) } }

    /** One-to-one conversations with a readable title: used to match names in questions. */
    fun people(): List<Pair<String, String>> =
        r.rawQuery(
            "SELECT conv_key, title FROM conversation WHERE title IS NOT NULL AND kind IN ('chat','sms','email','call')",
            null,
        ).use { c -> c.map { it.getString(0) to it.getString(1) } }

    fun appLabels(): List<Pair<String, String>> =
        r.rawQuery("SELECT DISTINCT pkg, app FROM conversation", null)
            .use { c -> c.map { it.getString(0) to it.getString(1) } }

    fun conversations(since: Long): List<Triple<String, String, String>> =
        r.rawQuery(
            "SELECT conv_key, COALESCE(title, app), pkg FROM conversation WHERE is_group = 0 AND kind IN ('chat','sms','email') AND last_in_at >= ?",
            arrayOf(since.toString()),
        ).use { c -> c.map { Triple(it.getString(0), it.getString(1), it.getString(2)) } }

    fun firstMessageAt(): Long? =
        r.rawQuery("SELECT MIN(at) FROM message", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    // ---------------------------------------------------------------- signals

    fun logSignal(kind: String, pkg: String?, convKey: String?, value: Double? = null, refId: Long? = null, at: Long = now()) {
        w.insert("signal", null, ContentValues().apply {
            put("kind", kind)
            put("pkg", pkg)
            put("conv_key", convKey)
            put("at", at)
            if (value != null) put("value", value)
            if (refId != null) put("ref_id", refId)
        })
    }

    /** kind -> count, per package, since [since]. */
    fun signalCountsByPkg(since: Long): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        r.rawQuery(
            "SELECT pkg, kind, COUNT(*) FROM signal WHERE at >= ? AND pkg IS NOT NULL GROUP BY pkg, kind",
            arrayOf(since.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
            }
        }
        return out
    }

    fun signalCount(kind: String, convKey: String, since: Long): Int =
        r.rawQuery(
            "SELECT COUNT(*) FROM signal WHERE kind = ? AND conv_key = ? AND at >= ?",
            arrayOf(kind, convKey, since.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    // ---------------------------------------------------------------- open loops

    private const val LOOP_COLS =
        "id, conv_key, pkg, app, person, ask_text, first_at, last_at, asks, urgency, reasons, due_hint, is_call, state, seen_at, snooze_until, first_msg_id, ai"

    private fun Cursor.toLoop() = OpenLoop(
        id = getLong(0), convKey = getString(1), pkg = getString(2), app = getString(3), person = getString(4),
        askText = getString(5), firstAt = getLong(6), lastAt = getLong(7), asks = getInt(8), urgency = getInt(9),
        reasons = getStringOrNull(10)?.let(::jsonList) ?: emptyList(), dueHint = getStringOrNull(11),
        isCall = getInt(12) == 1, state = getString(13), seenAt = getLongOrNull(14), snoozeUntil = getLongOrNull(15),
        firstMsgId = getLongOrNull(16), ai = getInt(17) == 1,
    )

    fun openLoopForConv(convKey: String): OpenLoop? =
        r.rawQuery("SELECT $LOOP_COLS FROM open_loop WHERE conv_key = ? AND state = 'open' ORDER BY id DESC LIMIT 1", arrayOf(convKey))
            .use { c -> if (c.moveToFirst()) c.toLoop() else null }

    fun loop(id: Long): OpenLoop? =
        r.rawQuery("SELECT $LOOP_COLS FROM open_loop WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toLoop() else null }

    fun insertLoop(
        convKey: String, pkg: String, app: String, person: String, askText: String, msgId: Long?, at: Long,
        urgency: Int, reasons: List<String>, dueHint: String?, isCall: Boolean, features: DoubleArray,
    ): Long {
        val id = w.insert("open_loop", null, ContentValues().apply {
            put("conv_key", convKey); put("pkg", pkg); put("app", app); put("person", person)
            put("ask_text", askText); put("first_msg_id", msgId); put("first_at", at); put("last_at", at)
            put("asks", 1); put("urgency", urgency); put("reasons", JSONArray(reasons).toString())
            put("due_hint", dueHint); put("is_call", if (isCall) 1 else 0)
            put("features", JSONArray(features.toList()).toString())
        })
        db.changed()
        return id
    }

    fun updateLoop(id: Long, askText: String, lastAt: Long, asks: Int, urgency: Int, reasons: List<String>, dueHint: String?, features: DoubleArray) {
        w.update("open_loop", ContentValues().apply {
            put("ask_text", askText); put("last_at", lastAt); put("asks", asks); put("urgency", urgency)
            put("reasons", JSONArray(reasons).toString()); put("due_hint", dueHint)
            put("features", JSONArray(features.toList()).toString())
            putNull("snooze_until")
        }, "id = ?", arrayOf(id.toString()))
        db.changed()
    }

    // ---------------------------------------------------------------- model triage

    /** Recent messages from people that the model hasn't looked at yet. */
    fun triageCandidates(since: Long, limit: Int): List<Message> =
        r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.triaged = 0 AND m.is_self = 0 AND m.ongoing = 0 " +
                "AND m.kind IN ('chat','sms','email') AND m.at >= ? ORDER BY m.at ASC LIMIT $limit",
            arrayOf(since.toString()),
        ).use { c -> c.map { it.toMessage() } }

    fun markTriaged(ids: Collection<Long>, value: Int) {
        if (ids.isEmpty()) return
        w.execSQL("UPDATE message SET triaged = $value WHERE id IN (${ids.joinToString(",")})")
    }

    /** Recent messages that look like money but the rules couldn't parse. */
    fun moneyCandidates(since: Long, limit: Int): List<Message> =
        r.rawQuery(
            "SELECT $MSG_COLS FROM message m WHERE m.money_checked = 0 AND m.is_self = 0 AND m.at >= ? " +
                "AND m.kind IN ('sms','email','other') AND NOT EXISTS (SELECT 1 FROM txn t WHERE t.msg_id = m.id) " +
                "ORDER BY m.at ASC LIMIT $limit",
            arrayOf(since.toString()),
        ).use { c -> c.map { it.toMessage() } }

    fun markMoneyChecked(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        w.execSQL("UPDATE message SET money_checked = 1 WHERE id IN (${ids.joinToString(",")})")
    }

    /** The model's reading of a request replaces the rules' guess. */
    fun updateLoopFromModel(id: Long, ask: String, urgency: Int, dueHint: String?) {
        w.update("open_loop", ContentValues().apply {
            put("ask_text", ask); put("urgency", urgency); put("due_hint", dueHint); put("ai", 1)
        }, "id = ?", arrayOf(id.toString()))
        db.changed()
    }

    fun markLoopAi(id: Long) {
        w.update("open_loop", ContentValues().apply { put("ai", 1) }, "id = ?", arrayOf(id.toString()))
    }

    fun closeLoopsForConv(convKey: String, by: String, label: Int?) {
        val cv = ContentValues().apply {
            put("state", "closed"); put("closed_by", by); put("closed_at", now())
            if (label != null) put("label", label)
        }
        val n = w.update("open_loop", cv, "conv_key = ? AND state = 'open'", arrayOf(convKey))
        if (n > 0) db.changed()
    }

    /** Closes open items from someone (by display name), e.g. after calling them back. */
    fun closeLoopsForPerson(person: String, by: String) {
        val n = w.update("open_loop", ContentValues().apply {
            put("state", "closed"); put("closed_by", by); put("closed_at", now()); put("label", 1)
        }, "state = 'open' AND (person = ? COLLATE NOCASE OR person LIKE ? COLLATE NOCASE)", arrayOf(person, "$person ·%"))
        if (n > 0) db.changed()
    }

    fun setLoopState(id: Long, state: String, by: String, label: Int?) {
        w.update("open_loop", ContentValues().apply {
            put("state", state); put("closed_by", by); put("closed_at", now())
            if (label != null) put("label", label)
        }, "id = ?", arrayOf(id.toString()))
        db.changed()
    }

    fun snoozeLoop(id: Long, until: Long) {
        w.update("open_loop", ContentValues().apply { put("snooze_until", until) }, "id = ?", arrayOf(id.toString()))
        db.changed()
    }

    fun markSeen(convKey: String, at: Long) {
        w.update("open_loop", ContentValues().apply { put("seen_at", at) }, "conv_key = ? AND state = 'open' AND seen_at IS NULL", arrayOf(convKey))
    }

    fun openLoops(now: Long = now()): List<OpenLoop> =
        r.rawQuery(
            "SELECT $LOOP_COLS FROM open_loop WHERE state = 'open' AND (snooze_until IS NULL OR snooze_until <= ?) ORDER BY urgency DESC, last_at DESC",
            arrayOf(now.toString()),
        ).use { c -> c.map { it.toLoop() } }

    fun expireLoops(now: Long) {
        val week = now - 7 * DAY
        val threeDays = now - 3 * DAY
        w.execSQL(
            "UPDATE open_loop SET state = 'expired', closed_by = 'expired', closed_at = ?, label = COALESCE(label, 0) " +
                "WHERE state = 'open' AND ((is_call = 0 AND last_at < ?) OR (is_call = 1 AND last_at < ?))",
            arrayOf(now, week, threeDays),
        )
        db.changed()
    }

    data class LoopRow(
        val convKey: String, val pkg: String, val firstAt: Long, val closedAt: Long?, val state: String,
        val closedBy: String?, val features: DoubleArray?, val label: Int?,
    )

    fun loopsSince(since: Long): List<LoopRow> =
        r.rawQuery(
            "SELECT conv_key, pkg, first_at, closed_at, state, closed_by, features, label FROM open_loop WHERE first_at >= ?",
            arrayOf(since.toString()),
        ).use { c ->
            c.map {
                LoopRow(
                    it.getString(0), it.getString(1), it.getLong(2), it.getLongOrNull(3), it.getString(4),
                    it.getStringOrNull(5),
                    it.getStringOrNull(6)?.let { s -> JSONArray(s).let { a -> DoubleArray(a.length()) { i -> a.getDouble(i) } } },
                    it.getIntOrNull(7),
                )
            }
        }

    // ---------------------------------------------------------------- money

    private fun Cursor.toTxn() = Txn(
        id = getLong(0), amountPaise = getLong(1), direction = getString(2), merchant = getStringOrNull(3),
        category = getString(4), account = getStringOrNull(5), sources = jsonList(getString(6)), at = getLong(7),
        raw = getStringOrNull(8),
    )

    private const val TXN_COLS = "id, amount_paise, direction, merchant, category, account, sources, at, raw"

    /**
     * Stores a transaction, or merges it into one seen in the last 10 minutes with the same
     * amount and direction (the same payment usually arrives as a bank SMS *and* a UPI alert).
     */
    fun insertOrMergeTxn(
        amountPaise: Long, direction: String, merchant: String?, category: String, account: String?,
        source: String, pkg: String, msgId: Long, raw: String, at: Long,
    ) {
        val window = 10 * 60 * 1000L
        val existing = r.rawQuery(
            "SELECT $TXN_COLS FROM txn WHERE amount_paise = ? AND direction = ? AND at BETWEEN ? AND ? LIMIT 1",
            arrayOf(amountPaise.toString(), direction, (at - window).toString(), (at + window).toString()),
        ).use { c -> if (c.moveToFirst()) c.toTxn() else null }

        if (existing != null) {
            val sources = (existing.sources + source).distinct()
            w.update("txn", ContentValues().apply {
                put("sources", JSONArray(sources).toString())
                if (existing.merchant == null && merchant != null) {
                    put("merchant", merchant); put("category", category)
                }
                if (existing.account == null && account != null) put("account", account)
            }, "id = ?", arrayOf(existing.id.toString()))
        } else {
            w.insert("txn", null, ContentValues().apply {
                put("amount_paise", amountPaise); put("direction", direction); put("merchant", merchant)
                put("category", category); put("account", account); put("sources", JSONArray(listOf(source)).toString())
                put("pkg", pkg); put("msg_id", msgId); put("raw", raw.take(400)); put("at", at)
            })
        }
        db.changed()
    }

    /** (category, source) where source is "user" or "model". */
    fun merchantCategory(merchant: String): Pair<String, String>? =
        r.rawQuery("SELECT category, source FROM merchant_category WHERE merchant = ? COLLATE NOCASE", arrayOf(merchant))
            .use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }

    /** Remembers a merchant's category and applies it to its uncategorised payments. */
    fun setMerchantCategory(merchant: String, category: String, source: String) {
        w.insertWithOnConflict("merchant_category", null, ContentValues().apply {
            put("merchant", merchant); put("category", category); put("source", source); put("at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // The user's choice applies to every payment to this merchant; the model's only fills gaps.
        val scope = if (source == "user") "" else " AND category = 'Other'"
        w.execSQL("UPDATE txn SET category = ? WHERE merchant = ? COLLATE NOCASE$scope", arrayOf(category, merchant))
        db.changed()
    }

    /** Merchants still in "Other" that nobody has categorised yet, with one sample message each. */
    fun uncategorisedMerchants(limit: Int): List<Pair<String, String>> =
        r.rawQuery(
            "SELECT merchant, MAX(raw) FROM txn WHERE category = 'Other' AND merchant IS NOT NULL " +
                "AND merchant NOT IN (SELECT merchant FROM merchant_category) GROUP BY merchant COLLATE NOCASE LIMIT $limit",
            null,
        ).use { c -> c.map { it.getString(0) to (it.getStringOrNull(1) ?: "") } }

    fun merchantCategories(): List<Triple<String, String, String>> =
        r.rawQuery("SELECT merchant, category, source FROM merchant_category ORDER BY source DESC, merchant", null)
            .use { c -> c.map { Triple(it.getString(0), it.getString(1), it.getString(2)) } }

    fun labelledLoops(): Int =
        r.rawQuery("SELECT COUNT(*) FROM open_loop WHERE label IS NOT NULL", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Recent SMS/bank texts, for learning the account holder's name. */
    fun bankMessages(limit: Int): List<String> =
        r.rawQuery("SELECT text FROM message WHERE kind = 'sms' AND is_self = 0 ORDER BY at DESC LIMIT $limit", null)
            .use { c -> c.map { it.getString(0) } }

    fun resetMoneyChecked(since: Long) {
        w.execSQL("UPDATE message SET money_checked = 0 WHERE at >= ?", arrayOf(since))
    }

    /**
     * The same amount leaving one account and arriving in another within 30 minutes is the
     * user moving their own money: mark both sides as a self transfer.
     */
    fun pairSelfTransfers() {
        val pair = "SELECT a.id FROM txn a JOIN txn b ON a.amount_paise = b.amount_paise AND a.direction != b.direction " +
            "AND abs(a.at - b.at) <= 1800000 AND a.amount_paise >= 50000 " +
            "AND (a.account IS NULL OR b.account IS NULL OR a.account != b.account) " +
            "WHERE a.category NOT IN ('Salary', 'Savings') AND b.category NOT IN ('Salary', 'Savings')"
        w.execSQL("UPDATE txn SET category = 'Self transfer' WHERE id IN ($pair)")
        db.changed()
    }

    /** "This is me": every payment to or from this name is money moving between your own accounts. */
    fun markOwnName(name: String) {
        w.execSQL("UPDATE txn SET category = 'Self transfer' WHERE merchant = ? COLLATE NOCASE", arrayOf(name))
        w.insertWithOnConflict("merchant_category", null, ContentValues().apply {
            put("merchant", name); put("category", "Self transfer"); put("source", "user"); put("at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.changed()
    }

    fun clearTxns() {
        w.delete("txn", null, null)
        db.changed()
    }

    fun txnsBetween(start: Long, end: Long): List<Txn> =
        r.rawQuery("SELECT $TXN_COLS FROM txn WHERE at >= ? AND at < ? ORDER BY at DESC", arrayOf(start.toString(), end.toString()))
            .use { c -> c.map { it.toTxn() } }

    /** (spent, received) in paise. */
    fun moneyTotals(start: Long, end: Long): Pair<Long, Long> =
        r.rawQuery(
            "SELECT COALESCE(SUM(CASE WHEN direction='out' THEN amount_paise END),0), " +
                "COALESCE(SUM(CASE WHEN direction='in' THEN amount_paise END),0) FROM txn WHERE at >= ? AND at < ? AND category NOT IN ('Savings', 'Self transfer')",
            arrayOf(start.toString(), end.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }

    fun spentMatching(term: String, start: Long, end: Long): List<Txn> =
        r.rawQuery(
            "SELECT $TXN_COLS FROM txn WHERE direction = 'out' AND at >= ? AND at < ? AND (merchant LIKE ? OR category LIKE ?) ORDER BY at DESC",
            arrayOf(start.toString(), end.toString(), "%$term%", "%$term%"),
        ).use { c -> c.map { it.toTxn() } }

    /** Every payment to or from one merchant or person, newest first. */
    fun txnsForMerchant(merchant: String): List<Txn> =
        r.rawQuery("SELECT $TXN_COLS FROM txn WHERE merchant = ? COLLATE NOCASE ORDER BY at DESC", arrayOf(merchant))
            .use { c -> c.map { it.toTxn() } }

    fun merchants(): List<String> =
        r.rawQuery("SELECT DISTINCT merchant FROM txn WHERE merchant IS NOT NULL", null)
            .use { c -> c.map { it.getString(0) } }

    fun firstTxnAt(): Long? =
        r.rawQuery("SELECT MIN(at) FROM txn", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    // ---------------------------------------------------------------- learning

    fun upsertProfile(p: Profile) {
        w.insertWithOnConflict("person_profile", null, ContentValues().apply {
            put("person_key", p.personKey); put("display", p.display); put("pkg", p.pkg)
            put("msgs_in", p.msgsIn); put("replies", p.replies); put("median_reply_s", p.medianReplyS)
            put("misses", p.misses); put("importance", p.importance); put("updated_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun profile(personKey: String): Profile? =
        r.rawQuery(
            "SELECT person_key, display, pkg, msgs_in, replies, median_reply_s, misses, importance FROM person_profile WHERE person_key = ?",
            arrayOf(personKey),
        ).use { c -> if (c.moveToFirst()) c.toProfile() else null }

    fun profiles(): List<Profile> =
        r.rawQuery("SELECT person_key, display, pkg, msgs_in, replies, median_reply_s, misses, importance FROM person_profile ORDER BY msgs_in DESC", null)
            .use { c -> c.map { it.toProfile() } }

    private fun Cursor.toProfile() = Profile(
        getString(0), getString(1), getString(2), getInt(3), getInt(4), getLongOrNull(5), getInt(6), getDouble(7),
    )

    fun saveWeights(weights: DoubleArray, n: Int) {
        w.insertWithOnConflict("urgency_model", null, ContentValues().apply {
            put("id", 1); put("weights", JSONArray(weights.toList()).toString()); put("n", n); put("trained_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadWeights(): Pair<DoubleArray, Int>? =
        r.rawQuery("SELECT weights, n FROM urgency_model WHERE id = 1", null).use { c ->
            if (!c.moveToFirst()) null
            else JSONArray(c.getString(0)).let { a -> DoubleArray(a.length()) { a.getDouble(it) } } to c.getInt(1)
        }

    fun upsertFact(key: String, kind: String, text: String) {
        val existing = r.rawQuery("SELECT removed FROM memory_fact WHERE key = ?", arrayOf(key))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        when (existing) {
            null -> w.insert("memory_fact", null, ContentValues().apply {
                put("key", key); put("kind", kind); put("text", text); put("created_at", now()); put("updated_at", now())
            })
            0 -> w.update("memory_fact", ContentValues().apply { put("text", text); put("updated_at", now()) }, "key = ?", arrayOf(key))
            else -> Unit // the user removed this one; never bring it back
        }
    }

    fun pruneFacts(keep: Set<String>) {
        val stale = r.rawQuery("SELECT key FROM memory_fact WHERE removed = 0", null)
            .use { c -> c.map { it.getString(0) } }
            .filter { it !in keep }
        stale.forEach { w.delete("memory_fact", "key = ?", arrayOf(it)) }
        db.changed()
    }

    fun removeFact(id: Long) {
        w.update("memory_fact", ContentValues().apply { put("removed", 1) }, "id = ?", arrayOf(id.toString()))
        db.changed()
    }

    fun facts(): List<Fact> =
        r.rawQuery("SELECT id, key, kind, text, created_at FROM memory_fact WHERE removed = 0 ORDER BY kind, updated_at DESC", null)
            .use { c -> c.map { Fact(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) } }

    fun removedFactKeys(): Set<String> =
        r.rawQuery("SELECT key FROM memory_fact WHERE removed = 1", null).use { c -> c.map { it.getString(0) }.toSet() }

    // ---------------------------------------------------------------- digest

    fun saveDigest(d: Digest, modelMs: Long?) {
        w.insertWithOnConflict("digest", null, ContentValues().apply {
            put("date", d.date); put("created_at", d.createdAt); put("summary", d.summary)
            put("noticed", JSONArray(d.noticed).toString()); put("urgent", d.urgent); put("waiting", d.waiting)
            put("spent", d.spentPaise); put("received", d.receivedPaise); put("total", d.total)
            put("apps", JSONArray(d.apps.map { "${it.first}\u001f${it.second}" }).toString())
            put("lines", JSONArray(d.lines).toString()); put("model", d.model); put("model_ms", modelMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.changed()
    }

    fun digest(date: String): Digest? =
        r.rawQuery(
            "SELECT date, created_at, summary, noticed, urgent, waiting, spent, received, total, apps, lines, model FROM digest WHERE date = ?",
            arrayOf(date),
        ).use { c -> if (c.moveToFirst()) c.toDigest() else null }

    fun latestDigest(): Digest? =
        r.rawQuery(
            "SELECT date, created_at, summary, noticed, urgent, waiting, spent, received, total, apps, lines, model FROM digest ORDER BY date DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.toDigest() else null }

    private fun Cursor.toDigest() = Digest(
        date = getString(0), createdAt = getLong(1), summary = getString(2), noticed = jsonList(getString(3)),
        urgent = getInt(4), waiting = getInt(5), spentPaise = getLong(6), receivedPaise = getLong(7), total = getInt(8),
        apps = jsonList(getString(9)).map { s -> s.split('\u001f').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) } },
        lines = jsonList(getString(10)), model = getStringOrNull(11),
    )

    // ---------------------------------------------------------------- chat

    fun addChat(role: String, text: String, cites: List<String> = emptyList()): Long {
        val id = w.insert("chat_msg", null, ContentValues().apply {
            put("role", role); put("text", text); put("cites", JSONArray(cites).toString()); put("at", now())
        })
        db.changed()
        return id
    }

    fun chat(limit: Int = 200): List<ChatMsg> =
        r.rawQuery("SELECT id, role, text, cites, at FROM chat_msg ORDER BY id DESC LIMIT $limit", null)
            .use { c -> c.map { ChatMsg(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3)?.let(::jsonList) ?: emptyList(), it.getLong(4)) } }
            .reversed()

    fun clearChat() {
        w.delete("chat_msg", null, null)
        db.changed()
    }

    // ---------------------------------------------------------------- network log

    fun logNet(host: String, path: String, bytes: Long, status: Int) {
        w.insert("net_log", null, ContentValues().apply {
            put("host", host); put("path", path); put("bytes", bytes); put("status", status); put("at", now())
        })
        db.changed()
    }

    fun netLog(): List<NetEntry> =
        r.rawQuery("SELECT id, host, path, bytes, status, at FROM net_log ORDER BY id DESC LIMIT 500", null)
            .use { c -> c.map { NetEntry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getInt(4), it.getLong(5)) } }

    // ---------------------------------------------------------------- retention

    /** Messages are kept a year. Learned facts, digests and money are kept. */
    fun retention(now: Long) {
        val cutoff = now - 365 * DAY
        w.execSQL("DELETE FROM message_fts WHERE docid IN (SELECT id FROM message WHERE at < ?)", arrayOf(cutoff))
        w.execSQL("DELETE FROM message WHERE at < ?", arrayOf(cutoff))
        w.execSQL("DELETE FROM signal WHERE at < ?", arrayOf(now - 180 * DAY))
        db.changed()
    }

    const val DAY = 24 * 60 * 60 * 1000L
}

// ---------------------------------------------------------------- cursor helpers

inline fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out.add(f(this))
    return out
}

fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
fun Cursor.getIntOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

fun jsonList(s: String): List<String> = try {
    JSONArray(s).let { a -> List(a.length()) { a.getString(it) } }
} catch (e: Exception) {
    emptyList()
}
