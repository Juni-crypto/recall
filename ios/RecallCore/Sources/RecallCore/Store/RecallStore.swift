import Foundation

/// Everything Recall keeps, in one SQLite file. Same shape as the Android app's database.
public final class RecallStore: @unchecked Sendable {
    public let db: SQLiteDB

    /// Posted after every write, so screens and the widget can refresh.
    public static let changed = Notification.Name("RecallStoreChanged")

    public init(path: String) throws {
        db = try SQLiteDB(path: path)
        try migrate()
    }

    /// In-memory store for tests and previews.
    public static func inMemory() throws -> RecallStore { try RecallStore(path: ":memory:") }

    private func migrate() throws {
        guard db.userVersion < 1 else { return }
        try db.exec("""
        CREATE TABLE IF NOT EXISTS message(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            hash TEXT NOT NULL UNIQUE,
            source TEXT NOT NULL,
            conv_key TEXT NOT NULL,
            conv_title TEXT,
            sender TEXT,
            text TEXT NOT NULL,
            is_self INTEGER NOT NULL DEFAULT 0,
            is_group INTEGER NOT NULL DEFAULT 0,
            at INTEGER NOT NULL,
            triaged INTEGER NOT NULL DEFAULT 0,
            money_checked INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS message_at ON message(at);
        CREATE INDEX IF NOT EXISTS message_conv ON message(conv_key, at);
        CREATE INDEX IF NOT EXISTS message_triage ON message(triaged, at);
        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(sender, conv_title, text);
        CREATE TABLE IF NOT EXISTS txn(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            amount_paise INTEGER NOT NULL,
            direction TEXT NOT NULL,
            merchant TEXT,
            category TEXT NOT NULL,
            account TEXT,
            source TEXT NOT NULL,
            msg_id INTEGER,
            raw TEXT NOT NULL,
            at INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS txn_at ON txn(at);
        CREATE TABLE IF NOT EXISTS merchant_category(
            merchant TEXT PRIMARY KEY COLLATE NOCASE,
            category TEXT NOT NULL,
            by TEXT NOT NULL,
            at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS open_loop(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conv_key TEXT NOT NULL,
            source TEXT NOT NULL,
            person TEXT NOT NULL,
            ask_text TEXT NOT NULL,
            first_msg_id INTEGER,
            first_at INTEGER NOT NULL,
            last_at INTEGER NOT NULL,
            asks INTEGER NOT NULL DEFAULT 1,
            urgency INTEGER NOT NULL DEFAULT 0,
            due_hint TEXT,
            state TEXT NOT NULL DEFAULT 'open',
            closed_by TEXT,
            closed_at INTEGER,
            snooze_until INTEGER,
            ai INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS open_loop_state ON open_loop(state, conv_key);
        CREATE TABLE IF NOT EXISTS fact(
            key TEXT PRIMARY KEY,
            topic TEXT NOT NULL,
            text TEXT NOT NULL,
            at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS digest(
            day TEXT PRIMARY KEY,
            at INTEGER NOT NULL,
            summary TEXT NOT NULL,
            spent INTEGER NOT NULL,
            received INTEGER NOT NULL,
            waiting INTEGER NOT NULL,
            urgent INTEGER NOT NULL,
            model TEXT);
        CREATE TABLE IF NOT EXISTS chat(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            sources TEXT,
            at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS kv(
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL);
        """)
        db.userVersion = 1
    }

    private func changed() {
        NotificationCenter.default.post(name: Self.changed, object: nil)
    }

    // MARK: - Messages

    private static let msgCols = "m.id, m.source, m.conv_key, m.conv_title, m.sender, m.text, m.is_self, m.is_group, m.at"

    private func message(_ r: SQLiteDB.Row) -> Message {
        Message(
            id: r.int("id"), source: Source(rawValue: r.text("source")) ?? .note, convKey: r.text("conv_key"),
            convTitle: r.textOrNil("conv_title"), sender: r.textOrNil("sender"), text: r.text("text"),
            isSelf: r.bool("is_self"), isGroup: r.bool("is_group"), at: r.int("at"),
        )
    }

    /// Stores a message; nil if it was already stored.
    @discardableResult
    public func insertMessage(_ c: Captured) throws -> Int64? {
        let id: Int64? = try db.transaction {
            guard let id = try db.run(
                "INSERT OR IGNORE INTO message(hash, source, conv_key, conv_title, sender, text, is_self, is_group, at) VALUES (?,?,?,?,?,?,?,?,?)",
                [.text(c.hash), .text(c.source.rawValue), .text(c.convKey), .of(c.convTitle), .of(c.sender), .text(c.text), .of(c.isSelf), .of(c.isGroup), .int(c.at)],
            ) else { return nil }
            try db.run("INSERT INTO message_fts(docid, sender, conv_title, text) VALUES (?,?,?,?)",
                       [.int(id), .text(c.sender ?? ""), .text(c.convTitle ?? ""), .text(c.text)])
            return id
        }
        if id != nil { changed() }
        return id
    }

    public func recentMessages(limit: Int = 400) -> [Message] {
        (try? db.query("SELECT \(Self.msgCols) FROM message m ORDER BY m.at DESC LIMIT \(limit)").map(message)) ?? []
    }

    public func messages(from start: Int64, to end: Int64, includeSelf: Bool = true, limit: Int = 2000) -> [Message] {
        let selfClause = includeSelf ? "" : " AND m.is_self = 0"
        return (try? db.query("SELECT \(Self.msgCols) FROM message m WHERE m.at >= ? AND m.at < ?\(selfClause) ORDER BY m.at ASC LIMIT \(limit)",
                              [.int(start), .int(end)]).map(message)) ?? []
    }

    public func messagesForConv(_ convKey: String, since: Int64) -> [Message] {
        (try? db.query("SELECT \(Self.msgCols) FROM message m WHERE m.conv_key = ? AND m.at >= ? ORDER BY m.at ASC",
                       [.text(convKey), .int(since)]).map(message)) ?? []
    }

    public func message(id: Int64) -> Message? {
        try? db.query("SELECT \(Self.msgCols) FROM message m WHERE m.id = ?", [.int(id)]).first.map(message)
    }

    /// Full-text search. `terms` are ANDed; each gets a prefix match.
    public func search(_ terms: [String], from start: Int64 = 0, to end: Int64 = .max, limit: Int = 60) -> [Message] {
        let q = terms.map { $0.replacingOccurrences(of: "\"", with: "") }.filter { !$0.isEmpty }.map { "\"\($0)\"*" }.joined(separator: " ")
        guard !q.isEmpty else { return [] }
        return (try? db.query(
            "SELECT \(Self.msgCols) FROM message_fts f JOIN message m ON m.id = f.docid WHERE message_fts MATCH ? AND m.at >= ? AND m.at < ? ORDER BY m.at DESC LIMIT \(limit)",
            [.text(q), .int(start), .int(end)],
        ).map(message)) ?? []
    }

    public func countMessages(from start: Int64, to end: Int64) -> Int {
        Int((try? db.scalar("SELECT COUNT(*) FROM message WHERE at >= ? AND at < ?", [.int(start), .int(end)])) ?? 0)
    }

    /// (source, count) for a period, busiest first.
    public func sourceCounts(from start: Int64, to end: Int64) -> [(Source, Int)] {
        (try? db.query("SELECT source, COUNT(*) AS n FROM message WHERE at >= ? AND at < ? GROUP BY source ORDER BY n DESC", [.int(start), .int(end)])
            .compactMap { r in Source(rawValue: r.text("source")).map { ($0, Int(r.int("n"))) } }) ?? []
    }

    public func lastIncoming(convKey: String, before: Int64) -> Int64? {
        try? db.query("SELECT MAX(at) AS at FROM message WHERE conv_key = ? AND is_self = 0 AND at < ?", [.text(convKey), .int(before)]).first?.intOrNil("at")
    }

    public func countIncoming(convKey: String, since: Int64) -> Int {
        Int((try? db.scalar("SELECT COUNT(*) FROM message WHERE conv_key = ? AND is_self = 0 AND at >= ?", [.text(convKey), .int(since)])) ?? 0)
    }

    /// (conv key, name) of everyone who has messaged you, for questions like "what did Rahul want".
    public func people() -> [(String, String)] {
        (try? db.query("SELECT conv_key, COALESCE(MAX(conv_title), MAX(sender)) AS t FROM message WHERE is_self = 0 GROUP BY conv_key HAVING t IS NOT NULL")
            .map { ($0.text("conv_key"), $0.text("t")) }) ?? []
    }

    public func messages(convKeys: [String], from start: Int64, to end: Int64, limit: Int) -> [Message] {
        guard !convKeys.isEmpty else { return [] }
        let marks = Array(repeating: "?", count: convKeys.count).joined(separator: ",")
        let rows = (try? db.query("SELECT \(Self.msgCols) FROM message m WHERE m.conv_key IN (\(marks)) AND m.at >= ? AND m.at < ? ORDER BY m.at DESC LIMIT \(limit)",
                                  convKeys.map { .text($0) } + [.int(start), .int(end)])) ?? []
        return rows.map(message).reversed()
    }

    public func merchants() -> [String] {
        (try? db.query("SELECT DISTINCT merchant FROM txn WHERE merchant IS NOT NULL").map { $0.text("merchant") }) ?? []
    }

    /// Recent bank SMS text, for learning the account holder's name.
    public func recentBankTexts(limit: Int = 400) -> [String] {
        (try? db.query("SELECT text FROM message WHERE source = 'sms' AND is_self = 0 ORDER BY at DESC LIMIT \(limit)").map { $0.text("text") }) ?? []
    }

    /// A payment out and one in, same amount, within 30 minutes: money moved between your own accounts.
    public func pairSelfTransfers() {
        try? db.exec("""
        UPDATE txn SET category = 'Self transfer' WHERE category NOT IN ('Savings','Self transfer') AND id IN (
          SELECT a.id FROM txn a JOIN txn b ON a.amount_paise = b.amount_paise AND a.direction <> b.direction
            AND ABS(a.at - b.at) <= 1800000 AND b.category NOT IN ('Savings'))
        """)
        changed()
    }

    // MARK: - Model review queue

    public func triageCandidates(since: Int64, limit: Int) -> [Message] {
        (try? db.query("SELECT \(Self.msgCols) FROM message m WHERE m.triaged = 0 AND m.is_self = 0 AND m.at >= ? ORDER BY m.at ASC LIMIT \(limit)",
                       [.int(since)]).map(message)) ?? []
    }

    public func pendingTriage(since: Int64) -> Int {
        Int((try? db.scalar("SELECT COUNT(*) FROM message WHERE triaged = 0 AND is_self = 0 AND at >= ?", [.int(since)])) ?? 0)
    }

    public func markTriaged(_ ids: [Int64], value: Int) {
        guard !ids.isEmpty else { return }
        try? db.exec("UPDATE message SET triaged = \(value) WHERE id IN (\(ids.map(String.init).joined(separator: ",")))")
    }

    public func moneyCandidates(since: Int64, limit: Int) -> [Message] {
        (try? db.query(
            "SELECT \(Self.msgCols) FROM message m WHERE m.money_checked = 0 AND m.is_self = 0 AND m.at >= ? AND m.source IN ('sms','mail') " +
                "AND NOT EXISTS (SELECT 1 FROM txn t WHERE t.msg_id = m.id) ORDER BY m.at ASC LIMIT \(limit)",
            [.int(since)],
        ).map(message)) ?? []
    }

    public func markMoneyChecked(_ ids: [Int64]) {
        guard !ids.isEmpty else { return }
        try? db.exec("UPDATE message SET money_checked = 1 WHERE id IN (\(ids.map(String.init).joined(separator: ",")))")
    }

    // MARK: - Money

    private static let txnCols = "id, amount_paise, direction, merchant, category, account, source, msg_id, raw, at"

    private func txn(_ r: SQLiteDB.Row) -> Txn {
        Txn(id: r.int("id"), amountPaise: r.int("amount_paise"), direction: r.text("direction"), merchant: r.textOrNil("merchant"),
            category: r.text("category"), account: r.textOrNil("account"), source: r.text("source"), msgId: r.intOrNil("msg_id"),
            raw: r.text("raw"), at: r.int("at"))
    }

    /// The same payment often arrives twice (bank SMS + UPI app); within 10 minutes they're merged.
    public func insertOrMergeTxn(amountPaise: Int64, direction: String, merchant: String?, category: String, account: String?, source: String, msgId: Int64?, raw: String, at: Int64) {
        let window: Int64 = 10 * 60 * 1000
        if let existing = try? db.query("SELECT \(Self.txnCols) FROM txn WHERE amount_paise = ? AND direction = ? AND at BETWEEN ? AND ? LIMIT 1",
                                        [.int(amountPaise), .text(direction), .int(at - window), .int(at + window)]).first.map(txn) {
            if existing.merchant == nil, let merchant {
                _ = try? db.run("UPDATE txn SET merchant = ?, category = ? WHERE id = ?", [.text(merchant), .text(category), .int(existing.id)])
            }
            if existing.account == nil, let account {
                _ = try? db.run("UPDATE txn SET account = ? WHERE id = ?", [.text(account), .int(existing.id)])
            }
        } else {
            _ = try? db.run("INSERT INTO txn(amount_paise, direction, merchant, category, account, source, msg_id, raw, at) VALUES (?,?,?,?,?,?,?,?,?)",
                        [.int(amountPaise), .text(direction), .of(merchant), .text(category), .of(account), .text(source), .of(msgId), .text(String(raw.prefix(400))), .int(at)])
        }
        changed()
    }

    public func hasTxn(msgId: Int64) -> Bool {
        ((try? db.scalar("SELECT COUNT(*) FROM txn WHERE msg_id = ?", [.int(msgId)])) ?? 0) > 0
    }

    public func clearTxns() { try? db.exec("DELETE FROM txn"); changed() }

    /// (spent, received) in paise, leaving out savings and moves between your own accounts.
    public func moneyTotals(from start: Int64, to end: Int64) -> (Int64, Int64) {
        guard let r = try? db.query(
            "SELECT COALESCE(SUM(CASE WHEN direction='out' THEN amount_paise END),0) AS spent, COALESCE(SUM(CASE WHEN direction='in' THEN amount_paise END),0) AS got " +
                "FROM txn WHERE at >= ? AND at < ? AND category NOT IN ('Savings','Self transfer')",
            [.int(start), .int(end)],
        ).first else { return (0, 0) }
        return (r.int("spent"), r.int("got"))
    }

    public func savingsTotal(from start: Int64, to end: Int64) -> Int64 {
        (try? db.scalar("SELECT COALESCE(SUM(amount_paise),0) FROM txn WHERE direction='out' AND category='Savings' AND at >= ? AND at < ?", [.int(start), .int(end)])) ?? 0
    }

    public func txns(from start: Int64, to end: Int64, direction: String? = nil, category: String? = nil, limit: Int = 500) -> [Txn] {
        var sql = "SELECT \(Self.txnCols) FROM txn WHERE at >= ? AND at < ?"
        var args: [SQLiteDB.Value] = [.int(start), .int(end)]
        if let direction { sql += " AND direction = ?"; args.append(.text(direction)) }
        if let category { sql += " AND category = ?"; args.append(.text(category)) }
        sql += " ORDER BY at DESC LIMIT \(limit)"
        return (try? db.query(sql, args).map(txn)) ?? []
    }

    public func categoryTotals(from start: Int64, to end: Int64, direction: String = "out") -> [CategoryTotal] {
        (try? db.query(
            "SELECT category, SUM(amount_paise) AS p, COUNT(*) AS n FROM txn WHERE direction = ? AND at >= ? AND at < ? AND category NOT IN ('Self transfer') GROUP BY category ORDER BY p DESC",
            [.text(direction), .int(start), .int(end)],
        ).map { CategoryTotal(category: $0.text("category"), paise: $0.int("p"), count: Int($0.int("n"))) }) ?? []
    }

    /// Who you paid most, or who paid you most.
    public func partyTotals(from start: Int64, to end: Int64, direction: String, limit: Int = 8) -> [PartyTotal] {
        (try? db.query(
            "SELECT COALESCE(merchant,'Unknown') AS name, SUM(amount_paise) AS p, COUNT(*) AS n FROM txn WHERE direction = ? AND at >= ? AND at < ? " +
                "AND category NOT IN ('Savings','Self transfer') GROUP BY name COLLATE NOCASE ORDER BY p DESC LIMIT \(limit)",
            [.text(direction), .int(start), .int(end)],
        ).map { PartyTotal(name: $0.text("name"), paise: $0.int("p"), count: Int($0.int("n"))) }) ?? []
    }

    /// Spent per day across [start, end), for the comparison chart. Index 0 is the first day.
    public func dailySpend(from start: Int64, days: Int, direction: String = "out", calendar: Calendar = .current) -> [Int64] {
        var out = [Int64](repeating: 0, count: days)
        let rows = (try? db.query(
            "SELECT at, amount_paise FROM txn WHERE direction = ? AND at >= ? AND at < ? AND category NOT IN ('Savings','Self transfer')",
            [.text(direction), .int(start), .int(start + Int64(days + 1) * Clock.day)],
        )) ?? []
        let startDay = calendar.startOfDay(for: Clock.date(start))
        for r in rows {
            let d = calendar.dateComponents([.day], from: startDay, to: calendar.startOfDay(for: Clock.date(r.int("at")))).day ?? -1
            if d >= 0 && d < days { out[d] += r.int("amount_paise") }
        }
        return out
    }

    public func txnsFor(merchant: String) -> [Txn] {
        (try? db.query("SELECT \(Self.txnCols) FROM txn WHERE merchant = ? COLLATE NOCASE ORDER BY at DESC", [.text(merchant)]).map(txn)) ?? []
    }

    public func spentMatching(_ term: String, from start: Int64, to end: Int64) -> [Txn] {
        (try? db.query("SELECT \(Self.txnCols) FROM txn WHERE direction = 'out' AND at >= ? AND at < ? AND (merchant LIKE ? OR category LIKE ?) ORDER BY at DESC",
                       [.int(start), .int(end), .text("%\(term)%"), .text("%\(term)%")]).map(txn)) ?? []
    }

    /// (category, "user" or "model")
    public func merchantCategory(_ merchant: String) -> (String, String)? {
        guard let r = try? db.query("SELECT category, by FROM merchant_category WHERE merchant = ?", [.text(merchant)]).first else { return nil }
        return (r.text("category"), r.text("by"))
    }

    public func setMerchantCategory(_ merchant: String, category: String, by: String) {
        _ = try? db.run("INSERT OR REPLACE INTO merchant_category(merchant, category, by, at) VALUES (?,?,?,?)", [.text(merchant), .text(category), .text(by), .int(Clock.now)])
        // The user's choice applies to every payment to this merchant; the model's only fills gaps.
        let scope = by == "user" ? "" : " AND category = 'Other'"
        _ = try? db.run("UPDATE txn SET category = ? WHERE merchant = ? COLLATE NOCASE\(scope)", [.text(category), .text(merchant)])
        changed()
    }

    // MARK: - Waiting on you

    private static let loopCols = "id, conv_key, source, person, ask_text, first_msg_id, first_at, last_at, asks, urgency, due_hint, state, ai"

    private func loop(_ r: SQLiteDB.Row) -> OpenLoop {
        OpenLoop(id: r.int("id"), convKey: r.text("conv_key"), source: Source(rawValue: r.text("source")) ?? .note, person: r.text("person"),
                 askText: r.text("ask_text"), firstMsgId: r.intOrNil("first_msg_id"), firstAt: r.int("first_at"), lastAt: r.int("last_at"),
                 asks: Int(r.int("asks")), urgency: Int(r.int("urgency")), dueHint: r.textOrNil("due_hint"), state: r.text("state"), ai: r.bool("ai"))
    }

    /// Open and not snoozed, most urgent first.
    public func openLoops() -> [OpenLoop] {
        (try? db.query("SELECT \(Self.loopCols) FROM open_loop WHERE state = 'open' AND (snooze_until IS NULL OR snooze_until < ?) ORDER BY urgency DESC, last_at DESC",
                       [.int(Clock.now)]).map(loop)) ?? []
    }

    public func openLoop(convKey: String) -> OpenLoop? {
        try? db.query("SELECT \(Self.loopCols) FROM open_loop WHERE conv_key = ? AND state = 'open' LIMIT 1", [.text(convKey)]).first.map(loop)
    }

    public func loops(from start: Int64, to end: Int64) -> [OpenLoop] {
        (try? db.query("SELECT \(Self.loopCols) FROM open_loop WHERE first_at >= ? AND first_at < ?", [.int(start), .int(end)]).map(loop)) ?? []
    }

    @discardableResult
    public func insertLoop(convKey: String, source: Source, person: String, ask: String, msgId: Int64?, at: Int64, urgency: Int, due: String?, ai: Bool) -> Int64? {
        let id = try? db.run(
            "INSERT INTO open_loop(conv_key, source, person, ask_text, first_msg_id, first_at, last_at, urgency, due_hint, ai) VALUES (?,?,?,?,?,?,?,?,?,?)",
            [.text(convKey), .text(source.rawValue), .text(person), .text(ask), .of(msgId), .int(at), .int(at), .of(urgency), .of(due), .of(ai)],
        )
        changed()
        return id ?? nil
    }

    /// Another ping in a conversation that's already waiting.
    public func bumpLoop(_ id: Int64, at: Int64, urgency: Int) {
        _ = try? db.run("UPDATE open_loop SET asks = asks + 1, last_at = ?, urgency = MAX(urgency, ?) WHERE id = ?", [.int(at), .of(urgency), .int(id)])
        changed()
    }

    public func updateLoopFromModel(_ id: Int64, ask: String, urgency: Int, due: String?) {
        _ = try? db.run("UPDATE open_loop SET ask_text = ?, urgency = ?, due_hint = ?, ai = 1 WHERE id = ?", [.text(ask), .of(urgency), .of(due), .int(id)])
        changed()
    }

    public func setLoopState(_ id: Int64, state: String, closedBy: String) {
        _ = try? db.run("UPDATE open_loop SET state = ?, closed_by = ?, closed_at = ? WHERE id = ?", [.text(state), .text(closedBy), .int(Clock.now), .int(id)])
        changed()
    }

    public func snoozeLoop(_ id: Int64, until: Int64) {
        _ = try? db.run("UPDATE open_loop SET snooze_until = ? WHERE id = ?", [.int(until), .int(id)])
        changed()
    }

    public func closeLoops(convKey: String, by: String) {
        _ = try? db.run("UPDATE open_loop SET state = 'closed', closed_by = ?, closed_at = ? WHERE conv_key = ? AND state = 'open'", [.text(by), .int(Clock.now), .text(convKey)])
        changed()
    }

    /// How often you close things with Done vs Not for me, per source: the learned part of urgency.
    public func loopOutcomes(since: Int64) -> [(Source, done: Int, notForMe: Int)] {
        (try? db.query(
            "SELECT source, SUM(CASE WHEN closed_by IN ('done','reply') THEN 1 ELSE 0 END) AS d, SUM(CASE WHEN closed_by = 'not_for_me' THEN 1 ELSE 0 END) AS n " +
                "FROM open_loop WHERE first_at >= ? GROUP BY source",
            [.int(since)],
        ).compactMap { r in Source(rawValue: r.text("source")).map { ($0, Int(r.int("d")), Int(r.int("n"))) } }) ?? []
    }

    // MARK: - Facts, digest, chat, settings

    public func facts() -> [Fact] {
        (try? db.query("SELECT key, topic, text, at FROM fact ORDER BY topic, at DESC").map {
            Fact(id: $0.text("key"), topic: $0.text("topic"), text: $0.text("text"), at: $0.int("at"))
        }) ?? []
    }

    public func upsertFact(key: String, topic: String, text: String) {
        _ = try? db.run("INSERT OR REPLACE INTO fact(key, topic, text, at) VALUES (?,?,?,?)", [.text(key), .text(topic), .text(text), .int(Clock.now)])
        changed()
    }

    public func deleteFact(_ key: String) {
        _ = try? db.run("DELETE FROM fact WHERE key = ?", [.text(key)])
        // Remember the deletion so the same fact isn't written again.
        set("fact_forgotten:\(key)", "1")
        changed()
    }

    public func isForgotten(_ key: String) -> Bool { get("fact_forgotten:\(key)") != nil }

    public func saveDigest(_ d: Digest) {
        _ = try? db.run("INSERT OR REPLACE INTO digest(day, at, summary, spent, received, waiting, urgent, model) VALUES (?,?,?,?,?,?,?,?)",
                    [.text(d.day), .int(d.at), .text(d.summary), .int(d.spentPaise), .int(d.receivedPaise), .of(d.waiting), .of(d.urgent), .of(d.model)])
        changed()
    }

    public func digest(day: String) -> Digest? {
        try? db.query("SELECT * FROM digest WHERE day = ?", [.text(day)]).first.map {
            Digest(day: $0.text("day"), at: $0.int("at"), summary: $0.text("summary"), spentPaise: $0.int("spent"), receivedPaise: $0.int("received"),
                   waiting: Int($0.int("waiting")), urgent: Int($0.int("urgent")), model: $0.textOrNil("model"))
        }
    }

    public func chat(limit: Int = 60) -> [ChatTurn] {
        ((try? db.query("SELECT id, role, text, sources, at FROM chat ORDER BY id DESC LIMIT \(limit)")) ?? []).reversed().map {
            ChatTurn(id: $0.int("id"), role: $0.text("role"), text: $0.text("text"),
                     sources: $0.text("sources").split(separator: "\n").map(String.init), at: $0.int("at"))
        }
    }

    @discardableResult
    public func addChat(role: String, text: String, sources: [String] = []) -> Int64? {
        let id = try? db.run("INSERT INTO chat(role, text, sources, at) VALUES (?,?,?,?)", [.text(role), .text(text), .text(sources.joined(separator: "\n")), .int(Clock.now)])
        changed()
        return id ?? nil
    }

    public func clearChat() { try? db.exec("DELETE FROM chat"); changed() }

    public func get(_ key: String) -> String? {
        try? db.query("SELECT value FROM kv WHERE key = ?", [.text(key)]).first?.text("value")
    }

    public func set(_ key: String, _ value: String?) {
        if let value { _ = try? db.run("INSERT OR REPLACE INTO kv(key, value) VALUES (?,?)", [.text(key), .text(value)]) }
        else { _ = try? db.run("DELETE FROM kv WHERE key = ?", [.text(key)]) }
    }

    /// Deletes everything older than a year.
    public func prune(olderThan cutoff: Int64) {
        _ = try? db.run("DELETE FROM message_fts WHERE docid IN (SELECT id FROM message WHERE at < ?)", [.int(cutoff)])
        _ = try? db.run("DELETE FROM message WHERE at < ?", [.int(cutoff)])
    }

    public func wipe() {
        try? db.exec("DELETE FROM message; DELETE FROM message_fts; DELETE FROM txn; DELETE FROM open_loop; DELETE FROM fact; DELETE FROM digest; DELETE FROM chat; DELETE FROM merchant_category; DELETE FROM kv;")
        changed()
    }
}
