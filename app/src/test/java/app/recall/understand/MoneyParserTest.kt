package app.recall.understand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParserTest {

    private fun parse(s: String) = MoneyParser.parse(s)

    @Test fun upiDebitSmsWithBalance() {
        val p = parse("Rs.486.00 debited from A/c XX1234 on 22-09-26 to VPA swiggy@icici (UPI Ref 123456). Avl Bal Rs 12,345.67")!!
        assertEquals(48600, p.amountPaise)
        assertEquals("out", p.direction)
        assertEquals("Swiggy", p.merchant)
        assertEquals("1234", p.account)
    }

    @Test fun cardSpendAtMerchant() {
        val p = parse("Rs 2000.00 spent on your ICICI Bank Credit Card XX4321 at HP PETROL PUMP on 22-Sep-26. Avl Lmt: Rs 45,000.00")!!
        assertEquals(200000, p.amountPaise)
        assertEquals("out", p.direction)
        assertEquals("HP Petrol Pump", p.merchant)
        assertEquals("4321", p.account)
    }

    @Test fun phonePePaid() {
        val p = parse("Paid ₹445 to Blinkit Commerce Pvt Ltd")!!
        assertEquals(44500, p.amountPaise)
        assertEquals("out", p.direction)
        assertEquals("Blinkit Commerce", p.merchant)
    }

    @Test fun gpayPaidYou() {
        val p = parse("Rahul K paid you ₹12,000")!!
        assertEquals(1200000, p.amountPaise)
        assertEquals("in", p.direction)
        assertEquals("Rahul K", p.merchant)
    }

    @Test fun receivedFrom() {
        val p = parse("Received ₹12,000 from Rahul K")!!
        assertEquals("in", p.direction)
        assertEquals("Rahul K", p.merchant)
    }

    @Test fun creditedToYourAccountFromVpa() {
        val p = parse("Dear Customer, Rs.500 credited to your a/c XX1234 by UPI from rahul.k@okaxis on 22-09-26")!!
        assertEquals(50000, p.amountPaise)
        assertEquals("in", p.direction)
        assertEquals("Rahul K", p.merchant)
    }

    @Test fun inrDebitCard() {
        val p = parse("INR 1,299.00 debited from HDFC Bank Card x5678 at AMAZON on 2026-09-22")!!
        assertEquals(129900, p.amountPaise)
        assertEquals("Amazon", p.merchant)
        assertEquals("5678", p.account)
    }

    @Test fun ignoresOtp() = assertNull(parse("Your OTP for payment of Rs 500 is 123456. Do not share."))

    @Test fun ignoresBillDue() = assertNull(parse("Your bill of Rs 799 is due on 25 Sep"))

    @Test fun ignoresPromo() = assertNull(parse("Get ₹100 cashback on your next order"))

    @Test fun ignoresNoAmount() = assertNull(parse("Swiggy: Your order is delivered"))

    @Test fun ignoresCollectRequest() = assertNull(parse("Karthik has requested money ₹250 from you on Google Pay"))

    @Test fun ignoresFuture() = assertNull(parse("Rs 499 will be debited from your account on 25 Sep for Netflix"))

    @Test fun categories() {
        assertEquals("Food", Categories.of("Swiggy", "out"))
        assertEquals("Fuel", Categories.of("HP Petrol Pump", "out"))
        assertEquals("Groceries", Categories.of("Blinkit Commerce", "out"))
        assertEquals("Received", Categories.of("Rahul K", "in"))
        assertEquals("Other", Categories.of(null, "out"))
    }

    @Test fun payerAfterTitle() {
        assertEquals("Rahul K", parse("Google Pay. Rahul K paid you ₹12,000 via UPI")!!.merchant)
    }

    @Test fun fixedDepositIsSavings() {
        val raw = "UPDATE: INR 50,000.00 debited from HDFC Bank XX1111 on 05-SEP-26. Info: FD through MOBILE-XXXX1234:A KUMAR. Avl bal:INR 1,00,000.00"
        val p = parse(raw)!!
        assertEquals(5000000, p.amountPaise)
        assertEquals("Savings", Categories.of(p.merchant, p.direction, raw))
    }

    @Test fun salaryCredit() {
        val raw = "Update! INR 90,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for XXXX2527-TPT-Aug 2026 salary-ACME LABS PRIVATE LIMITED.Avl bal INR 2,00,000.00"
        val p = parse(raw)!!
        assertEquals("in", p.direction)
        assertEquals("Acme Labs", p.merchant)
        assertEquals("Salary", Categories.of(p.merchant, p.direction, raw))
    }

    @Test fun neftSender() {
        assertEquals("Blue Pine Tech", parse("Update! INR 10,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for NEFT Cr-KKBK0000001-BLUE PINE TECH -A KUMAR-CMS0000001")!!.merchant)
    }

    @Test fun upiPathSender() {
        assertEquals("S Rao", parse("INR 700.00 credited A/c no. XX9999 03-09-26, 16:40:34 IST UPI/P2A/600000000001/S Rao/SBIN/UPI - Axis Bank")!!.merchant)
    }

    @Test fun ignoresFundRequestConfirmations() {
        assertNull(parse("Dear Investor, Your PURCHASE request for Rs 20000.0 in Folio no 4***7 has been submitted successfully."))
        assertNull(parse("Your additional purchase request for Rs.20000 has been received on 10 SEP 2026."))
    }

    @Test fun cardSpendSkipsOwnCard() {
        val raw = "Spent Rs.527 On HDFC Bank Card 3740 At SWIGGY FOOD On 2026-08-24:16:48:19.Not You? To Block+Reissue Call 18002586161"
        val p = parse(raw)!!
        assertEquals("Swiggy Food", p.merchant)
        assertEquals("Food", Categories.of(p.merchant, p.direction, raw))
    }

    @Test fun ownAccountTransferIsSavings() {
        val raw = "HDFC Bank:Rs. 6000.00 debited from a/c *1111 on 05/09/26 to a/c **2222 (UPI Ref No. 600000000004). Not you? Call on 18002586161 to report"
        val p = parse(raw)!!
        assertNull(p.merchant)
        assertEquals("Self transfer", Categories.of(p.merchant, p.direction, raw))
    }

    @Test fun upiToPersonWithoutTrailingSlash() {
        val raw = "INR 54.00 debited A/c no. XX1111 25-08-26, 15:17:29 UPI/P2A/600000000002/ANIL KUMAR Not you? SMS BLOCKUPI"
        val p = parse(raw)!!
        assertEquals("Anil Kumar", p.merchant)
        assertEquals("People", Categories.of(p.merchant, p.direction, raw))
    }

    @Test fun leadingTheAndBills() {
        assertEquals("Tasty Corner", parse("Sent Rs.390.00 From HDFC Bank A/C *1111 To THE TASTY CORNER AND CO On 28/08/26 Ref 600000000003")!!.merchant)
        val bill = "Dear Customer, Thank You for making payment of Rs.1179.00/- on 18-SEP-26 for your BSNL Landline bill"
        val p = parse(bill)!!
        assertEquals("Bills", Categories.of(p.merchant, p.direction, bill))
    }

    @Test fun ownAccountByLearnedName() {
        Categories.ownerNames = listOf("A KUMARASWAMY")
        assertEquals(true, Categories.isOwner("A Kumaras"))
        assertEquals(false, Categories.isOwner("Anil Kumar"))
        assertEquals(true, Categories.isOwner("Kumaraswamy A"))
        assertEquals("Self transfer", Categories.of("A Kumaras", "in", "INR 700.00 credited A/c no. XX1111 UPI/P2A/1234/A KUMARAS/SBIN"))
        Categories.ownerNames = emptyList()
    }

    @Test fun termDepositClosureIsSavings() {
        val raw = "INR 150000.00 credited to A/c no. XX1111 on 06-12-23 at 05:41:34 IST. Info- TO For 900000000000001. Avl Bal- INR 150321.25 - Axis Bank"
        val p = parse(raw)!!
        assertEquals("in", p.direction)
        assertEquals("Savings", Categories.of(p.merchant, p.direction, raw))
        assertEquals("Fixed deposits", Categories.investmentType(raw))
    }

    @Test fun ignoresPassbookBalance() =
        assertNull(parse("Dear XXXXXXXX1864, your passbook balance against ABCDE**0000 is Rs. 1,00,000/-. Contribution of Rs. 1,800/- for due month Jun-26"))

    @Test fun truncatedCompanySuffix() {
        assertEquals("Northwind Data Systems", parse("Update! INR 10,000.00 deposited in HDFC Bank A/c XX1111 on 01-SEP-26 for NEFT Cr-KKBK0000001-NORTHWIND DATA SYSTEMS PRI-A KUMAR-CMS1")!!.merchant)
    }
}
