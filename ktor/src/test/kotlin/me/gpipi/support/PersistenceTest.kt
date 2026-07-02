package me.gpipi.support

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach

abstract class PersistenceTest {
    protected val db: Database = TestPostgres.database
    @BeforeEach fun clean() = cleanDatabase()
}

fun cleanDatabase() = transaction(TestPostgres.database) {
    val tables = exec(
        """select tablename from pg_tables
            | where pg_tables.schemaname = 'public' and pg_tables.tablename <> 'flyway_schema_history'
        """.trimMargin()
    ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } ?: emptyList()
    if (tables.isNotEmpty()) {
        exec("TRUNCATE TABLE ${tables.joinToString(", ")} RESTART IDENTITY CASCADE")
    }
}