package me.gpipi.account

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.MoneyMovement
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import me.gpipi.support.insertTestAccount
import me.gpipi.support.insertTestCategory
import org.jetbrains.exposed.v1.jdbc.selectAll

class AccountServiceTest : PersistenceTest() {
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-07-28T15:30:00Z"),
        ZoneOffset.UTC,
    )
    private val repository = AccountRepository()
    private val service = AccountService(
        db = db,
        repository = repository,
        clock = fixedClock,
    )

    private suspend fun account(name: String): UUID =
        dbQuery(db) { insertTestAccount(name) }

    private fun input(
        from: UUID? = null,
        to: UUID? = null,
        amount: Long = 10_000,
        occurredOn: String = "2026-07-29",
        note: String? = "July salary",
    ) = MovementInput(
        fromAccountId = from?.toString(),
        toAccountId = to?.toString(),
        amount = amount,
        occurredOn = occurredOn,
        note = note,
    )

    @Test
    fun `external top-up preview is authoritative but does not write`() = runBlocking {
        val wallet = account("Household")

        val preview = assertIs<MovementResult.Previewed>(
            service.preview(input(to = wallet, amount = 75_000)),
        ).preview

        assertEquals(Instant.parse("2026-07-28T15:30:00Z"), preview.calculatedAt.toInstant())
        assertEquals(
            BalanceProjectionRecord(
                accountId = wallet,
                name = "Household",
                balanceBefore = 0,
                delta = 75_000,
                balanceAfter = 75_000,
            ),
            preview.accounts.single(),
        )
        assertEquals(0L, service.listAccounts().single().balance)
        assertEquals(0L, dbQuery(db) { MoneyMovement.selectAll().count() })
    }

    @Test
    fun `top-up send and reallocation derive balances and allow negative values`() = runBlocking {
        val everyday = account("Everyday")
        val savings = account("Savings")

        assertIs<MovementResult.Recorded>(
            service.record(UUID.randomUUID().toString(), input(to = everyday, amount = 60_000), "U1"),
        )
        assertIs<MovementResult.Recorded>(
            service.record(
                UUID.randomUUID().toString(),
                input(from = everyday, to = savings, amount = 25_000, note = "Allocate savings"),
                "U1",
            ),
        )
        val externalSend = assertIs<MovementResult.Recorded>(
            service.record(
                UUID.randomUUID().toString(),
                input(from = everyday, amount = 40_000, note = "Utility debit"),
                "U1",
            ),
        ).write

        val balances = service.listAccounts().associate { it.name to it.balance }
        assertEquals(-5_000L, balances.getValue("Everyday"))
        assertEquals(25_000L, balances.getValue("Savings"))
        assertEquals(-5_000L, externalSend.accounts.single().balanceAfter)
    }

    @Test
    fun `recorded expense immediately reduces its snapshotted wallet`() = runBlocking {
        val wallet = account("Everyday")
        val category = dbQuery(db) {
            insertTestCategory(name = "Groceries", accountId = wallet)
        }
        val inboundRepository = InboundRepository()
        val inbound = dbQuery(db) {
            inboundRepository.captureOrSkip(
                "Ev-wallet-spend",
                "U1",
                "C1",
                "15000 groceries",
                "1751700000.000100",
            )
        }!!
        dbQuery(db) {
            ExpenseRepository().insert(
                inboundMessageId = inbound,
                userId = "U1",
                amount = 15_000,
                currency = "JPY",
                merchant = "Life",
                note = null,
                categoryId = category,
            )
        }

        assertEquals(-15_000L, service.listAccounts().single().balance)
    }

    @Test
    fun `future date is rejected using Tokyo household-local today`() = runBlocking {
        val wallet = account("Everyday")

        val result = assertIs<MovementResult.Invalid>(
            service.preview(input(to = wallet, occurredOn = "2026-07-30")),
        )

        assertEquals("Future money movements are not allowed.", result.message)
    }

    @Test
    fun `same idempotency key replays once and rejects changed input or actor`() = runBlocking {
        val wallet = account("Everyday")
        val key = UUID.randomUUID().toString()
        val original = input(to = wallet, amount = 30_000)

        val first = assertIs<MovementResult.Recorded>(service.record(key, original, "U1")).write
        val replay = assertIs<MovementResult.Recorded>(service.record(key, original, "U1")).write

        assertFalse(first.replayed)
        assertEquals(true, replay.replayed)
        assertEquals(first.movement.id, replay.movement.id)
        assertIs<MovementResult.Conflict>(
            service.record(key, original.copy(amount = 31_000), "U1"),
        )
        assertIs<MovementResult.Conflict>(service.record(key, original, "U2"))
        assertEquals(1L, dbQuery(db) { MoneyMovement.selectAll().count() })
    }

    @Test
    fun `concurrent retries with one idempotency key commit one movement`() = runBlocking {
        val wallet = account("Everyday")
        val key = UUID.randomUUID().toString()
        val original = input(to = wallet, amount = 12_500)

        val results = listOf(
            async { service.record(key, original, "U1") },
            async { service.record(key, original, "U1") },
        ).awaitAll().map { assertIs<MovementResult.Recorded>(it).write }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(results.first().movement.id, results.last().movement.id)
        assertEquals(1L, dbQuery(db) { MoneyMovement.selectAll().count() })
    }

    @Test
    fun `keyset pagination neither duplicates nor skips equal-time movements`() = runBlocking {
        val wallet = account("Everyday")
        repeat(5) { index ->
            assertIs<MovementResult.Recorded>(
                service.record(
                    UUID.randomUUID().toString(),
                    input(
                        to = wallet,
                        amount = (index + 1) * 1_000L,
                        occurredOn = "2026-07-28",
                        note = "Allocation $index",
                    ),
                    "U1",
                ),
            )
        }

        val first = assertIs<AccountTransactionsResult.Found>(
            service.transactions(wallet, limit = 2, cursor = null),
        )
        val second = assertIs<AccountTransactionsResult.Found>(
            service.transactions(wallet, limit = 2, cursor = assertNotNull(first.nextCursor)),
        )
        val third = assertIs<AccountTransactionsResult.Found>(
            service.transactions(wallet, limit = 2, cursor = assertNotNull(second.nextCursor)),
        )
        val ids = (first.items + second.items + third.items).map { it.id }

        assertEquals(5, ids.size)
        assertEquals(5, ids.distinct().size)
        assertNotEquals(first.items.last().id, second.items.first().id)
        assertEquals(null, third.nextCursor)
    }
}
