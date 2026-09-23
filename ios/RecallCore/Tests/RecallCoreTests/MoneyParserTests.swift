import XCTest
@testable import RecallCore

final class MoneyParserTests: XCTestCase {

    private func parse(_ s: String) -> MoneyParser.Parsed? { MoneyParser.parse(s) }

    func testUpiDebitSmsWithBalance() throws {
        let p = try XCTUnwrap(parse("Rs.486.00 debited from A/c XX1234 on 22-09-26 to VPA swiggy@icici (UPI Ref 123456). Avl Bal Rs 12,345.67"))
        XCTAssertEqual(48600, p.amountPaise)
        XCTAssertEqual("out", p.direction)
        XCTAssertEqual("Swiggy", p.merchant)
        XCTAssertEqual("1234", p.account)
    }

    func testCardSpendAtMerchant() throws {
        let p = try XCTUnwrap(parse("Rs 2000.00 spent on your ICICI Bank Credit Card XX4321 at HP PETROL PUMP on 22-Sep-26. Avl Lmt: Rs 45,000.00"))
        XCTAssertEqual(200000, p.amountPaise)
        XCTAssertEqual("out", p.direction)
        XCTAssertEqual("HP Petrol Pump", p.merchant)
        XCTAssertEqual("4321", p.account)
    }

    func testPhonePePaid() throws {
        let p = try XCTUnwrap(parse("Paid ₹445 to Blinkit Commerce Pvt Ltd"))
        XCTAssertEqual(44500, p.amountPaise)
        XCTAssertEqual("out", p.direction)
        XCTAssertEqual("Blinkit Commerce", p.merchant)
    }

    func testGpayPaidYou() throws {
        let p = try XCTUnwrap(parse("Rahul K paid you ₹12,000"))
        XCTAssertEqual(1200000, p.amountPaise)
        XCTAssertEqual("in", p.direction)
        XCTAssertEqual("Rahul K", p.merchant)
    }

    func testReceivedFrom() throws {
        let p = try XCTUnwrap(parse("Received ₹12,000 from Rahul K"))
        XCTAssertEqual("in", p.direction)
        XCTAssertEqual("Rahul K", p.merchant)
    }

    func testCreditedToYourAccountFromVpa() throws {
        let p = try XCTUnwrap(parse("Dear Customer, Rs.500 credited to your a/c XX1234 by UPI from rahul.k@okaxis on 22-09-26"))
        XCTAssertEqual(50000, p.amountPaise)
        XCTAssertEqual("in", p.direction)
        XCTAssertEqual("Rahul K", p.merchant)
    }

    func testInrDebitCard() throws {
        let p = try XCTUnwrap(parse("INR 1,299.00 debited from HDFC Bank Card x5678 at AMAZON on 2026-09-22"))
        XCTAssertEqual(129900, p.amountPaise)
        XCTAssertEqual("Amazon", p.merchant)
        XCTAssertEqual("5678", p.account)
    }

    func testIgnoresOtp() { XCTAssertNil(parse("Your OTP for payment of Rs 500 is 123456. Do not share.")) }

    func testIgnoresBillDue() { XCTAssertNil(parse("Your bill of Rs 799 is due on 25 Sep")) }

    func testIgnoresPromo() { XCTAssertNil(parse("Get ₹100 cashback on your next order")) }

    func testIgnoresNoAmount() { XCTAssertNil(parse("Swiggy: Your order is delivered")) }

    func testIgnoresCollectRequest() { XCTAssertNil(parse("Karthik has requested money ₹250 from you on Google Pay")) }

    func testIgnoresFuture() { XCTAssertNil(parse("Rs 499 will be debited from your account on 25 Sep for Netflix")) }

    func testCategories() {
        XCTAssertEqual("Food", Categories.of("Swiggy", "out"))
        XCTAssertEqual("Fuel", Categories.of("HP Petrol Pump", "out"))
        XCTAssertEqual("Groceries", Categories.of("Blinkit Commerce", "out"))
        XCTAssertEqual("Received", Categories.of("Rahul K", "in"))
        XCTAssertEqual("Other", Categories.of(nil, "out"))
    }

    func testPayerAfterTitle() {
        XCTAssertEqual("Rahul K", parse("Google Pay. Rahul K paid you ₹12,000 via UPI")?.merchant)
    }

    func testFixedDepositIsSavings() throws {
        let raw = "UPDATE: INR 50,000.00 debited from HDFC Bank XX1111 on 05-SEP-26. Info: FD through MOBILE-XXXX1234:A KUMAR. Avl bal:INR 1,00,000.00"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertEqual(5000000, p.amountPaise)
        XCTAssertEqual("Savings", Categories.of(p.merchant, p.direction, raw))
    }

    func testSalaryCredit() throws {
        let raw = "Update! INR 90,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for XXXX2527-TPT-Aug 2026 salary-ACME LABS PRIVATE LIMITED.Avl bal INR 2,00,000.00"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertEqual("in", p.direction)
        XCTAssertEqual("Acme Labs", p.merchant)
        XCTAssertEqual("Salary", Categories.of(p.merchant, p.direction, raw))
    }

    func testNeftSender() {
        XCTAssertEqual("Blue Pine Tech", parse("Update! INR 10,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for NEFT Cr-KKBK0000001-BLUE PINE TECH -A KUMAR-CMS0000001")?.merchant)
    }

    func testUpiPathSender() {
        XCTAssertEqual("S Rao", parse("INR 700.00 credited A/c no. XX9999 03-09-26, 16:40:34 IST UPI/P2A/600000000001/S Rao/SBIN/UPI - Axis Bank")?.merchant)
    }

    func testIgnoresFundRequestConfirmations() {
        XCTAssertNil(parse("Dear Investor, Your PURCHASE request for Rs 20000.0 in Folio no 4***7 has been submitted successfully."))
        XCTAssertNil(parse("Your additional purchase request for Rs.20000 has been received on 10 SEP 2026."))
    }

    func testCardSpendSkipsOwnCard() throws {
        let raw = "Spent Rs.527 On HDFC Bank Card 3740 At SWIGGY FOOD On 2026-08-24:16:48:19.Not You? To Block+Reissue Call 18002586161"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertEqual("Swiggy Food", p.merchant)
        XCTAssertEqual("Food", Categories.of(p.merchant, p.direction, raw))
    }

    func testOwnAccountTransferIsSavings() throws {
        let raw = "HDFC Bank:Rs. 6000.00 debited from a/c *1111 on 05/09/26 to a/c **2222 (UPI Ref No. 600000000004). Not you? Call on 18002586161 to report"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertNil(p.merchant)
        XCTAssertEqual("Self transfer", Categories.of(p.merchant, p.direction, raw))
    }

    func testUpiToPersonWithoutTrailingSlash() throws {
        let raw = "INR 54.00 debited A/c no. XX1111 25-08-26, 15:17:29 UPI/P2A/600000000002/ANIL KUMAR Not you? SMS BLOCKUPI"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertEqual("Anil Kumar", p.merchant)
        XCTAssertEqual("People", Categories.of(p.merchant, p.direction, raw))
    }

    func testLeadingTheAndBills() throws {
        XCTAssertEqual("Tasty Corner", parse("Sent Rs.390.00 From HDFC Bank A/C *1111 To THE TASTY CORNER AND CO On 28/08/26 Ref 600000000003")?.merchant)
        let bill = "Dear Customer, Thank You for making payment of Rs.1179.00/- on 18-SEP-26 for your BSNL Landline bill"
        let p = try XCTUnwrap(parse(bill))
        XCTAssertEqual("Bills", Categories.of(p.merchant, p.direction, bill))
    }

    func testOwnAccountByLearnedName() {
        // Kotlin sets Categories.ownerNames globally; here the names are passed in.
        let owners = ["A KUMARASWAMY"]
        XCTAssertEqual(true, Categories.isOwner("A Kumaras", ownerNames: owners))
        XCTAssertEqual(false, Categories.isOwner("Anil Kumar", ownerNames: owners))
        XCTAssertEqual(true, Categories.isOwner("Kumaraswamy A", ownerNames: owners))
        XCTAssertEqual("Self transfer", Categories.of("A Kumaras", "in", "INR 700.00 credited A/c no. XX1111 UPI/P2A/1234/A KUMARAS/SBIN", ownerNames: owners))
    }

    func testTermDepositClosureIsSavings() throws {
        let raw = "INR 150000.00 credited to A/c no. XX1111 on 06-12-23 at 05:41:34 IST. Info- TO For 900000000000001. Avl Bal- INR 150321.25 - Axis Bank"
        let p = try XCTUnwrap(parse(raw))
        XCTAssertEqual("in", p.direction)
        XCTAssertEqual("Savings", Categories.of(p.merchant, p.direction, raw))
        XCTAssertEqual("Fixed deposits", Categories.investmentType(raw))
    }

    func testIgnoresPassbookBalance() {
        XCTAssertNil(parse("Dear XXXXXXXX1864, your passbook balance against ABCDE**0000 is Rs. 1,00,000/-. Contribution of Rs. 1,800/- for due month Jun-26"))
    }

    func testTruncatedCompanySuffix() {
        XCTAssertEqual("Northwind Data Systems", parse("Update! INR 10,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for NEFT Cr-KKBK0000001-NORTHWIND DATA SYSTEMS PRI-A KUMAR-CMS1")?.merchant)
    }
}
