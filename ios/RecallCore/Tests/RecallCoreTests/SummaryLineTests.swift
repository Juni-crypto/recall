import XCTest
@testable import RecallCore

final class SummaryLineTests: XCTestCase {
    private func assertSplit(_ sender: String?, _ text: String, _ line: String, file: StaticString = #filePath, line l: UInt = #line) {
        let r = SummaryLine.split(line)
        XCTAssertEqual(sender, r.sender, file: file, line: l)
        XCTAssertEqual(text, r.text, file: file, line: l)
    }

    func testSplitsSenderFromSubject() {
        assertSplit("Beacon", "[Beacon] Replica updated", "Beacon   [Beacon] Replica updated")
        assertSplit("Priya S", "can you send the invoice", "Priya S: can you send the invoice")
        assertSplit("Karthik R", "Contract review", "Karthik R - Contract review")
    }

    func testLineWithoutSenderStaysWhole() {
        assertSplit(nil, "Your order has shipped", "Your order has shipped")
    }
}

final class MailThreadTests: XCTestCase {
    func testRepliesStayInTheirThread() {
        XCTAssertEqual(
            MailThread.key("gm", "Dev, Asha", "Re: [acme-labs/beacon] Refine export handling"),
            MailThread.key("gm", "Dev, Asha", "RE: Fwd: [acme-labs/beacon]  Refine export handling\nLGTM")
        )
    }

    func testDifferentMailsFromOneSenderAreSeparate() {
        let a = MailThread.key("gm", "Beacon", "[Beacon] ETL run failed — orders / daily")
        let b = MailThread.key("gm", "Beacon", "[Beacon] Warehouse replica updated — orders")
        XCTAssertNotEqual(a, b)
    }
}
