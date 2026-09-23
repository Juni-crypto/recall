import XCTest
@testable import RecallCore

final class WhatsAppImportTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    private func ms(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Int64 {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = utc
        let date = c.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
        return Int64(date.timeIntervalSince1970) * 1000
    }

    func testAndroidOneToOne() {
        let text = """
            23/09/26, 3:10 pm - Messages and calls are end-to-end encrypted.
            23/09/26, 3:10 pm - Arjun Mehta: hey can u approve this pr
            quick? blocking the release
            23/09/26, 3:12 pm - Sam: on it
            23/09/26, 3:13 pm - Arjun Mehta: <Media omitted>
            """
        let out = WhatsAppImport.parse("Arjun Mehta", text, selfNames: [], zone: utc)
        XCTAssertEqual(2, out.count)
        XCTAssertEqual("hey can u approve this pr\nquick? blocking the release", out[0].text)
        XCTAssertEqual(ms(2026, 9, 23, 15, 10), out[0].at)
        XCTAssertFalse(out[0].mine)
        XCTAssertTrue(out[1].mine)
        XCTAssertFalse(out[0].isGroup)
    }

    func testIosBracketsAndMonthFirst() {
        let text = """
            [09/23/26, 9:05:10 AM] Priya S: can you send the invoice by today?
            [09/23/26, 12:30:00 PM] Me: sent
            """
        let out = WhatsAppImport.parse("Priya S", text, selfNames: [], zone: utc)
        XCTAssertEqual(ms(2026, 9, 23, 9, 5) + 10_000, out[0].at)
        XCTAssertEqual(ms(2026, 9, 23, 12, 30), out[1].at)
        XCTAssertTrue(out[1].mine)
    }

    func testGroupUsesConfiguredName() {
        let text = """
            01/10/2026, 21:15 - Ravi: dinner at 9?
            01/10/2026, 21:16 - Anu: yes
            01/10/2026, 21:20 - Sam: coming
            """
        let out = WhatsAppImport.parse("College gang", text, selfNames: ["Sam"], zone: utc)
        XCTAssertTrue(out.allSatisfy { $0.isGroup })
        XCTAssertEqual([false, false, true], out.map(\.mine))
        XCTAssertEqual(ms(2026, 10, 1, 21, 15), out[0].at)
    }
}
