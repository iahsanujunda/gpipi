package me.gpipi.support

import java.util.UUID
import me.gpipi.generated.db.base.public1.Account
import me.gpipi.generated.db.base.public1.Category
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

fun insertTestAccount(name: String = "Test wallet ${UUID.randomUUID()}"): UUID {
    val id = UUID.randomUUID()
    Account.insert {
        it[Account.id] = id
        it[Account.name] = name
        it[Account.description] = "Test wallet"
    }
    return id
}

fun insertTestCategory(
    id: UUID = UUID.randomUUID(),
    name: String,
    description: String = "Test category",
    period: String = "MONTHLY",
    amount: Long = 50_000L,
    active: Boolean = true,
    slackLoggable: Boolean = true,
    accountId: UUID = insertTestAccount(),
): UUID {
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

fun testCategoryAccountId(categoryId: UUID): UUID =
    Category
        .select(Category.accountId)
        .where { Category.id eq categoryId }
        .single()[Category.accountId]
