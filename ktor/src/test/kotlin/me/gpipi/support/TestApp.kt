package me.gpipi.support

import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Boots the real application.conf module chain against the Testcontainers Postgres. Since
 * configureDatabase now runs on every startup, testApplication must point db.* at a live DB
 * or Hikari fails with connection-refused. Reuses the shared [TestPostgres] container.
 */
fun ApplicationTestBuilder.configureWithTestDb(
    signingSecret: String = "test-signing-secret",
    appEnv: String? = null,   // null → app.env falls back to PROD from application.conf
) = configure {
    put("slack.signingSecret", signingSecret)
    // Dummy secrets so configureRouting's fail-fast guards pass; nothing in these tests
    // calls Slack or OpenRouter for real. openrouter.model/url resolve from application.conf.
    put("slack.botToken", "xoxb-test-token")
    put("openrouter.apiKey", "test-openrouter-key")
    put("session.signKey", "test-session-sign-key")
    if (appEnv != null) put("app.env", appEnv)
    val container = TestPostgres.container
    put("db.url", container.jdbcUrl)
    put("db.user", container.username)
    put("db.password", container.password)
    put("db.maxPoolSize", "2")
}
