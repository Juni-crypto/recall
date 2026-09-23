import XCTest
@testable import RecallCore

final class RequestAndUrgencyTests: XCTestCase {
    static let OpenLoopThreshold = 70

    func testRequests() {
        XCTAssertTrue(RequestDetector.detect("hey can u approve this pr quick?").isRequest)
        XCTAssertTrue(RequestDetector.detect("are you free saturday for the site visit?").isRequest)
        XCTAssertTrue(RequestDetector.detect("can you send the invoice by today?").isRequest)
        XCTAssertTrue(RequestDetector.detect("Following up on the contract review").isRequest)
        XCTAssertTrue(RequestDetector.detect("pls call me when you see this").isRequest)
    }

    func testSystemMailIsAutomated() {
        XCTAssertTrue(SenderHeuristics.isSystemMail("[Beacon] Warehouse build finished — Orders 1 alert(s) from Beacon"))
        XCTAssertFalse(SenderHeuristics.isSystemMail("Can you review the contract before Friday?"))
    }

    func testNotRequests() {
        XCTAssertFalse(RequestDetector.detect("ok thanks").isRequest)
        XCTAssertFalse(RequestDetector.detect("lol").isRequest)
        XCTAssertFalse(RequestDetector.detect("I reached home").isRequest)
        XCTAssertFalse(RequestDetector.detect("👍").isRequest)
    }

    private func input(_ text: String, pings: Int = 1, importance: Double = 0.3, call: Bool = false, automated: Bool = false) -> UrgencyScorer.Input {
        UrgencyScorer.Input(
            text: text, pings: pings, importance: importance, isQuestion: text.contains("?"), isGroup: false,
            isEmail: false, isCall: call, isEvening: false, appOpenRate: 0.5, automated: automated
        )
    }

    func testUrgentPrApprovalAskedTwice() {
        let s = UrgencyScorer.score(input("hey can u approve this pr quick? blocking the release", pings: 2))
        XCTAssertTrue(s.value >= Self.OpenLoopThreshold, "score \(s.value)")
        XCTAssertTrue(s.reasons.contains("urgent"))
    }

    func testDeadlineIsCaptured() {
        let s = UrgencyScorer.score(input("can you send the invoice by today?"))
        XCTAssertEqual("by today", s.dueHint)
        XCTAssertTrue(s.value < Self.OpenLoopThreshold)
    }

    func testRepeatedMissedCallsAreUrgent() {
        XCTAssertTrue(UrgencyScorer.score(input("Missed call", pings: 2, importance: 0.8, call: true)).value >= Self.OpenLoopThreshold)
        XCTAssertTrue(UrgencyScorer.score(input("Missed call", pings: 1, call: true)).value < Self.OpenLoopThreshold)
    }

    func testLearnedModelSeparatesExamples() {
        var pos = [Double](repeating: 0, count: UrgencyScorer.N_FEATURES)
        pos[0] = 1.0; pos[UrgencyScorer.F_STRONG] = 1.0
        var neg = [Double](repeating: 0, count: UrgencyScorer.N_FEATURES)
        neg[0] = 1.0; neg[UrgencyScorer.F_GROUP] = 1.0
        let model = LearnedModel.train(Array(repeating: (pos, 1), count: 20) + Array(repeating: (neg, 0), count: 20))
        XCTAssertTrue(model.predict(pos) > 0.8)
        XCTAssertTrue(model.predict(neg) < 0.2)
    }

    private static var utc: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }

    private func day(_ y: Int, _ m: Int, _ d: Int) -> Date {
        Self.utc.date(from: DateComponents(year: y, month: m, day: d))!
    }

    func testQueryParsing() {
        let today = day(2026, 9, 23)
        let people = [("com.whatsapp|rahul", "Rahul K"), ("com.whatsapp|amma", "Amma")]
        let q = QueryParser.parse("what did rahul want yesterday?", today: today, people: people, apps: [], merchants: [], calendar: Self.utc)
        XCTAssertEqual(["com.whatsapp|rahul"], q.people)
        XCTAssertEqual(day(2026, 9, 22), q.range.start)
        XCTAssertFalse(q.money)

        let m = QueryParser.parse("how much on swiggy this month", today: today, people: people, apps: [], merchants: ["Swiggy", "Amazon"], calendar: Self.utc)
        XCTAssertTrue(m.money)
        XCTAssertEqual("Swiggy", m.merchantTerm)
        XCTAssertEqual(day(2026, 9, 1), m.range.start)
        XCTAssertNotNil(QueryParser.ftsQuery(["invoice", "pr"]))
    }

    func testAlertMailProblemsAreSpotted() {
        XCTAssertTrue(SenderHeuristics.isProblem("[Beacon] ETL run failed — orders / nightly"))
        XCTAssertTrue(SenderHeuristics.isProblem("[Beacon-dev] API errors — 22 in 5 minutes"))
        XCTAssertTrue(SenderHeuristics.isProblem("[Beacon-dev] Endpoint failing — GET /clients"))
        XCTAssertFalse(SenderHeuristics.isProblem("[Beacon] Warehouse build finished — Orders\n1 alert(s) from Beacon."))
        XCTAssertFalse(SenderHeuristics.isProblem("[Beacon] Warehouse replica updated — orders"))
    }
}
