package me.gpipi.categorization

import java.util.UUID
import me.gpipi.generated.db.base.public1.CategorizationEvent
import org.jetbrains.exposed.v1.jdbc.insert

class CategorizationEventRepository {
    fun insert(
        inboundMessageId: UUID,
        expenseId: UUID,
        predictedCategoryId: UUID,
        finalCategoryId: UUID,
        confidence: Double?,
        model: String?,
    ): UUID {
        val id = UUID.randomUUID()
        CategorizationEvent.insert {
            it[CategorizationEvent.id] = id
            it[CategorizationEvent.inboundMessageId] = inboundMessageId
            it[CategorizationEvent.expenseId] = expenseId
            it[CategorizationEvent.predictedCategoryId] = predictedCategoryId
            it[CategorizationEvent.finalCategoryId] = finalCategoryId
            it[CategorizationEvent.wasCorrected] = predictedCategoryId  != finalCategoryId
            it[CategorizationEvent.confidence] = confidence?.toBigDecimal()
            it[CategorizationEvent.model] = model
        }
        return id
    }
}