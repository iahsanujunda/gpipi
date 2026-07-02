package me.gpipi.support

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

object TestPostgres {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withReuse(true)
            .apply { start() }
    }

    val database: Database by lazy {
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .load()
            .migrate()
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password
        )
    }
}