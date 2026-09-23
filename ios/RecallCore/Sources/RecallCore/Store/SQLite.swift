import Foundation
import SQLite3

/// A small wrapper over SQLite's C API. The app, its Shortcuts actions and the widget all
/// open the same file, so it runs in WAL mode with a busy timeout.
public final class SQLiteDB: @unchecked Sendable {
    public enum Value: Equatable, Sendable {
        case int(Int64), double(Double), text(String), null
    }

    public struct Row: Sendable {
        let values: [String: Value]
        public func int(_ k: String) -> Int64 { if case let .int(v) = values[k] { return v }; if case let .double(d) = values[k] { return Int64(d) }; return 0 }
        public func intOrNil(_ k: String) -> Int64? { if case let .int(v) = values[k] { return v }; return nil }
        public func double(_ k: String) -> Double { if case let .double(v) = values[k] { return v }; if case let .int(i) = values[k] { return Double(i) }; return 0 }
        public func text(_ k: String) -> String { if case let .text(v) = values[k] { return v }; return "" }
        public func textOrNil(_ k: String) -> String? { if case let .text(v) = values[k] { return v }; return nil }
        public func bool(_ k: String) -> Bool { int(k) != 0 }
    }

    public struct Failure: Error, CustomStringConvertible { public let description: String }

    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    private var depth = 0
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    public init(path: String) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &db, flags, nil) == SQLITE_OK else {
            throw Failure(description: "open \(path): \(String(cString: sqlite3_errmsg(db)))")
        }
        sqlite3_busy_timeout(db, 4000)
        try exec("PRAGMA journal_mode=WAL")
        try exec("PRAGMA foreign_keys=ON")
    }

    deinit { sqlite3_close_v2(db) }

    public func exec(_ sql: String) throws {
        try locked {
            var err: UnsafeMutablePointer<CChar>?
            if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
                let msg = err.map { String(cString: $0) } ?? "unknown"
                sqlite3_free(err)
                throw Failure(description: "\(msg) in: \(sql.prefix(120))")
            }
        }
    }

    /// Runs a statement; returns the last insert id, or nil when nothing was inserted (e.g. OR IGNORE).
    @discardableResult
    public func run(_ sql: String, _ args: [Value] = []) throws -> Int64? {
        try locked {
            let stmt = try prepare(sql, args)
            defer { sqlite3_finalize(stmt) }
            let rc = sqlite3_step(stmt)
            guard rc == SQLITE_DONE || rc == SQLITE_ROW else {
                throw Failure(description: "\(String(cString: sqlite3_errmsg(db))) in: \(sql.prefix(120))")
            }
            return sqlite3_changes(db) > 0 ? sqlite3_last_insert_rowid(db) : nil
        }
    }

    public func query(_ sql: String, _ args: [Value] = []) throws -> [Row] {
        try locked {
            let stmt = try prepare(sql, args)
            defer { sqlite3_finalize(stmt) }
            var rows: [Row] = []
            while true {
                let rc = sqlite3_step(stmt)
                if rc == SQLITE_DONE { break }
                guard rc == SQLITE_ROW else { throw Failure(description: String(cString: sqlite3_errmsg(db))) }
                var values: [String: Value] = [:]
                for i in 0..<sqlite3_column_count(stmt) {
                    let name = String(cString: sqlite3_column_name(stmt, i))
                    switch sqlite3_column_type(stmt, i) {
                    case SQLITE_INTEGER: values[name] = .int(sqlite3_column_int64(stmt, i))
                    case SQLITE_FLOAT: values[name] = .double(sqlite3_column_double(stmt, i))
                    case SQLITE_TEXT: values[name] = .text(String(cString: sqlite3_column_text(stmt, i)))
                    default: values[name] = .null
                    }
                }
                rows.append(Row(values: values))
            }
            return rows
        }
    }

    public func scalar(_ sql: String, _ args: [Value] = []) throws -> Int64 {
        try query(sql, args).first.map { $0.values.values.first.flatMap { if case let .int(v) = $0 { return v }; return nil } ?? 0 } ?? 0
    }

    /// Nested calls join the outer transaction (SQLite has no nested BEGIN).
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        try locked {
            if depth > 0 {
                depth += 1; defer { depth -= 1 }
                return try body()
            }
            try exec("BEGIN IMMEDIATE")
            depth = 1
            defer { depth = 0 }
            do {
                let r = try body()
                try exec("COMMIT")
                return r
            } catch {
                try? exec("ROLLBACK")
                throw error
            }
        }
    }

    public var userVersion: Int {
        get { Int((try? scalar("PRAGMA user_version")) ?? 0) }
        set { try? exec("PRAGMA user_version = \(newValue)") }
    }

    private func prepare(_ sql: String, _ args: [Value]) throws -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw Failure(description: "\(String(cString: sqlite3_errmsg(db))) in: \(sql.prefix(120))")
        }
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case let .int(v): sqlite3_bind_int64(stmt, idx, v)
            case let .double(v): sqlite3_bind_double(stmt, idx, v)
            case let .text(v): sqlite3_bind_text(stmt, idx, v, -1, Self.transient)
            case .null: sqlite3_bind_null(stmt, idx)
            }
        }
        return stmt
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }
        return try body()
    }
}

public extension SQLiteDB.Value {
    static func of(_ s: String?) -> Self { s.map { .text($0) } ?? .null }
    static func of(_ i: Int64?) -> Self { i.map { .int($0) } ?? .null }
    static func of(_ i: Int) -> Self { .int(Int64(i)) }
    static func of(_ b: Bool) -> Self { .int(b ? 1 : 0) }
}
