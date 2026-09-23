import Foundation

/**
 * Parses a WhatsApp "Export chat" text file into messages. Exports are how old conversations
 * get in. Reading the file (or the .zip with media) and storing the result is the app's job.
 */
public enum WhatsAppImport {
    public static let PKG = "com.whatsapp"

    // "23/09/26, 3:10 pm - Arjun: hi"  or  "[23/09/26, 3:10:22 PM] Arjun: hi"
    private static let LINE = RulePattern(
        #"^‎?\[?(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4}),?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([ap]\.?\s?m\.?)?\]?\s*(?:-\s*)?([^:]{1,60}?):\s(.*)$"#,
        ignoreCase: true
    )
    private static let SKIP = RulePattern(#"^(<media omitted>|<attached:|this message was deleted|you deleted this message|null)"#, ignoreCase: true)

    private struct Raw {
        let a: Int, b: Int, y: Int, h: Int, m: Int, s: Int
        let ampm: String?
        let who: String
        var text: String
    }

    public struct Parsed: Equatable, Hashable, Sendable {
        public let sender: String
        public let text: String
        /** Epoch milliseconds. */
        public let at: Int64
        public let mine: Bool
        public let isGroup: Bool

        public init(sender: String, text: String, at: Int64, mine: Bool, isGroup: Bool) {
            self.sender = sender
            self.text = text
            self.at = at
            self.mine = mine
            self.isGroup = isGroup
        }
    }

    /** The chat name from an export's file name ("WhatsApp Chat with Arjun.txt" -> "Arjun"). */
    public static func chatName(fileName name: String) -> String {
        var s = name
        if let r = s.range(of: "WhatsApp Chat with ") { s = String(s[r.upperBound...]) }
        if let dot = s.lastIndex(of: ".") { s = String(s[..<dot]) }
        if let r = s.range(of: ".txt") { s = String(s[..<r.lowerBound]) }
        s = s.ktTrim()
        return s.isEmpty ? "WhatsApp chat" : s
    }

    /** Pure parser, separate from storage so it can be tested. `selfNames` are the user's own names. */
    public static func parse(_ chat: String, _ text: String, selfNames: [String], zone: TimeZone = .current) -> [Parsed] {
        var raws: [Raw] = []
        for line in text.ktLines() {
            if let m = LINE.find(line) {
                let g = m.groupValues
                raws.append(Raw(
                    a: Int(g[1])!, b: Int(g[2])!, y: Int(g[3])!, h: Int(g[4])!, m: Int(g[5])!, s: Int(g[6]) ?? 0,
                    ampm: g[7].isEmpty ? nil : g[7], who: g[8].ktTrim(), text: g[9]
                ))
            } else if !raws.isEmpty && !line.ktIsBlank {
                raws[raws.count - 1].text += "\n" + line
            }
        }
        if raws.isEmpty { return [] }

        // Day/month order differs by locale; the data tells us which one it is.
        let monthFirst = raws.contains { $0.b > 12 } && !raws.contains { $0.a > 12 }
        var senders: [String] = []
        for r in raws where !senders.contains(r.who) { senders.append(r.who) }
        let names = selfNames.map { $0.ktTrim().lowercased() }.filter { !$0.isEmpty }
        let chatLower = chat.lowercased()
        let me: String?
        if senders.count == 2 && senders.contains(where: { $0.lowercased() == chatLower }) {
            me = senders.first { $0.lowercased() != chatLower }
        } else {
            me = senders.first { names.contains($0.lowercased()) }
        }
        let isGroup = senders.count > 2
        return raws.compactMap { r in
            let body = r.text.ktTrim()
            if body.isEmpty || SKIP.containsMatch(in: body) { return nil }
            let day = monthFirst ? r.b : r.a
            let month = monthFirst ? r.a : r.b
            let year = r.y < 100 ? 2000 + r.y : r.y
            var hour = r.h
            if let ap = r.ampm?.lowercased() {
                if ap.hasPrefix("p") && hour < 12 { hour += 12 }
                if ap.hasPrefix("a") && hour == 12 { hour = 0 }
            }
            guard let at = epochMillis(year, month, day, hour, r.m, r.s, zone) else { return nil }
            return Parsed(sender: r.who, text: body, at: at, mine: me != nil && r.who == me, isGroup: isGroup)
        }
    }

    /** Like java.time LocalDateTime.of(...).atZone(zone): nil for an invalid date; a gap moves later, an overlap takes the earlier offset. */
    static func epochMillis(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int, _ second: Int, _ zone: TimeZone) -> Int64? {
        guard (1...12).contains(month), (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let lengths = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        guard day >= 1 && day <= lengths[month - 1] else { return nil }

        // Days from 1970-01-01 in the proleptic Gregorian calendar.
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146097 + doe - 719468
        let local = Int64(days) * 86400 + Int64(hour * 3600 + minute * 60 + second)

        func offset(_ t: Int64) -> Int64 { Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(t)))) }
        let before = offset(local - 86400)
        let after = offset(local + 86400)
        let seconds: Int64
        if before == after {
            seconds = local - before
        } else if offset(local - before) == before {
            seconds = local - before // valid, or the earlier of two in an overlap
        } else if offset(local - after) == after {
            seconds = local - after
        } else {
            seconds = local - before // gap: later by the gap's length
        }
        return seconds * 1000
    }
}
