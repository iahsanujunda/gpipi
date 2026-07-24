package me.gpipi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import me.gpipi.auth.AuthNonceRepository
import me.gpipi.auth.AuthService
import me.gpipi.auth.authRoutes
import me.gpipi.category.CategoryRepository
import me.gpipi.config.DbKey
import me.gpipi.dev.devRoutes
import me.gpipi.expense.ExpenseRepository
import me.gpipi.ai.OpenRouterClient
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.extraction.ExtractionService
import me.gpipi.health.healthRoutes
import me.gpipi.inbound.InboundRepository
import me.gpipi.slack.LogExpenseCommand
import me.gpipi.slack.OpenBudgetCommand
import me.gpipi.slack.SlackClient
import me.gpipi.slack.SlackEventHandler
import me.gpipi.slack.SlackInteractionHandler
import me.gpipi.slack.slackRoutes
import me.gpipi.slack.slackInteractionRoutes

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
    val authService = AuthService(
        db = db,
        nonceRepo = AuthNonceRepository(),
    )
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

    val slack = SlackClient(httpClient, botToken)

    val orClient = OpenRouterClient(
        httpClient,
        openRouterKey,
        cfg.property("openrouter.model").getString()
    )

    val extractionService = ExtractionService(
        db = db,
        categoryRepo = CategoryRepository(),
        orClient = orClient,
    )

    val inboundRepo = InboundRepository()

    val webBaseUrl = cfg.property("web.baseUrl").getString()
    val eventHandler = SlackEventHandler(
        db = db,
        inboundRepo = inboundRepo,
        commands = listOf(OpenBudgetCommand(authService, slack, webBaseUrl)),
        default = LogExpenseCommand(
            db = db,
            inboundRepo = inboundRepo,
            extractionService = extractionService,
            draftRepo = ExpenseDraftRepository(),
            slack = slack,
        ),
    )

    val interactionHandler = SlackInteractionHandler(
        db = db,
        draftRepo = ExpenseDraftRepository(),
        expenseRepo = ExpenseRepository(),
        inboundRepo = InboundRepository(),
        eventRepo = CategorizationEventRepository(),
        slack = slack
    )

    val isDev = cfg.propertyOrNull("app.env")?.getString().equals("DEV", ignoreCase = true)

    install(CORS) {
        allowHost(cfg.property("cors.allowedOrigin").getString(), schemes = listOf("https","http"))
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Post)
    }

    routing {
        healthRoutes(db)
        authRoutes(authService)
        slackRoutes(signingSecret, eventHandler)
        slackInteractionRoutes(signingSecret, interactionHandler)
        authenticate("auth-session") {  }
        if (isDev) {
            log.warn("DEV routes enabled — /dev/extract calls OpenRouter unauthenticated. Never set APP_ENV=DEV in prod.")
            devRoutes(extractionService)
        }
    }
}
