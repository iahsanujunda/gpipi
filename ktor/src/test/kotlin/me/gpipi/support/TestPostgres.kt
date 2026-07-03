package me.gpipi.support

import me.gpipi.config.DbConfig
import me.gpipi.config.connectDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

object TestPostgres {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withReuse(true)
            .apply { start() }
    }

    val database: Database by lazy {
        connectDatabase(DbConfig(container.jdbcUrl, container.username, container.password, maxPoolSize = 2)).database
    }
}