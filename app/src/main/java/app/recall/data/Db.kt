package app.recall.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plain SQLite. Everything Recall knows lives in this one file inside the app's private
 * storage. [changes] ticks after every write so screens can re-query.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    fun changed() {
        _changes.value = _changes.value + 1
    }

    val w: SQLiteDatabase get() = writableDatabase

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
        V3.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(MERCHANT_CATEGORY)
        if (oldVersion < 3) {
            V3.forEach(db::execSQL)
            // Don't queue months of old messages for the model; only the last few days.
            db.execSQL("UPDATE message SET triaged = 2, money_checked = 1 WHERE at < ?", arrayOf(System.currentTimeMillis() - 3 * 86_400_000L))
        }
    }

    fun wipe() {
        val tables = listOf(
            "message", "message_fts", "conversation", "signal", "open_loop", "txn",
            "person_profile", "memory_fact", "urgency_model", "digest", "chat_msg", "net_log", "merchant_category",
        )
        w.beginTransaction()
        try {
            tables.forEach { w.execSQL("DELETE FROM $it") }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        changed()
    }

    companion object {
        const val NAME = "recall.db"
        const val VERSION = 3

        /** What category each merchant belongs to, decided by the on-device model or the user. */
        private const val MERCHANT_CATEGORY =
            "CREATE TABLE merchant_category(merchant TEXT PRIMARY KEY, category TEXT NOT NULL, source TEXT NOT NULL, at INTEGER NOT NULL)"

        /** v3: the on-device model reviews messages (triage) and rescues unparsed money alerts. */
        private val V3 = listOf(
            "ALTER TABLE message ADD COLUMN triaged INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE message ADD COLUMN money_checked INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE open_loop ADD COLUMN ai INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX message_triage ON message(triaged, at)",
        )

        private val SCHEMA = listOf(
            """CREATE TABLE message(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hash TEXT NOT NULL UNIQUE,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                conv_key TEXT NOT NULL,
                conv_title TEXT,
                sender TEXT,
                text TEXT NOT NULL,
                is_self INTEGER NOT NULL DEFAULT 0,
                is_group INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL,
                category TEXT,
                at INTEGER NOT NULL,
                sbn_key TEXT,
                ongoing INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX message_at ON message(at)",
            "CREATE INDEX message_conv ON message(conv_key, at)",
            "CREATE VIRTUAL TABLE message_fts USING fts4(sender, conv_title, text, tokenize=unicode61)",
            """CREATE TABLE conversation(
                conv_key TEXT PRIMARY KEY,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT,
                kind TEXT NOT NULL,
                is_group INTEGER NOT NULL DEFAULT 0,
                last_in_at INTEGER,
                last_out_at INTEGER,
                last_text TEXT
            )""",
            """CREATE TABLE signal(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                pkg TEXT,
                conv_key TEXT,
                at INTEGER NOT NULL,
                value REAL,
                ref_id INTEGER
            )""",
            "CREATE INDEX signal_at ON signal(at)",
            "CREATE INDEX signal_conv ON signal(conv_key, at)",
            """CREATE TABLE open_loop(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_key TEXT NOT NULL,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                person TEXT NOT NULL,
                ask_text TEXT NOT NULL,
                first_msg_id INTEGER,
                first_at INTEGER NOT NULL,
                last_at INTEGER NOT NULL,
                asks INTEGER NOT NULL DEFAULT 1,
                urgency INTEGER NOT NULL DEFAULT 0,
                reasons TEXT,
                due_hint TEXT,
                is_call INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'open',
                closed_by TEXT,
                closed_at INTEGER,
                seen_at INTEGER,
                snooze_until INTEGER,
                features TEXT,
                label INTEGER
            )""",
            "CREATE INDEX open_loop_state ON open_loop(state, conv_key)",
            """CREATE TABLE txn(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_paise INTEGER NOT NULL,
                direction TEXT NOT NULL,
                merchant TEXT,
                category TEXT NOT NULL,
                account TEXT,
                sources TEXT NOT NULL,
                pkg TEXT,
                msg_id INTEGER,
                raw TEXT,
                at INTEGER NOT NULL
            )""",
            "CREATE INDEX txn_at ON txn(at)",
            """CREATE TABLE person_profile(
                person_key TEXT PRIMARY KEY,
                display TEXT NOT NULL,
                pkg TEXT NOT NULL,
                msgs_in INTEGER NOT NULL,
                replies INTEGER NOT NULL,
                median_reply_s INTEGER,
                misses INTEGER NOT NULL,
                importance REAL NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
            """CREATE TABLE memory_fact(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                removed INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE urgency_model(
                id INTEGER PRIMARY KEY,
                weights TEXT NOT NULL,
                n INTEGER NOT NULL,
                trained_at INTEGER NOT NULL
            )""",
            """CREATE TABLE digest(
                date TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                summary TEXT NOT NULL,
                noticed TEXT NOT NULL,
                urgent INTEGER NOT NULL,
                waiting INTEGER NOT NULL,
                spent INTEGER NOT NULL,
                received INTEGER NOT NULL,
                total INTEGER NOT NULL,
                apps TEXT NOT NULL,
                lines TEXT NOT NULL,
                model TEXT,
                model_ms INTEGER
            )""",
            """CREATE TABLE chat_msg(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                cites TEXT,
                at INTEGER NOT NULL
            )""",
            MERCHANT_CATEGORY,
            """CREATE TABLE net_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL,
                path TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                status INTEGER NOT NULL,
                at INTEGER NOT NULL
            )""",
        )
    }
}
