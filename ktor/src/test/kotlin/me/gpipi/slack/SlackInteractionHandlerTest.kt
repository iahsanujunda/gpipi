package me.gpipi.slack

import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.BudgetEnvelope
import me.gpipi.generated.db.base.public1.CategorizationEvent
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.generated.db.base.public1.ExpenseDraft
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Confirm-path tests: real repos + Testcontainers DB so the atomic write (expense +
 * categorization_event + inbound status flip + draft consume) is genuinely exercised, with
 * Slack mocked so nothing hits the network. Payload parsing lives in the route (step 2);
 * here we drive handleConfirm(draftId, finalCategoryId) directly.
 */
class SlackInteractionHandlerTest : PersistenceTest() {

    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val draftRepo = ExpenseDraftRepository()
    private val handler = SlackInteractionHandler(
        db, draftRepo, ExpenseRepository(), InboundRepository(), CategorizationEventRepository(), slack,
    )

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun givenInbound(eventId: String = "Ev001"): UUID = runBlocking {
        dbQuery(db) { InboundRepository().captureOrSkip(eventId, "U1", "C1", "510 conbini", "1751700000.000100") }!!
    }

    private fun givenCategory(name: String): UUID = query {
        val envId = UUID.randomUUID()
        BudgetEnvelope.insert {
            it[BudgetEnvelope.id] = envId
            it[BudgetEnvelope.name] = "Env $name"
            it[BudgetEnvelope.period] = "WEEKLY"
            it[BudgetEnvelope.amount] = 15000L
        }
        val catId = UUID.randomUUID()
        Category.insert {
            it[Category.id] = catId
            it[Category.envelopeId] = envId
            it[Category.name] = name
            it[Category.description] = "desc for $name"
        }
        catId
    }

    private fun givenDraft(msgId: UUID, predictedCategoryId: UUID): UUID = query {
        draftRepo.insert(
            inboundMessageId = msgId, userId = "U1", channelId = "C1",
            amount = 510, currency = "JPY", merchant = "conbini", note = null,
            predictedCategoryId = predictedCategoryId, confidence = 0.9, model = "qwen/qwen3-instruct",
        )
    }

    private val responseUrl = "https://hooks.slack.test/r"

    private fun confirm(
        draftId: UUID,
        finalCategoryId: UUID,
        categoryName: String = "Convenience Store",
        responseUrl: String? = this.responseUrl,
    ) = runBlocking { handler.handleConfirm(draftId, finalCategoryId, categoryName, responseUrl) }

    @Test
    fun `confirm with no change records the prediction and commits all four writes`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val draftId = givenDraft(msgId, predicted)

        confirm(draftId, finalCategoryId = predicted)

        val expense = query { Expense.selectAll().single() }
        assertEquals(msgId, expense[Expense.inboundMessageId])
        assertEquals(510L, expense[Expense.amount])
        assertEquals(predicted, expense[Expense.categoryId])

        val event = query { CategorizationEvent.selectAll().single() }
        assertEquals(expense[Expense.id], event[CategorizationEvent.expenseId])
        assertEquals(predicted, event[CategorizationEvent.predictedCategoryId])
        assertEquals(predicted, event[CategorizationEvent.finalCategoryId])
        assertFalse(event[CategorizationEvent.wasCorrected])

        assertEquals("RECORDED", query { InboundMessage.selectAll().single()[InboundMessage.status] })
        assertEquals("CONFIRMED", query { ExpenseDraft.selectAll().single()[ExpenseDraft.status] })
        // Replaces the card in place via response_url, with the category name from the payload.
        coVerify(exactly = 1) { slack.replaceCard(responseUrl, match { "Recorded" in it && "Convenience Store" in it }) }
    }

    @Test
    fun `confirm with a changed category records the correction on both expense and event`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val corrected = givenCategory("Monthly Groceries")
        val draftId = givenDraft(msgId, predicted)

        confirm(draftId, finalCategoryId = corrected)

        val expense = query { Expense.selectAll().single() }
        assertEquals(corrected, expense[Expense.categoryId])   // expense carries the FINAL category

        val event = query { CategorizationEvent.selectAll().single() }
        assertEquals(predicted, event[CategorizationEvent.predictedCategoryId])
        assertEquals(corrected, event[CategorizationEvent.finalCategoryId])
        assertTrue(event[CategorizationEvent.wasCorrected])
    }

    @Test
    fun `a second confirm on the same draft writes nothing and posts once`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val draftId = givenDraft(msgId, predicted)

        confirm(draftId, predicted)
        confirm(draftId, predicted)   // double-tap Confirm

        assertEquals(1L, query { Expense.selectAll().count() })
        assertEquals(1L, query { CategorizationEvent.selectAll().count() })
        coVerify(exactly = 1) { slack.replaceCard(any(), any()) }
    }

    @Test
    fun `confirm on an unknown draft writes nothing and does not post`() {
        confirm(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(0L, query { Expense.selectAll().count() })
        assertEquals(0L, query { CategorizationEvent.selectAll().count() })
        coVerify(exactly = 0) { slack.replaceCard(any(), any()) }
        coVerify(exactly = 0) { slack.postMessage(any(), any()) }
    }

    @Test
    fun `confirm without a response_url falls back to a channel post`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val draftId = givenDraft(msgId, predicted)

        confirm(draftId, predicted, responseUrl = null)

        // No card to replace — post to the draft's channel instead.
        coVerify(exactly = 1) { slack.postMessage("C1", match { "Recorded" in it }) }
        coVerify(exactly = 0) { slack.replaceCard(any(), any()) }
    }
}
