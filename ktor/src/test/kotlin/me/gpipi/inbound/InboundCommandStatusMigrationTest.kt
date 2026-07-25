package me.gpipi.inbound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class InboundCommandStatusMigrationTest : PersistenceTest() {
    private val repo = InboundRepository()

    @Test
    fun `legacy backfill closes only messages matching the open command grammar`() = runBlocking {
        val examples = listOf(
            "EvOpen" to "<@BOT> open",
            "EvOpenBudget" to "<@BOT> OPEN budget",
            "EvTabNearMatch" to "<@BOT> open\tbudget",
            "EvExpense" to "<@BOT> 1500 for ramen",
        )
        dbQuery(db) {
            examples.forEach { (eventId, text) ->
                repo.captureOrSkip(eventId, "U1", "C1", text, "1751700000.000100")
            }

            val migration = checkNotNull(
                javaClass.getResource("/db/migration/V9__inbound_command_status.sql"),
            ).readText()
            TransactionManager.current().exec(migration)
        }

        val statuses = dbQuery(db) {
            InboundMessage
                .selectAll()
                .orderBy(InboundMessage.eventId to SortOrder.ASC)
                .associate {
                    it[InboundMessage.eventId] to it[InboundMessage.status]
                }
        }

        assertEquals(
            mapOf(
                "EvExpense" to "RECEIVED",
                "EvOpen" to "COMMAND",
                "EvOpenBudget" to "COMMAND",
                "EvTabNearMatch" to "RECEIVED",
            ),
            statuses,
        )
    }
}
