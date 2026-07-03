package me.gpipi.support

import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Boots the real application.conf module chain against the Testcontainers Postgres. Since
 * configureDatabase now runs on every startup, testApplication must point db.* at a live DB
 * or Hikari fails with connection-refused. Reuses the shared [TestPostgres] container.
 */
fun ApplicationTestBuilder.configureWithTestDb(signingSecret: String = "test-signing-secret") = configure {
    put("slack.signingSecret", signingSecret)
    val container = TestPostgres.container
    put("db.url", container.jdbcUrl)
    put("db.user", container.username)
    put("db.password", container.password)
    put("db.maxPoolSize", "2")
}
