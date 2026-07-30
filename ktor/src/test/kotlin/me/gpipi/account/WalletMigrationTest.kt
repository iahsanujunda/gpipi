package me.gpipi.account

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.gpipi.support.TestPostgres
import org.flywaydb.core.Flyway

class WalletMigrationTest {
    @Test
    fun `migration assigns default wallet to existing active inactive and historical rows`() {
        val container = TestPostgres.container
        val schema = "wallet_migration_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password,
        ).use { connection ->
            connection.createStatement().use { it.execute("""create schema "$schema"""") }
        }

        try {
            val flyway = Flyway.configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("12")
                .load()
            flyway.migrate()

            val inactiveId = UUID.randomUUID()
            val inboundId = UUID.randomUUID()
            val expenseId = UUID.randomUUID()
            DriverManager.getConnection(
                container.jdbcUrl,
                container.username,
                container.password,
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into "$schema".category
                            (id, name, description, active, period, amount, slack_loggable)
                        values
                            ('$inactiveId', 'Legacy inactive', 'Archived budget', false,
                             'MONTHLY', 1000, false)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into "$schema".inbound_message
                            (id, event_id, user_id, channel_id, text, slack_ts)
                        values
                            ('$inboundId', 'Ev-wallet-migration', 'U1', 'C1',
                             '1000 archived item', '1751700000.000100')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into "$schema".expense
                            (id, inbound_message_id, user_id, amount, currency, category_id)
                        values
                            ('$expenseId', '$inboundId', 'U1', 1000, 'JPY', '$inactiveId')
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            DriverManager.getConnection(
                container.jdbcUrl,
                container.username,
                container.password,
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        select
                            (select count(*) from "$schema".account
                             where name = 'Default wallet') as defaults,
                            (select count(*) from "$schema".category
                             where account_id = (
                                select id from "$schema".account
                                where name = 'Default wallet'
                             )) as assigned,
                            (select count(*) from "$schema".category) as categories
                        """.trimIndent(),
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt("defaults"))
                        assertEquals(rows.getInt("categories"), rows.getInt("assigned"))
                    }
                    statement.executeQuery(
                        """
                        select e.account_id = c.account_id as correctly_snapshotted
                        from "$schema".expense e
                        join "$schema".category c on c.id = e.category_id
                        where e.id = '$expenseId'
                        """.trimIndent(),
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean("correctly_snapshotted"))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(
                container.jdbcUrl,
                container.username,
                container.password,
            ).use { connection ->
                connection.createStatement().use {
                    it.execute("""drop schema "$schema" cascade""")
                }
            }
        }
    }
}
