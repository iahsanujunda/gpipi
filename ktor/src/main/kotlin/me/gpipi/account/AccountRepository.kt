package me.gpipi.account

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import me.gpipi.expense.expenseDescription
import me.gpipi.generated.db.base.public1.Account
import me.gpipi.generated.db.base.public1.MoneyMovement
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update

data class AccountRecord(
    val id: UUID,
    val name: String,
    val description: String?,
    val balance: Long,
    val assignedBudgetCount: Int,
)

data class AssignedBudgetRecord(
    val id: UUID,
    val name: String,
    val period: String,
    val amount: Long,
)

data class MovementRecord(
    val id: UUID,
    val idempotencyKey: UUID,
    val fromAccountId: UUID?,
    val toAccountId: UUID?,
    val amount: Long,
    val occurredAt: OffsetDateTime,
    val note: String?,
    val createdByUserId: String,
    val createdAt: OffsetDateTime,
)

data class TimelineCursor(
    val occurredAt: OffsetDateTime,
    val kindRank: Int,
    val id: UUID,
)

sealed interface AccountTransactionRecord {
    val id: UUID
    val occurredAt: OffsetDateTime
    val signedAmount: Long

    data class Expense(
        override val id: UUID,
        override val occurredAt: OffsetDateTime,
        override val signedAmount: Long,
        val merchant: String?,
        val description: String?,
        val categoryName: String,
        val note: String?,
    ) : AccountTransactionRecord

    data class Movement(
        override val id: UUID,
        override val occurredAt: OffsetDateTime,
        override val signedAmount: Long,
        val direction: String,
        val counterpartyAccountId: UUID?,
        val counterpartyName: String,
        val note: String?,
    ) : AccountTransactionRecord
}

class AccountRepository {
    fun listAccounts(): List<AccountRecord> =
        queryAccounts()

    fun findAccount(id: UUID): AccountRecord? =
        queryAccounts(listOf(id)).singleOrNull()

    fun findAccounts(ids: Collection<UUID>): Map<UUID, AccountRecord> =
        queryAccounts(ids).associateBy(AccountRecord::id)

    fun exists(id: UUID): Boolean =
        Account
            .selectAll()
            .where { Account.id eq id }
            .limit(1)
            .any()

    fun create(name: String, description: String?): UUID {
        val id = UUID.randomUUID()
        Account.insert {
            it[Account.id] = id
            it[Account.name] = name
            it[Account.description] = description
        }
        return id
    }

    fun update(
        id: UUID,
        name: String,
        description: String?,
        updatedAt: OffsetDateTime,
    ): Boolean =
        Account.update({ Account.id eq id }) {
            it[Account.name] = name
            it[Account.description] = description
            it[Account.updatedAt] = updatedAt
        } > 0

    fun listAssignedBudgets(accountId: UUID): List<AssignedBudgetRecord> {
        val transaction = checkNotNull(TransactionManager.currentOrNull())
        return transaction.exec(
            """
            select id, name, period, amount
            from category
            where account_id = ?
              and active = true
            order by name asc
            """.trimIndent(),
            args = listOf(UUIDColumnType() to accountId),
        ) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        AssignedBudgetRecord(
                            id = rs.getObject("id", UUID::class.java),
                            name = rs.getString("name"),
                            period = rs.getString("period"),
                            amount = rs.getLong("amount"),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    fun balance(accountId: UUID): Long {
        val transaction = checkNotNull(TransactionManager.currentOrNull())
        return transaction.exec(
            """
            select
                coalesce((select sum(amount) from money_movement where to_account_id = ?), 0)
              - coalesce((select sum(amount) from money_movement where from_account_id = ?), 0)
              - coalesce((select sum(amount) from expense where account_id = ?), 0)
                as balance
            """.trimIndent(),
            args = listOf(
                UUIDColumnType() to accountId,
                UUIDColumnType() to accountId,
                UUIDColumnType() to accountId,
            ),
        ) { rs ->
            if (rs.next()) rs.getLong("balance") else 0L
        } ?: 0L
    }

    fun insertMovementIgnore(
        idempotencyKey: UUID,
        fromAccountId: UUID?,
        toAccountId: UUID?,
        amount: Long,
        occurredAt: OffsetDateTime,
        note: String?,
        createdByUserId: String,
    ): UUID? {
        val id = UUID.randomUUID()
        val inserted = MoneyMovement.insertIgnore {
            it[MoneyMovement.id] = id
            it[MoneyMovement.idempotencyKey] = idempotencyKey
            it[MoneyMovement.fromAccountId] = fromAccountId
            it[MoneyMovement.toAccountId] = toAccountId
            it[MoneyMovement.amount] = amount
            it[MoneyMovement.occurredAt] = occurredAt
            it[MoneyMovement.note] = note
            it[MoneyMovement.createdByUserId] = createdByUserId
        }
        return id.takeIf { inserted.insertedCount == 1 }
    }

    fun findMovementByIdempotencyKey(key: UUID): MovementRecord? =
        MoneyMovement
            .selectAll()
            .where { MoneyMovement.idempotencyKey eq key }
            .singleOrNull()
            ?.let { row ->
                MovementRecord(
                    id = row[MoneyMovement.id],
                    idempotencyKey = row[MoneyMovement.idempotencyKey],
                    fromAccountId = row[MoneyMovement.fromAccountId],
                    toAccountId = row[MoneyMovement.toAccountId],
                    amount = row[MoneyMovement.amount],
                    occurredAt = row[MoneyMovement.occurredAt],
                    note = row[MoneyMovement.note],
                    createdByUserId = row[MoneyMovement.createdByUserId],
                    createdAt = row[MoneyMovement.createdAt],
                )
            }

    fun listTransactions(
        accountId: UUID,
        limit: Int,
        cursor: TimelineCursor?,
    ): List<AccountTransactionRecord> {
        val cursorPredicate = if (cursor == null) {
            ""
        } else {
            "and (occurred_at, kind_rank, id) < (?, ?, ?)"
        }
        val sql =
            """
            with account_timeline as (
                select
                    'EXPENSE'::text as kind,
                    0 as kind_rank,
                    e.id,
                    e.spent_at as occurred_at,
                    -e.amount as signed_amount,
                    e.merchant,
                    im.text as source_text,
                    e.note,
                    c.name as category_name,
                    null::text as direction,
                    null::uuid as counterparty_account_id,
                    null::text as counterparty_name
                from expense e
                join category c on c.id = e.category_id
                join inbound_message im on im.id = e.inbound_message_id
                where e.account_id = ?

                union all

                select
                    'MONEY_MOVEMENT'::text as kind,
                    1 as kind_rank,
                    m.id,
                    m.occurred_at,
                    case when m.to_account_id = ? then m.amount else -m.amount end as signed_amount,
                    null::text as merchant,
                    null::text as source_text,
                    m.note,
                    null::text as category_name,
                    case when m.to_account_id = ? then 'INCOMING' else 'OUTGOING' end as direction,
                    case when m.to_account_id = ? then m.from_account_id else m.to_account_id end
                        as counterparty_account_id,
                    coalesce(counterparty.name, 'External account') as counterparty_name
                from money_movement m
                left join account counterparty
                    on counterparty.id =
                        case when m.to_account_id = ? then m.from_account_id else m.to_account_id end
                where m.from_account_id = ? or m.to_account_id = ?
            )
            select *
            from account_timeline
            where true
            $cursorPredicate
            order by occurred_at desc, kind_rank desc, id desc
            limit ?
            """.trimIndent()

        val args: MutableList<Pair<IColumnType<*>, Any?>> = mutableListOf(
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
            UUIDColumnType() to accountId,
        )
        if (cursor != null) {
            args += JavaOffsetDateTimeColumnType() to cursor.occurredAt
            args += IntegerColumnType() to cursor.kindRank
            args += UUIDColumnType() to cursor.id
        }
        args += IntegerColumnType() to limit

        val transaction = checkNotNull(TransactionManager.currentOrNull())
        return transaction.exec(
            stmt = sql,
            args = args,
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            buildList {
                while (rs.next()) {
                    val id = rs.getObject("id", UUID::class.java)
                    val occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java)
                    val amount = rs.getLong("signed_amount")
                    when (rs.getString("kind")) {
                        "EXPENSE" -> add(
                            AccountTransactionRecord.Expense(
                                id = id,
                                occurredAt = occurredAt,
                                signedAmount = amount,
                                merchant = rs.getString("merchant"),
                                description = expenseDescription(
                                    sourceText = rs.getString("source_text"),
                                    note = rs.getString("note"),
                                    amount = -amount,
                                ),
                                categoryName = rs.getString("category_name"),
                                note = rs.getString("note"),
                            ),
                        )

                        else -> add(
                            AccountTransactionRecord.Movement(
                                id = id,
                                occurredAt = occurredAt,
                                signedAmount = amount,
                                direction = rs.getString("direction"),
                                counterpartyAccountId = rs.getObject(
                                    "counterparty_account_id",
                                    UUID::class.java,
                                ),
                                counterpartyName = rs.getString("counterparty_name"),
                                note = rs.getString("note"),
                            ),
                        )
                    }
                }
            }
        }.orEmpty()
    }

    fun encodeCursor(record: AccountTransactionRecord): String {
        val rank = if (record is AccountTransactionRecord.Movement) 1 else 0
        val raw = "${record.occurredAt}|$rank|${record.id}"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeCursor(value: String): TimelineCursor {
        require(value.length <= 512)
        val raw = String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8,
        )
        val parts = raw.split('|')
        require(parts.size == 3)
        val rank = parts[1].toInt()
        require(rank == 0 || rank == 1)
        return TimelineCursor(
            occurredAt = OffsetDateTime.parse(parts[0]),
            kindRank = rank,
            id = UUID.fromString(parts[2]),
        )
    }

    private fun queryAccounts(ids: Collection<UUID>? = null): List<AccountRecord> {
        val where = when {
            ids == null -> ""
            ids.isEmpty() -> "where false"
            else -> "where a.id in (${ids.joinToString(",") { "?" }})"
        }
        val transaction = checkNotNull(TransactionManager.currentOrNull())
        return transaction.exec(
            """
            select
                a.id,
                a.name,
                a.description,
                coalesce(budget_counts.assigned_budget_count, 0) as assigned_budget_count,
                coalesce(incoming.total, 0)
                  - coalesce(outgoing.total, 0)
                  - coalesce(spending.total, 0) as balance
            from account a
            left join (
                select account_id, count(*) as assigned_budget_count
                from category
                where active = true
                group by account_id
            ) budget_counts on budget_counts.account_id = a.id
            left join (
                select to_account_id as account_id, sum(amount) as total
                from money_movement
                where to_account_id is not null
                group by to_account_id
            ) incoming on incoming.account_id = a.id
            left join (
                select from_account_id as account_id, sum(amount) as total
                from money_movement
                where from_account_id is not null
                group by from_account_id
            ) outgoing on outgoing.account_id = a.id
            left join (
                select account_id, sum(amount) as total
                from expense
                group by account_id
            ) spending on spending.account_id = a.id
            $where
            order by lower(a.name), a.id
            """.trimIndent(),
            args = ids?.map { UUIDColumnType() to it }.orEmpty(),
        ) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        AccountRecord(
                            id = rs.getObject("id", UUID::class.java),
                            name = rs.getString("name"),
                            description = rs.getString("description"),
                            balance = rs.getLong("balance"),
                            assignedBudgetCount = rs.getInt("assigned_budget_count"),
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
