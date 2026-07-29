package me.gpipi.category

import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.generated.db.base.public1.Account
import me.gpipi.generated.db.base.public1.Category
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

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
    val active: Boolean,
    val slackLoggable: Boolean,
    val accountId: String,
    val accountName: String,
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
        (Category innerJoin Account)
            .select(
                Category.id,
                Category.name,
                Category.description,
                Category.period,
                Category.amount,
                Category.active,
                Category.slackLoggable,
                Category.accountId,
                Account.name,
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
                    active = row[Category.active],
                    slackLoggable = row[Category.slackLoggable],
                    accountId = row[Category.accountId].toString(),
                    accountName = row[Account.name],
                )
            }

    fun create(name: String,
               description: String,
               period: String,
               amount: Long,
               active: Boolean,
               slackLoggable: Boolean,
               accountId: UUID): UUID {
        val id = UUID.randomUUID()
        Category.insert {
            it[Category.id] = id
            it[Category.name] = name
            it[Category.description] = description
            it[Category.period] = period
            it[Category.amount] = amount
            it[Category.active] = active
            it[Category.slackLoggable] = slackLoggable
            it[Category.accountId] = accountId
        }
        return id
    }

    fun update(id: UUID,
               name: String,
               description: String,
               period: String,
               amount: Long,
               active: Boolean,
               slackLoggable: Boolean,
               accountId: UUID): Boolean =
        Category.update(
            { Category.id eq id }
        ) {
            it[Category.name] = name
            it[Category.description] = description
            it[Category.period] = period
            it[Category.amount] = amount
            it[Category.active] = active
            it[Category.slackLoggable] = slackLoggable
            it[Category.accountId] = accountId
        }
            .let { rowsUpdated -> rowsUpdated > 0 }

    fun deactivate(id: UUID): Boolean =
        Category.update({ Category.id eq id }) {
            it[Category.active] = false
        } > 0
}
