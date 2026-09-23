import Foundation

/**
 * Starting urgency rules. After enough of the user's reactions, [LearnedModel] blends in a
 * personal model trained on what they actually treat as urgent.
 */
public enum UrgencyScorer {
    private static let STRONG = RulePattern(
        #"\b(urgent(ly)?|asap|a\.s\.a\.p|emergency|immediately|blocking|blocker|blocked|critical|hurry|right now|at the earliest|p0|sev ?1|911)\b"#
    )
    private static let WEAK = RulePattern(#"\b(quick(ly)?|now|important|priority|soon|fast|jaldi|jldi)\b"#)
    private static let DEADLINE = RulePattern(
        #"\b(by\s+(\d{1,2}(:\d{2})?\s*(am|pm)?|today|tonight|tomorrow|eod|eow|noon|evening|morning|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|today|tonight|eod|end of (the )?day|before (the )?(call|meeting|standup)|deadline|in \d+\s*(min|mins|minutes|hr|hrs|hours))\b"#
    )
    private static let DUE = RulePattern(
        #"\b(by\s+\d{1,2}(:\d{2})?\s*(am|pm)?|by\s+(today|tonight|tomorrow|eod|noon|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|today|tonight|eod|end of (the )?day)\b"#
    )

    /** Feature vector layout, shared with the learned model. Index 0 is the bias. */
    public static let F_BIAS = 0
    public static let F_STRONG = 1
    public static let F_WEAK = 2
    public static let F_DEADLINE = 3
    public static let F_REPEAT = 4
    public static let F_IMPORTANCE = 5
    public static let F_QUESTION = 6
    public static let F_GROUP = 7
    public static let F_EMAIL = 8
    public static let F_CALL = 9
    public static let F_EVENING = 10
    public static let F_APP_OPEN_RATE = 11
    public static let N_FEATURES = 12

    public struct Input: Equatable, Sendable {
        public let text: String
        public let pings: Int
        public let importance: Double
        public let isQuestion: Bool
        public let isGroup: Bool
        public let isEmail: Bool
        public let isCall: Bool
        public let isEvening: Bool
        public let appOpenRate: Double
        public let automated: Bool

        public init(
            text: String, pings: Int, importance: Double, isQuestion: Bool, isGroup: Bool,
            isEmail: Bool, isCall: Bool, isEvening: Bool, appOpenRate: Double, automated: Bool
        ) {
            self.text = text
            self.pings = pings
            self.importance = importance
            self.isQuestion = isQuestion
            self.isGroup = isGroup
            self.isEmail = isEmail
            self.isCall = isCall
            self.isEvening = isEvening
            self.appOpenRate = appOpenRate
            self.automated = automated
        }
    }

    public struct Score: Equatable, Sendable {
        public let value: Int
        public let reasons: [String]
        public let dueHint: String?
        public let features: [Double]
    }

    public static func score(_ input: Input, learned: LearnedModel? = nil) -> Score {
        let t = input.text.lowercased()
        let strong = STRONG.containsMatch(in: t)
        let weak = !strong && WEAK.containsMatch(in: t)
        let deadline = DEADLINE.containsMatch(in: t)
        let dueHint = DUE.find(t)?.value
        let repeated = input.pings >= 2

        var reasons: [String] = []
        var s = 0.0
        if input.isCall { s += 45; reasons.append("missed call") }
        if strong { s += 40; reasons.append("urgent") } else if weak { s += 25; reasons.append("quick") }
        if repeated { s += input.pings >= 4 ? 30 : 25; reasons.append("asked \(input.pings)×") }
        if deadline { s += 20; reasons.append(dueHint.map { "due \($0)" } ?? "deadline") }
        if input.isQuestion { s += 5 }
        s += 15 * input.importance
        if input.automated { s -= 30 }

        var f = [Double](repeating: 0, count: N_FEATURES)
        f[F_BIAS] = 1.0
        f[F_STRONG] = strong ? 1.0 : 0.0
        f[F_WEAK] = weak ? 1.0 : 0.0
        f[F_DEADLINE] = deadline ? 1.0 : 0.0
        f[F_REPEAT] = Double(min(input.pings, 5)) / 5.0
        f[F_IMPORTANCE] = input.importance
        f[F_QUESTION] = input.isQuestion ? 1.0 : 0.0
        f[F_GROUP] = input.isGroup ? 1.0 : 0.0
        f[F_EMAIL] = input.isEmail ? 1.0 : 0.0
        f[F_CALL] = input.isCall ? 1.0 : 0.0
        f[F_EVENING] = input.isEvening ? 1.0 : 0.0
        f[F_APP_OPEN_RATE] = input.appOpenRate

        var value = s
        if let learned, learned.n >= LearnedModel.MIN_EXAMPLES {
            value = 0.6 * s + 0.4 * (100 * learned.predict(f))
        }
        return Score(value: min(max(toInt(value), 0), 100), reasons: reasons, dueHint: dueHint, features: f)
    }

    // Kotlin Double.toInt(): truncates, NaN is 0, out of range clamps.
    private static func toInt(_ d: Double) -> Int {
        if d.isNaN { return 0 }
        if d >= Double(Int32.max) { return Int(Int32.max) }
        if d <= Double(Int32.min) { return Int(Int32.min) }
        return Int(d)
    }
}

/** Logistic regression over the urgency features, retrained each night on-device. */
public struct LearnedModel: Equatable, Sendable {
    public let weights: [Double]
    public let n: Int

    public static let MIN_EXAMPLES = 30

    public init(weights: [Double], n: Int) {
        self.weights = weights
        self.n = n
    }

    public func predict(_ x: [Double]) -> Double {
        var z = 0.0
        for i in weights.indices { z += weights[i] * (i < x.count ? x[i] : 0.0) }
        return 1.0 / (1.0 + exp(-z))
    }

    public static func train(_ examples: [([Double], Int)], epochs: Int = 300, lr: Double = 0.1, l2: Double = 0.01) -> LearnedModel {
        var w = [Double](repeating: 0, count: UrgencyScorer.N_FEATURES)
        if examples.isEmpty { return LearnedModel(weights: w, n: 0) }
        for _ in 0..<epochs {
            for (x, y) in examples {
                var z = 0.0
                for i in w.indices { z += w[i] * (i < x.count ? x[i] : 0.0) }
                let p = 1.0 / (1.0 + exp(-z))
                let err = p - Double(y)
                for i in w.indices {
                    let reg = i == 0 ? 0.0 : l2 * w[i]
                    w[i] -= lr * (err * (i < x.count ? x[i] : 0.0) + reg)
                }
            }
        }
        return LearnedModel(weights: w, n: examples.count)
    }
}
