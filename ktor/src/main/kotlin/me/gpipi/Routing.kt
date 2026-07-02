package me.gpipi

import io.ktor.server.application.Application
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

    routing {
        healthRoutes()
        slackRoutes(signingSecret)
    }
}
