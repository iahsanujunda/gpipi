package me.gpipi.category

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database

fun Route.budgetApiRoutes(
    db: Database,
    categoryRepo: CategoryRepository,
) {
    get("/api/budgets") {
        val budgets = dbQuery(db) {
            categoryRepo.listBudgets()
        }
        call.respond(budgets)
    }
}
