package app.recall.understand

import app.recall.chat.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RequestAndUrgencyTest {

    @Test fun requests() {
        assertTrue(RequestDetector.detect("hey can u approve this pr quick?").isRequest)
        assertTrue(RequestDetector.detect("are you free saturday for the site visit?").isRequest)
        assertTrue(RequestDetector.detect("can you send the invoice by today?").isRequest)
        assertTrue(RequestDetector.detect("Following up on the contract review").isRequest)
        assertTrue(RequestDetector.detect("pls call me when you see this").isRequest)
    }

    @Test fun systemMailIsAutomated() {
        assertTrue(SenderHeuristics.isSystemMail("[Cosmos] Warehouse build finished — Perfora 1 alert(s) from Cosmos"))
        assertFalse(SenderHeuristics.isSystemMail("Can you review the contract before Friday?"))
    }

    @Test fun notRequests() {
        assertFalse(RequestDetector.detect("ok thanks").isRequest)
        assertFalse(RequestDetector.detect("lol").isRequest)
        assertFalse(RequestDetector.detect("I reached home").isRequest)
        assertFalse(RequestDetector.detect("👍").isRequest)
    }

    private fun input(text: String, pings: Int = 1, importance: Double = 0.3, call: Boolean = false, automated: Boolean = false) =
        UrgencyScorer.Input(text, pings, importance, text.contains('?'), false, false, call, false, 0.5, automated)

    @Test fun urgentPrApprovalAskedTwice() {
        val s = UrgencyScorer.score(input("hey can u approve this pr quick? blocking the release", pings = 2))
        assertTrue("score ${s.value}", s.value >= OpenLoopThreshold)
        assertTrue(s.reasons.contains("urgent"))
    }

    @Test fun deadlineIsCaptured() {
        val s = UrgencyScorer.score(input("can you send the invoice by today?"))
        assertEquals("by today", s.dueHint)
        assertTrue(s.value < OpenLoopThreshold)
    }

    @Test fun repeatedMissedCallsAreUrgent() {
        assertTrue(UrgencyScorer.score(input("Missed call", pings = 2, importance = 0.8, call = true)).value >= OpenLoopThreshold)
        assertTrue(UrgencyScorer.score(input("Missed call", pings = 1, call = true)).value < OpenLoopThreshold)
    }

    @Test fun learnedModelSeparatesExamples() {
        val pos = DoubleArray(UrgencyScorer.N_FEATURES).also { it[0] = 1.0; it[UrgencyScorer.F_STRONG] = 1.0 }
        val neg = DoubleArray(UrgencyScorer.N_FEATURES).also { it[0] = 1.0; it[UrgencyScorer.F_GROUP] = 1.0 }
        val model = LearnedModel.train(List(20) { pos to 1 } + List(20) { neg to 0 })
        assertTrue(model.predict(pos) > 0.8)
        assertTrue(model.predict(neg) < 0.2)
    }

    @Test fun queryParsing() {
        val today = LocalDate.of(2026, 9, 23)
        val people = listOf("com.whatsapp|rahul" to "Rahul K", "com.whatsapp|amma" to "Amma")
        val q = QueryParser.parse("what did rahul want yesterday?", today, people, emptyList(), emptyList())
        assertEquals(listOf("com.whatsapp|rahul"), q.people)
        assertEquals(LocalDate.of(2026, 9, 22), q.range.start)
        assertFalse(q.money)

        val m = QueryParser.parse("how much on swiggy this month", today, people, emptyList(), listOf("Swiggy", "Amazon"))
        assertTrue(m.money)
        assertEquals("Swiggy", m.merchantTerm)
        assertEquals(LocalDate.of(2026, 9, 1), m.range.start)
        assertNotNull(QueryParser.ftsQuery(listOf("invoice", "pr")))
    }

    companion object {
        const val OpenLoopThreshold = 70
    }
}
