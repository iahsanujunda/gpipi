package me.gpipi.inbound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

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
        val row = query { InboundMessage.selectAll().single() }
        assertEquals("RECEIVED", row[InboundMessage.status])
        assertEquals("Ev001", row[InboundMessage.eventId])
        assertEquals(id, row[InboundMessage.id])
    }

    @Test
    fun `duplicate event_id returns null and keeps a single row`() {
        assertNotNull(capture("EvDup"))
        assertNull(capture("EvDup"))
        assertEquals(1, query { InboundMessage.selectAll().count() })
    }

    @Test
    fun `markFailed sets status and keeps the raw text`() {
        val id = capture()!!
        query { repo.markFailed(id, "schema mismatch") }
        val row = query { InboundMessage.selectAll().single() }
        assertEquals("FAILED_PARSE", row[InboundMessage.status])
        assertEquals("schema mismatch", row[InboundMessage.failReason])
        assertEquals("1500 ramen", row[InboundMessage.text])
    }

    @Test
    fun `markRecorded sets status`() {
        val id = capture()!!
        query { repo.markRecorded(id) }
        assertEquals("RECORDED", query { InboundMessage.selectAll().single() }[InboundMessage.status])
    }
}