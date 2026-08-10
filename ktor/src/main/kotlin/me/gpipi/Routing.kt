package me.gpipi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import me.gpipi.ai.OpenRouterClient
import me.gpipi.account.AccountRepository
import me.gpipi.account.AccountService
import me.gpipi.account.accountApiRoutes
import me.gpipi.auth.AuthNonceRepository
import me.gpipi.auth.AuthService
import me.gpipi.auth.authRoutes
import me.gpipi.category.ActiveCategoryCatalog
import me.gpipi.category.BudgetService
import me.gpipi.category.CategoryRepository
import me.gpipi.category.budgetApiRoutes
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.DbKey
import me.gpipi.dev.devRoutes
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.expense.expenseApiRoutes
import me.gpipi.extraction.ExtractionService
import me.gpipi.health.healthRoutes
import me.gpipi.inbound.InboundRepository
import me.gpipi.slack.HelpCommand
import me.gpipi.slack.LogExpenseCommand
import me.gpipi.slack.OpenBudgetCommand
import me.gpipi.slack.ShoppingAddCommand
import me.gpipi.slack.ShoppingShowCommand
import me.gpipi.slack.SlackClient
import me.gpipi.slack.SlackEventHandler
import me.gpipi.slack.SlackInteractionHandler
import me.gpipi.slack.slackInteractionRoutes
import me.gpipi.slack.slackRoutes
import me.gpipi.shopping.ShoppingExtractionService
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.shopping.ShoppingService
import me.gpipi.shopping.shoppingApiRoutes
import me.gpipi.training.TrainingRepository
import me.gpipi.training.TrainingService
import me.gpipi.training.trainingApiRoutes

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

    val slackHttpClient = HttpClient(CIO) { configureSlackHttpClient() }
    val openRouterHttpClient = HttpClient(CIO) { configureOpenRouterHttpClient() }
    monitor.subscribe(ApplicationStopped) {
        slackHttpClient.close()
        openRouterHttpClient.close()
    }

    val slack = SlackClient(slackHttpClient, botToken)

    val orClient = OpenRouterClient(
        openRouterHttpClient,
        openRouterKey,
        cfg.property("openrouter.model").getString()
    )

    val categoryRepo = CategoryRepository()
    val accountRepo = AccountRepository()
    val inboundRepo = InboundRepository()
    val expenseRepo = ExpenseRepository()
    val activeCategoryCatalog = ActiveCategoryCatalog(db, categoryRepo)
    val budgetService = BudgetService(
        db = db,
        categoryRepo = categoryRepo,
        expenseRepo = expenseRepo,
        activeCategories = activeCategoryCatalog,
    )
    val accountService = AccountService(
        db = db,
        repository = accountRepo,
    )

    val authService = AuthService(
        db = db,
        nonceRepo = AuthNonceRepository(),
    )

    val extractionService = ExtractionService(
        activeCategories = activeCategoryCatalog,
        orClient = orClient,
    )
    val shoppingRepository = ShoppingRepository()
    val shoppingService = ShoppingService(db, shoppingRepository)
    val shoppingExtractionService = ShoppingExtractionService(orClient)
    val trainingService = TrainingService(db, TrainingRepository())

    val webBaseUrl = cfg.property("web.baseUrl").getString()
    val eventHandler = SlackEventHandler(
        db = db,
        inboundRepo = inboundRepo,
        commands = listOf(
            HelpCommand(slack),
            OpenBudgetCommand(authService, slack, webBaseUrl),
            ShoppingAddCommand(
                db = db,
                extractionService = shoppingExtractionService,
                repository = shoppingRepository,
                slack = slack,
            ),
            ShoppingShowCommand(shoppingService, slack),
        ),
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
        expenseRepo = expenseRepo,
        inboundRepo = InboundRepository(),
        eventRepo = CategorizationEventRepository(),
        shoppingService = shoppingService,
        slack = slack
    )

    val isDev = cfg.propertyOrNull("app.env")?.getString().equals("DEV", ignoreCase = true)

    install(CORS) {
        allowHost(cfg.property("cors.allowedOrigin").getString(), schemes = listOf("https","http"))
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        healthRoutes(db)
        authRoutes(authService)
        slackRoutes(signingSecret, eventHandler)
        slackInteractionRoutes(signingSecret, interactionHandler)
        authenticate("auth-session") {
            expenseApiRoutes(db, expenseRepo)
            accountApiRoutes(accountService)
            budgetApiRoutes(budgetService)
            shoppingApiRoutes(shoppingService)
            trainingApiRoutes(trainingService)
        }
        if (isDev) {
            log.warn("DEV routes enabled — /dev/extract calls OpenRouter unauthenticated. Never set APP_ENV=DEV in prod.")
            devRoutes(extractionService)
        }
    }
}
