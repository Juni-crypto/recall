import Foundation

/**
 * A thin wrapper over NSRegularExpression (ICU) with Kotlin Regex semantics, so the Android
 * patterns copy over nearly verbatim. Android's java.util.regex is ICU too.
 */
public struct RulePattern: @unchecked Sendable {
    public let pattern: String
    private let re: NSRegularExpression
    // The same pattern anchored at both ends, for Kotlin's `matches` (whole input).
    private let whole: NSRegularExpression

    public init(_ pattern: String, ignoreCase: Bool = false) {
        self.pattern = pattern
        let options: NSRegularExpression.Options = ignoreCase ? [.caseInsensitive] : []
        do {
            re = try NSRegularExpression(pattern: pattern, options: options)
            whole = try NSRegularExpression(pattern: "\\A(?:" + pattern + ")\\z", options: options)
        } catch {
            preconditionFailure("Bad pattern \(pattern): \(error)")
        }
    }

    /** One match. `groupValues` follows Kotlin: a group that didn't take part is "". */
    public struct Match {
        public let value: String
        public let range: NSRange
        public let groups: [String?]

        public func group(_ i: Int) -> String? { i < groups.count ? groups[i] : nil }
        public func groupValue(_ i: Int) -> String { group(i) ?? "" }
        public var groupValues: [String] { groups.map { $0 ?? "" } }
    }

    private static func full(_ s: String) -> NSRange { NSRange(location: 0, length: (s as NSString).length) }

    private func wrap(_ r: NSTextCheckingResult, in ns: NSString) -> Match {
        var groups: [String?] = []
        for i in 0..<r.numberOfRanges {
            let g = r.range(at: i)
            groups.append(g.location == NSNotFound ? nil : ns.substring(with: g))
        }
        return Match(value: ns.substring(with: r.range), range: r.range, groups: groups)
    }

    /** Kotlin `containsMatchIn`. */
    public func containsMatch(in s: String) -> Bool {
        re.firstMatch(in: s, options: [], range: Self.full(s)) != nil
    }

    /** Kotlin `matches`: the whole input must match. */
    public func matches(_ s: String) -> Bool {
        whole.firstMatch(in: s, options: [], range: Self.full(s)) != nil
    }

    /** Kotlin `find`. */
    public func find(_ s: String) -> Match? {
        let ns = s as NSString
        return re.firstMatch(in: s, options: [], range: Self.full(s)).map { wrap($0, in: ns) }
    }

    /** Kotlin `findAll`. */
    public func findAll(_ s: String) -> [Match] {
        let ns = s as NSString
        return re.matches(in: s, options: [], range: Self.full(s)).map { wrap($0, in: ns) }
    }

    /** Kotlin `replace` with a literal replacement. */
    public func replace(_ s: String, with replacement: String) -> String {
        re.stringByReplacingMatches(
            in: s, options: [], range: Self.full(s),
            withTemplate: NSRegularExpression.escapedTemplate(for: replacement)
        )
    }

    /** Kotlin `split`: keeps leading and trailing empty parts. */
    public func split(_ s: String) -> [String] {
        let ns = s as NSString
        var parts: [String] = []
        var last = 0
        for m in re.matches(in: s, options: [], range: Self.full(s)) {
            parts.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        parts.append(ns.substring(from: last))
        return parts
    }
}

// Kotlin String and Char helpers the rules rely on. Kotlin counts UTF-16 units, so lengths do too.
extension String {
    var ktLength: Int { utf16.count }

    func ktTrim() -> String { trimmingCharacters(in: .whitespacesAndNewlines) }

    func ktTrimEnd(_ chars: Character...) -> String {
        var s = Substring(self)
        while let c = s.last, chars.contains(c) { s = s.dropLast() }
        return String(s)
    }

    var ktIsBlank: Bool { ktTrim().isEmpty }

    /** Kotlin `lineSequence`: splits on \r\n, \n and \r. */
    func ktLines() -> [String] {
        split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r" || $0 == "\r\n" }).map(String.init)
    }

    /** Kotlin `take(n)` on UTF-16 units, without splitting a character. */
    func ktTake(_ n: Int) -> String {
        var out = ""
        var used = 0
        for c in self {
            let w = String(c).utf16.count
            if used + w > n { break }
            out.append(c)
            used += w
        }
        return out
    }

    func ktCount(_ test: (Unicode.Scalar) -> Bool) -> Int { unicodeScalars.filter(test).count }
    func ktAll(_ test: (Unicode.Scalar) -> Bool) -> Bool { unicodeScalars.allSatisfy(test) }
    func ktAny(_ test: (Unicode.Scalar) -> Bool) -> Bool { unicodeScalars.contains(where: test) }
}

enum KtChar {
    static func isLetter(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }
    static func isDigit(_ c: Unicode.Scalar) -> Bool { c.properties.generalCategory == .decimalNumber }
    static func isUpperCase(_ c: Unicode.Scalar) -> Bool { c.properties.isUppercase }
    static func isLowerCase(_ c: Unicode.Scalar) -> Bool { c.properties.isLowercase }
}
