package me

import org.testcontainers.postgresql.PostgreSQLContainer

object TestPostgres {
    val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .apply { start()}
    }
}