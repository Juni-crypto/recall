package app.recall.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryLineTest {
    @Test fun splitsSenderFromSubject() {
        assertEquals("Cosmos" to "[Cosmos] Replica updated", SummaryLine.split("Cosmos   [Cosmos] Replica updated"))
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
            MailThread.key("gm", "Shivam, Ishika", "Re: [Juni-crypto/cosmos] Refine export handling"),
            MailThread.key("gm", "Shivam, Ishika", "RE: Fwd: [Juni-crypto/cosmos]  Refine export handling\nLGTM"),
        )
    }

    @Test fun differentMailsFromOneSenderAreSeparate() {
        val a = MailThread.key("gm", "Cosmos", "[Cosmos] ETL run failed — Perfora / daily")
        val b = MailThread.key("gm", "Cosmos", "[Cosmos] ClickHouse replica updated — perfora")
        assert(a != b)
    }
}
