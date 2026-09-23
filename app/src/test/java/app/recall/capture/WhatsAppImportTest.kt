package app.recall.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class WhatsAppImportTest {
    private val utc = ZoneOffset.UTC
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(utc).toEpochMilli()

    @Test fun androidOneToOne() {
        val text = """
            23/09/26, 3:10 pm - Messages and calls are end-to-end encrypted.
            23/09/26, 3:10 pm - Arjun Mehta: hey can u approve this pr
            quick? blocking the release
            23/09/26, 3:12 pm - Sam: on it
            23/09/26, 3:13 pm - Arjun Mehta: <Media omitted>
        """.trimIndent()
        val out = WhatsAppImport.parse("Arjun Mehta", text, emptyList(), utc)
        assertEquals(2, out.size)
        assertEquals("hey can u approve this pr\nquick? blocking the release", out[0].text)
        assertEquals(ms(2026, 9, 23, 15, 10), out[0].at)
        assertFalse(out[0].mine)
        assertTrue(out[1].mine)
        assertFalse(out[0].isGroup)
    }

    @Test fun iosBracketsAndMonthFirst() {
        val text = """
            [09/23/26, 9:05:10 AM] Priya S: can you send the invoice by today?
            [09/23/26, 12:30:00 PM] Me: sent
        """.trimIndent()
        val out = WhatsAppImport.parse("Priya S", text, emptyList(), utc)
        assertEquals(ms(2026, 9, 23, 9, 5) + 10_000, out[0].at)
        assertEquals(ms(2026, 9, 23, 12, 30), out[1].at)
        assertTrue(out[1].mine)
    }

    @Test fun groupUsesConfiguredName() {
        val text = """
            01/10/2026, 21:15 - Ravi: dinner at 9?
            01/10/2026, 21:16 - Anu: yes
            01/10/2026, 21:20 - Sam: coming
        """.trimIndent()
        val out = WhatsAppImport.parse("College gang", text, listOf("Sam"), utc)
        assertTrue(out.all { it.isGroup })
        assertEquals(listOf(false, false, true), out.map { it.mine })
        assertEquals(ms(2026, 10, 1, 21, 15), out[0].at)
    }
}
