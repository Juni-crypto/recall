import XCTest
@testable import RecallCore

/// A stand-in for the on-device model that returns fixed replies.
@MainActor
final class FakeBrain: TextGenerator {
    var available = true
    var replies: [String]
    private(set) var prompts: [String] = []
    init(_ replies: [String]) { self.replies = replies }
    func generate(system: String, user: String, maxTokens: Int, temperature: Float, load: GenerationLoad,
                  onText: (@Sendable (String) -> Void)?) async -> String? {
        prompts.append(user)
        return replies.isEmpty ? nil : replies.removeFirst()
    }
}

@MainActor
final class EngineTests: XCTestCase {
    private func engine() throws -> RecallEngine { RecallEngine(store: try RecallStore.inMemory()) }

    func testBankSmsBecomesAPayment() throws {
        let e = try engine()
        XCTAssertTrue(e.intake(text: "Rs.250.00 debited from A/c XX1234 to VPA swiggy@icici on 12-09-26. Ref 123456", sender: "AX-HDFCBK", source: .sms))
        let (start, end) = Clock.dayBounds()
        XCTAssertEqual(e.store.moneyTotals(from: start, to: end).0, 25_000)
        XCTAssertTrue(e.store.openLoops().isEmpty, "a bank alert isn't someone waiting on you")
    }

    func testSameMessageTwiceIsStoredOnce() throws {
        let e = try engine()
        XCTAssertTrue(e.intake(text: "can you send the invoice today?", sender: "Priya", source: .sms))
        XCTAssertFalse(e.intake(text: "can you send the invoice today?", sender: "Priya", source: .sms))
        XCTAssertEqual(e.store.recentMessages().count, 1)
    }

    func testRequestOpensALoopAndDoneClosesIt() throws {
        let e = try engine()
        e.intake(text: "hey can u approve this pr quick? blocking the release", sender: "Arjun", source: .whatsapp)
        let loops = e.store.openLoops()
        XCTAssertEqual(loops.count, 1)
        XCTAssertEqual(loops.first?.person, "Arjun")
        e.done(loops[0])
        XCTAssertTrue(e.store.openLoops().isEmpty)
    }

    func testEachMailThreadIsItsOwnConversation() throws {
        let e = try engine()
        e.intake(text: "22 in 5 minutes", sender: "Beacon (dev)", subject: "[Beacon-dev] API errors", source: .mail)
        e.intake(text: "orders / daily", sender: "Beacon (dev)", subject: "[Beacon-dev] ETL run failed", source: .mail)
        e.intake(text: "LGTM", sender: "Dev", subject: "Re: [org/repo] Refine export", source: .mail)
        e.intake(text: "one more thing", sender: "Dev", subject: "RE: [org/repo] Refine export", source: .mail)
        XCTAssertEqual(Set(e.store.recentMessages().map(\.convKey)).count, 3)
    }

    func testModelReviewsProblemAlertsButNotRoutineOnes() async throws {
        let e = try engine()
        e.intake(text: "orders / daily", sender: "Beacon", subject: "[Beacon] ETL run failed", source: .mail)
        e.intake(text: "Orders", sender: "Beacon", subject: "[Beacon] Warehouse build finished", source: .mail)
        let brain = FakeBrain(["1 | NEED | 2 | - | Check the failed ETL run"])
        let report = await e.review(with: brain, load: .interactive)
        XCTAssertEqual(report.reviewed, 1, "only the failure goes to the model")
        XCTAssertTrue(brain.prompts.first?.contains("(alert)") == true)
        XCTAssertEqual(e.store.openLoops().first?.askText, "Check the failed ETL run")
        XCTAssertEqual(e.store.pendingTriage(since: 0), 0)
    }

    func testModelCanDismissARuleFlag() async throws {
        let e = try engine()
        e.intake(text: "can you believe this weather?", sender: "Rahul", source: .whatsapp)
        XCTAssertEqual(e.store.openLoops().count, 1)
        _ = await e.review(with: FakeBrain(["1 | FYI | 0 | - | Chat about weather"]), load: .interactive)
        XCTAssertTrue(e.store.openLoops().isEmpty)
    }

    func testCopiedExampleAnswersAreIgnored() async throws {
        let e = try engine()
        e.intake(text: "can you send the invoice today?", sender: "Priya", source: .sms)
        e.intake(text: "3 tables failed to load", sender: "Beacon", subject: "[Beacon] ETL run failed", source: .mail)
        let brain = FakeBrain(["1 | NEED | 2 | - | Check the failed ETL run\n2 | NEED | 2 | - | Check the failed ETL run"])
        _ = await e.review(with: brain, load: .interactive)
        let asks = e.store.openLoops().map(\.askText)
        XCTAssertEqual(asks.filter { $0 == "Check the failed ETL run" }.count, 1, "only the ETL mail keeps that answer")
    }

    func testAnswersFollowTheNameNotTheNumber() {
        // The model skipped item 1, so its numbers are one off; the names put them back.
        let out = "1 | Priya S | NEED | 2 | today | Send the invoice\n2 | Neha | FYI | 0 | - | Lunch plans\n3 | Nobody | NEED | 3 | - | Something"
        let a = Prompts.align(Prompts.parseTriage(out), titles: ["Karthik R", "Priya S", "Neha"])
        XCTAssertNil(a[0])
        XCTAssertEqual(a[1]?.ask, "Send the invoice")
        XCTAssertEqual(a[2]?.need, false)
        XCTAssertEqual(a.count, 2, "the answer naming nobody is dropped")
    }

    func testCopiedExampleDeadlineIsDropped() {
        XCTAssertTrue(Prompts.isParrotedDue("before release", text: "Could you share your comments on the contract by Friday?"))
        XCTAssertFalse(Prompts.isParrotedDue("before release", text: "approve this pr, it's blocking the release"))
    }

    func testPaymentAlertsSkipTheReview() async throws {
        let e = try engine()
        e.intake(text: "Rs.250.00 debited from A/c XX1234 to VPA swiggy@icici on 12-09-26. Ref 123456", sender: "HDFC Bank", source: .sms)
        let brain = FakeBrain(["1 | HDFC Bank | NEED | 1 | - | Check payment"])
        let r = await e.review(with: brain, load: .interactive)
        XCTAssertEqual(r.reviewed, 0)
        XCTAssertTrue(brain.prompts.isEmpty)
    }

    func testModelMoneyNeedsTheDigitsInTheMessage() {
        let msgs = ["Your a/c XX1234 is debited for INR 1,299.00 at SOMESHOP", "Paid Rs 450 to Ravi"]
        let a = Prompts.parseMoney("1 | DEBIT | 1299.00 | SOMESHOP\n2 | DEBIT | 4500 | Ravi", messages: msgs)
        XCTAssertEqual(a[0]?.amountPaise, 129_900)
        XCTAssertNil(a[1], "4500 isn't in the message, so it's rejected")
    }

    func testDigestWithoutModelStatesTheFacts() async throws {
        let e = try engine()
        e.intake(text: "can you send the invoice today?", sender: "Priya", source: .sms)
        let d = await e.buildDigest(brain: nil, load: .light)
        XCTAssertTrue(d.summary.contains("waiting on you"), d.summary)
        XCTAssertEqual(e.snapshot().mood, "needs")
    }

    func testRupeesUseIndianGrouping() {
        XCTAssertEqual(Fmt.rupees(12_240_000), "₹1,22,400")
        XCTAssertEqual(Fmt.rupees(4_819_500), "₹48,195")
        XCTAssertEqual(Fmt.rupees(1_850), "₹18.50")
    }
}
