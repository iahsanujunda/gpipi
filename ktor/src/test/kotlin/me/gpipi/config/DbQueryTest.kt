package me.gpipi.config

import kotlinx.coroutines.runBlocking
import me.gpipi.support.TestPostgres
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DbQueryTest {

    @Test
    fun `dbQuery opens a live transaction for the block`() = runBlocking {
        val txPresent = dbQuery(TestPostgres.database) {
            TransactionManager.currentOrNull() != null
        }
        assertEquals(true, txPresent)
    }

    @Test
    fun `dbQuery returns the block result via a real SELECT`() = runBlocking {
        val one = dbQuery(TestPostgres.database) {
            val tx = assertNotNull(TransactionManager.currentOrNull())
            tx.exec("SELECT 1") { rs -> if (rs.next()) rs.getInt(1) else null }
        }
        assertEquals(1, one)
    }
}
