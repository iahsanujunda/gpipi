package me.gpipi.shopping

import java.time.OffsetDateTime
import java.util.UUID
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingAddDraftItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

data class ShoppingDraftItemInput(
    val item: String,
    val quantity: String? = null,
    val note: String? = null,
)

data class ShoppingAddDraftItemRow(
    val id: UUID,
    val position: Int,
    val item: String,
    val quantity: String?,
    val note: String?,
)

data class ShoppingAddDraftRow(
    val id: UUID,
    val inboundMessageId: UUID,
    val userId: String,
    val channelId: String,
    val status: String,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val items: List<ShoppingAddDraftItemRow>,
)

class ShoppingRepository {
    fun insertAddDraft(
        inboundMessageId: UUID,
        userId: String,
        channelId: String,
        items: List<ShoppingDraftItemInput>,
    ): UUID {
        require(items.isNotEmpty()) { "a shopping add draft must have at least one item" }

        val id = UUID.randomUUID()
        ShoppingAddDraft.insert {
            it[ShoppingAddDraft.id]               = id
            it[ShoppingAddDraft.inboundMessageId] = inboundMessageId
            it[ShoppingAddDraft.userId]           = userId
            it[ShoppingAddDraft.channelId]        = channelId
        }

        items.forEachIndexed { index, input ->
            ShoppingAddDraftItem.insert {
                it[ShoppingAddDraftItem.id]       = UUID.randomUUID()
                it[ShoppingAddDraftItem.draftId]  = id
                it[ShoppingAddDraftItem.position] = index
                it[ShoppingAddDraftItem.item]     = input.item
                it[ShoppingAddDraftItem.quantity] = input.quantity
                it[ShoppingAddDraftItem.note]     = input.note
            }
        }
        return id
    }

    fun findAddDraft(id: UUID): ShoppingAddDraftRow? {
        val draft = ShoppingAddDraft
            .selectAll()
            .where { ShoppingAddDraft.id eq id }
            .singleOrNull()
            ?: return null

        val items = ShoppingAddDraftItem
            .selectAll()
            .where { ShoppingAddDraftItem.draftId eq id }
            .orderBy(ShoppingAddDraftItem.position to SortOrder.ASC)
            .map { r ->
                ShoppingAddDraftItemRow(
                    id = r[ShoppingAddDraftItem.id],
                    position = r[ShoppingAddDraftItem.position],
                    item = r[ShoppingAddDraftItem.item],
                    quantity = r[ShoppingAddDraftItem.quantity],
                    note = r[ShoppingAddDraftItem.note],
                )
            }

        return ShoppingAddDraftRow(
            id = draft[ShoppingAddDraft.id],
            inboundMessageId = draft[ShoppingAddDraft.inboundMessageId],
            userId = draft[ShoppingAddDraft.userId],
            channelId = draft[ShoppingAddDraft.channelId],
            status = draft[ShoppingAddDraft.status],
            createdAt = draft[ShoppingAddDraft.createdAt],
            completedAt = draft[ShoppingAddDraft.completedAt],
            items = items,
        )
    }
}
