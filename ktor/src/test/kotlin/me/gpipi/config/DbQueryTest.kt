package me.gpipi.config

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.gpipi.support.TestPostgres
import me.gpipi.observability.TelemetryRuntime
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `dbQuery keeps the parent trace across the IO dispatcher`() = runBlocking {
        val parent = TelemetryRuntime.tracer("me.gpipi.test")
            .spanBuilder("request")
            .startSpan()

        try {
            val dbSpan = withContext(Context.root().with(parent).asContextElement()) {
                dbQuery(TestPostgres.database) {
                    Span.current().spanContext
                }
            }

            assertEquals(parent.spanContext.traceId, dbSpan.traceId)
            assertNotEquals(parent.spanContext.spanId, dbSpan.spanId)
        } finally {
            parent.end()
        }
    }
}
