package app.recall.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryLineTest {
    @Test fun splitsSenderFromSubject() {
        assertEquals("Beacon" to "[Beacon] Replica updated", SummaryLine.split("Beacon   [Beacon] Replica updated"))
        assertEquals("Priya S" to "can you send the invoice", SummaryLine.split("Priya S: can you send the invoice"))
        assertEquals("Karthik R" to "Contract review", SummaryLine.split("Karthik R - Contract review"))
    }

    @Test fun lineWithoutSenderStaysWhole() {
        assertEquals(null to "Your order has shipped", SummaryLine.split("Your order has shipped"))
    }
}

class MailThreadTest {
    @Test fun repliesStayInTheirThread() {
        assertEquals(
            MailThread.key("gm", "Dev, Asha", "Re: [acme-labs/beacon] Refine export handling"),
            MailThread.key("gm", "Dev, Asha", "RE: Fwd: [acme-labs/beacon]  Refine export handling\nLGTM"),
        )
    }

    @Test fun differentMailsFromOneSenderAreSeparate() {
        val a = MailThread.key("gm", "Beacon", "[Beacon] ETL run failed — orders / daily")
        val b = MailThread.key("gm", "Beacon", "[Beacon] Warehouse replica updated — orders")
        assert(a != b)
    }
}
