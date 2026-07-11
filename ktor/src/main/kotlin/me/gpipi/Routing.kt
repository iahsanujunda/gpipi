package me.gpipi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import me.gpipi.health.healthRoutes
import me.gpipi.slack.slackRoutes
import me.gpipi.config.DbKey
import me.gpipi.expense.ExpenseRepository
import me.gpipi.extraction.OpenRouterClient
import me.gpipi.inbound.InboundRepository
import me.gpipi.slack.SlackClient
import me.gpipi.slack.SlackEventHandler

/**
 * Composition root for routes — hand-wired, since Ktor has no component scan. Public health
 * routes sit alongside (not inside) the Slack group, so `/health` needs no signature while
 * `/slack/events` verifies per-request within `slackRoutes`.
 */
fun Application.configureRouting() {
    val signingSecret = environment.config
        .propertyOrNull("slack.signingSecret")?.getString().orEmpty()

    // Startup visibility + fail-fast: never boot silently misconfigured. Log presence only,
    // never the value. A blank secret means every Slack request is unverifiable, so refuse to
    // start rather than 401/500 every request at runtime.
    log.info("Slack signing secret present: ${signingSecret.isNotBlank()}")
    require(signingSecret.isNotBlank()) {
        "SLACK_SIGNING_SECRET is missing — set it in .env and restart before starting the server."
    }

    val db = attributes[DbKey].database
    val cfg = environment.config
    val botToken = cfg.propertyOrNull("slack.botToken")?.getString().orEmpty()
    val openRouterKey = cfg.propertyOrNull("openrouter.apiKey")?.getString().orEmpty()

    require(botToken.isNotBlank()) { "SLACK_BOT_OAUTH_TOKEN is missing. set it in .env and restart." }
    require(openRouterKey.isNotBlank()) { "OPENROUTER_API_KEY is missing. set it in .env and restart." }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            retryOnException(maxRetries = 2, retryOnTimeout = true)
            exponentialDelay()
        }
    }
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    val handler = SlackEventHandler(
        db = db,
        inboundRepo = InboundRepository(),
        expenseRepo = ExpenseRepository(),
        orClient = OpenRouterClient(
            httpClient,
            openRouterKey,
            cfg.property("openrouter.model").getString()
        ),
        slack = SlackClient(httpClient, botToken)
    )

    routing {
        healthRoutes()
        slackRoutes(signingSecret, handler)
    }
}
