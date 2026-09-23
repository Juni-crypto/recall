import Foundation

/// Where a message came from. iOS doesn't let apps read notifications, so everything
/// arrives through Shortcuts automations, imports or the share sheet.
public enum Source: String, Sendable, CaseIterable, Codable {
    case sms, mail, whatsapp, note

    public var label: String {
        switch self {
        case .sms: "Messages"
        case .mail: "Mail"
        case .whatsapp: "WhatsApp"
        case .note: "Note"
        }
    }
}

public struct Captured: Sendable {
    public var hash: String
    public var source: Source
    public var convKey: String
    public var convTitle: String?
    public var sender: String?
    public var text: String
    public var isSelf: Bool
    public var isGroup: Bool
    public var at: Int64

    public init(hash: String, source: Source, convKey: String, convTitle: String?, sender: String?, text: String, isSelf: Bool = false, isGroup: Bool = false, at: Int64) {
        self.hash = hash; self.source = source; self.convKey = convKey; self.convTitle = convTitle
        self.sender = sender; self.text = text; self.isSelf = isSelf; self.isGroup = isGroup; self.at = at
    }
}

public struct Message: Identifiable, Sendable, Hashable {
    public let id: Int64
    public let source: Source
    public let convKey: String
    public let convTitle: String?
    public let sender: String?
    public let text: String
    public let isSelf: Bool
    public let isGroup: Bool
    public let at: Int64

    public var who: String { isSelf ? "You" : (sender ?? convTitle ?? source.label) }
}

public struct Txn: Identifiable, Sendable, Hashable {
    public let id: Int64
    public let amountPaise: Int64
    public let direction: String // "out" or "in"
    public let merchant: String?
    public let category: String
    public let account: String?
    public let source: String
    public let msgId: Int64?
    public let raw: String
    public let at: Int64

    public var isOut: Bool { direction == "out" }
}

public struct OpenLoop: Identifiable, Sendable, Hashable {
    public static let urgentThreshold = 70

    public let id: Int64
    public let convKey: String
    public let source: Source
    public let person: String
    public let askText: String
    public let firstMsgId: Int64?
    public let firstAt: Int64
    public let lastAt: Int64
    public let asks: Int
    public let urgency: Int
    public let dueHint: String?
    public let state: String
    public let ai: Bool

    public var isUrgent: Bool { urgency >= Self.urgentThreshold }
}

public struct Fact: Identifiable, Sendable, Hashable {
    public let id: String
    public let topic: String
    public let text: String
    public let at: Int64
}

public struct Digest: Sendable {
    public let day: String
    public let at: Int64
    public let summary: String
    public let spentPaise: Int64
    public let receivedPaise: Int64
    public let waiting: Int
    public let urgent: Int
    public let model: String?

    public init(day: String, at: Int64, summary: String, spentPaise: Int64, receivedPaise: Int64, waiting: Int, urgent: Int, model: String?) {
        self.day = day; self.at = at; self.summary = summary; self.spentPaise = spentPaise
        self.receivedPaise = receivedPaise; self.waiting = waiting; self.urgent = urgent; self.model = model
    }
}

public struct ChatTurn: Identifiable, Sendable, Hashable {
    public let id: Int64
    public let role: String // "user" or "assistant"
    public let text: String
    public let sources: [String]
    public let at: Int64
}

public struct CategoryTotal: Identifiable, Sendable, Hashable {
    public var id: String { category }
    public let category: String
    public let paise: Int64
    public let count: Int
}

public struct PartyTotal: Identifiable, Sendable, Hashable {
    public var id: String { name }
    public let name: String
    public let paise: Int64
    public let count: Int
}

/// Local-time helpers. All stored times are Unix milliseconds.
public enum Clock {
    public static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    public static let day: Int64 = 86_400_000
    public static let hour: Int64 = 3_600_000

    public static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    public static func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }

    /// [start, end) of the local day containing `date`.
    public static func dayBounds(_ date: Date = Date(), calendar: Calendar = .current) -> (Int64, Int64) {
        let s = calendar.startOfDay(for: date)
        let e = calendar.date(byAdding: .day, value: 1, to: s)!
        return (ms(s), ms(e))
    }

    public static func dayKey(_ date: Date = Date(), calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
}
