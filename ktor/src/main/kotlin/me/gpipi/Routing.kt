package me.gpipi

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import me.gpipi.health.healthRoutes
import me.gpipi.slack.slackRoutes

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

    routing {
        healthRoutes()
        slackRoutes(signingSecret)
    }
}
