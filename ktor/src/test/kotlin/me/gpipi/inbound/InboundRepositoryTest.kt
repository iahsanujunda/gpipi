package me.gpipi.inbound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.assertNull

class InboundRepositoryTest : PersistenceTest() {
    private val repo = InboundRepository()
    private fun capture(eventId: String = "Ev001") = runBlocking {
        dbQuery(db) { repo.captureOrSkip(eventId, "U1", "C1", "1500 ramen", "1751700000.000100") }
    }
    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    @Test
    fun `capture inserts a row with status RECEIVED`() {
        val id = capture()
        assertNotNull(id)
        val row = query { InboundMessages.selectAll().single() }
        assertEquals("RECEIVED", row[InboundMessages.status])
        assertEquals("Ev001", row[InboundMessages.eventId])
        assertEquals(id, row[InboundMessages.id].value)
    }

    @Test
    fun `duplicate event_id returns null and keeps a single row`() {
        assertNotNull(capture("EvDup"))
        assertNull(capture("EvDup"))
        assertEquals(1, query { InboundMessages.selectAll().count() })
    }

    @Test
    fun `markFailed sets status and keeps the raw text`() {
        val id = capture()!!
        query { repo.markFailed(id, "schema mismatch") }
        val row = query { InboundMessages.selectAll().single() }
        assertEquals("FAILED_PARSE", row[InboundMessages.status])
        assertEquals("schema mismatch", row[InboundMessages.failReason])
        assertEquals("1500 ramen", row[InboundMessages.text])
    }

    @Test
    fun `markRecorded sets status`() {
        val id = capture()!!
        query { repo.markRecorded(id) }
        assertEquals("RECORDED", query { InboundMessages.selectAll().single() }[InboundMessages.status])
    }
}