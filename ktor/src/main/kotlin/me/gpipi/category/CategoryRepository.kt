package me.gpipi.category

import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.generated.db.base.public1.Category
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

data class CategoryRow(
    val id: UUID,
    val name: String,
    val description: String,
)

@Serializable
data class BudgetRow(
    val id: String,
    val name: String,
    val description: String,
    val period: String,
    val amount: Long,
)

class CategoryRepository {
    fun findActive(): List<CategoryRow> =
        Category
            .select(Category.id, Category.name, Category.description)
            .where {
                (Category.active eq true) and (Category.slackLoggable eq true)
            }
            .map { row ->
                CategoryRow(
                    id = row[Category.id],
                    name = row[Category.name],
                    description = row[Category.description]
                )
            }

    fun listBudgets(): List<BudgetRow> =
        Category
            .select(
                Category.id,
                Category.name,
                Category.description,
                Category.period,
                Category.amount,
            )
            .where { Category.active eq true }
            .orderBy(Category.name to SortOrder.ASC)
            .map { row ->
                BudgetRow(
                    id = row[Category.id].toString(),
                    name = row[Category.name],
                    description = row[Category.description],
                    period = row[Category.period],
                    amount = row[Category.amount],
                )
            }
}
