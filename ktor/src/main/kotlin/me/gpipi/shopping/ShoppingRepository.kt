package me.gpipi.shopping

import java.time.OffsetDateTime
import java.util.UUID
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingAddDraftItem
import me.gpipi.generated.db.base.public1.ShoppingItem
import me.gpipi.generated.db.base.public1.ShoppingMutation
import me.gpipi.generated.db.base.public1.ShoppingMutationItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

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

data class ShoppingItemRow(
    val id: UUID,
    val inboundMessageId: UUID,
    val item: String,
    val quantity: String?,
    val note: String?,
    val status: String,
    val addedBy: String,
    val addedAt: OffsetDateTime,
    val boughtBy: String?,
    val boughtAt: OffsetDateTime?,
    val removedBy: String?,
    val removedAt: OffsetDateTime?,
    val currentMutationId: UUID,
)

data class ShoppingMutationRow(
    val id: UUID,
    val kind: String,
    val actorId: String,
    val reversesMutationId: UUID?,
    val createdAt: OffsetDateTime,
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

    fun consumeAddDraft(
        id: UUID,
        status: String,
        completedAt: OffsetDateTime,
    ): ShoppingAddDraftRow? {
        require(status == "CONFIRMED" || status == "CANCELLED")

        ShoppingAddDraft.updateReturning(
            returning = listOf(ShoppingAddDraft.id),
            where = {
                (ShoppingAddDraft.id eq id) and
                    (ShoppingAddDraft.status eq "PENDING")
            },
        ) {
            it[ShoppingAddDraft.status] = status
            it[ShoppingAddDraft.completedAt] = completedAt
        }.singleOrNull() ?: return null

        return findAddDraft(id)
    }

    /**
     * Serializes operations that can create or restore the same normalized pending identity.
     * Callers must provide keys in any order; this method sorts them to prevent lock-order cycles.
     */
    fun lockIdentityKeys(keys: Collection<String>) {
        val transaction = checkNotNull(TransactionManager.currentOrNull())
        keys.distinct().sorted().forEach { key ->
            transaction.exec(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                args = listOf(TextColumnType() to key),
            )
        }
    }

    fun createMutation(
        kind: String,
        actorId: String,
        reversesMutationId: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        ShoppingMutation.insert {
            it[ShoppingMutation.id] = id
            it[ShoppingMutation.kind] = kind
            it[ShoppingMutation.actorId] = actorId
            it[ShoppingMutation.reversesMutationId] = reversesMutationId
        }
        return id
    }

    fun createInverseMutationIfFirst(
        kind: String,
        actorId: String,
        reversesMutationId: UUID,
    ): UUID? {
        val id = UUID.randomUUID()
        val inserted = ShoppingMutation.insertIgnore {
            it[ShoppingMutation.id] = id
            it[ShoppingMutation.kind] = kind
            it[ShoppingMutation.actorId] = actorId
            it[ShoppingMutation.reversesMutationId] = reversesMutationId
        }
        return id.takeIf { inserted.insertedCount == 1 }
    }

    fun discardEmptyMutation(id: UUID) {
        ShoppingMutation.deleteWhere { ShoppingMutation.id eq id }
    }

    fun findMutation(id: UUID): ShoppingMutationRow? =
        ShoppingMutation.selectAll()
            .where { ShoppingMutation.id eq id }
            .singleOrNull()
            ?.let(::toMutationRow)

    fun linkMutationItems(mutationId: UUID, itemIds: Collection<UUID>) {
        ShoppingMutationItem.batchInsert(itemIds.distinct()) { itemId ->
            this[ShoppingMutationItem.mutationId] = mutationId
            this[ShoppingMutationItem.itemId] = itemId
        }
    }

    fun listMutationItems(mutationId: UUID): List<ShoppingItemRow> {
        val ids = ShoppingMutationItem.selectAll()
            .where { ShoppingMutationItem.mutationId eq mutationId }
            .map { it[ShoppingMutationItem.itemId] }
        return listItems(ids)
    }

    fun insertPendingItemIgnore(
        inboundMessageId: UUID,
        addedBy: String,
        mutationId: UUID,
        input: ShoppingDraftItemInput,
    ): ShoppingItemRow? {
        val id = UUID.randomUUID()
        val inserted = ShoppingItem.insertIgnore {
            it[ShoppingItem.id] = id
            it[ShoppingItem.inboundMessageId] = inboundMessageId
            it[ShoppingItem.item] = input.item
            it[ShoppingItem.quantity] = input.quantity
            it[ShoppingItem.note] = input.note
            it[ShoppingItem.addedBy] = addedBy
            it[ShoppingItem.currentMutationId] = mutationId
        }
        return if (inserted.insertedCount == 1) {
            findItem(id)
        } else {
            null
        }
    }

    fun findItem(id: UUID): ShoppingItemRow? =
        ShoppingItem.selectAll()
            .where { ShoppingItem.id eq id }
            .singleOrNull()
            ?.let(::toItemRow)

    fun listItems(ids: Collection<UUID>): List<ShoppingItemRow> {
        if (ids.isEmpty()) return emptyList()
        return ShoppingItem.selectAll()
            .where { ShoppingItem.id inList ids.distinct() }
            .orderBy(ShoppingItem.id to SortOrder.ASC)
            .map(::toItemRow)
    }

    fun listPendingItems(): List<ShoppingItemRow> =
        ShoppingItem.selectAll()
            .where { ShoppingItem.status eq "PENDING" }
            .orderBy(
                ShoppingItem.addedAt to SortOrder.ASC,
                ShoppingItem.id to SortOrder.ASC,
            )
            .map(::toItemRow)

    fun listAllItems(): List<ShoppingItemRow> =
        ShoppingItem.selectAll()
            .orderBy(
                ShoppingItem.addedAt to SortOrder.DESC,
                ShoppingItem.id to SortOrder.DESC,
            )
            .map(::toItemRow)

    fun editPendingItem(
        id: UUID,
        expectedMutationId: UUID,
        mutationId: UUID,
        input: ShoppingDraftItemInput,
    ): ShoppingItemRow? =
        ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id eq id) and
                    (ShoppingItem.status eq "PENDING") and
                    (ShoppingItem.currentMutationId eq expectedMutationId)
            },
        ) {
            it[ShoppingItem.item] = input.item
            it[ShoppingItem.quantity] = input.quantity
            it[ShoppingItem.note] = input.note
            it[ShoppingItem.currentMutationId] = mutationId
        }.singleOrNull()?.let(::toItemRow)

    fun removePendingItem(
        id: UUID,
        expectedMutationId: UUID,
        mutationId: UUID,
        actorId: String,
        occurredAt: OffsetDateTime,
    ): ShoppingItemRow? =
        ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id eq id) and
                    (ShoppingItem.status eq "PENDING") and
                    (ShoppingItem.currentMutationId eq expectedMutationId)
            },
        ) {
            it[ShoppingItem.status] = "REMOVED"
            it[ShoppingItem.removedBy] = actorId
            it[ShoppingItem.removedAt] = occurredAt
            it[ShoppingItem.currentMutationId] = mutationId
        }.singleOrNull()?.let(::toItemRow)

    fun restoreRemovedItem(
        id: UUID,
        expectedMutationId: UUID,
        mutationId: UUID,
    ): ShoppingItemRow? =
        ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id eq id) and
                    (ShoppingItem.status eq "REMOVED") and
                    (ShoppingItem.currentMutationId eq expectedMutationId)
            },
        ) {
            it[ShoppingItem.status] = "PENDING"
            it[ShoppingItem.removedBy] = null
            it[ShoppingItem.removedAt] = null
            it[ShoppingItem.currentMutationId] = mutationId
        }.singleOrNull()?.let(::toItemRow)

    fun markBought(
        ids: Collection<UUID>,
        actorId: String,
        mutationId: UUID,
        occurredAt: OffsetDateTime,
    ): List<ShoppingItemRow> {
        if (ids.isEmpty()) return emptyList()
        return ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id inList ids.distinct()) and
                    (ShoppingItem.status eq "PENDING")
            },
        ) {
            it[ShoppingItem.status] = "BOUGHT"
            it[ShoppingItem.boughtBy] = actorId
            it[ShoppingItem.boughtAt] = occurredAt
            it[ShoppingItem.currentMutationId] = mutationId
        }.map(::toItemRow)
    }

    fun undoAdd(
        ids: Collection<UUID>,
        targetMutationId: UUID,
        inverseMutationId: UUID,
        actorId: String,
        occurredAt: OffsetDateTime,
    ): List<ShoppingItemRow> {
        if (ids.isEmpty()) return emptyList()
        return ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id inList ids.distinct()) and
                    (ShoppingItem.status eq "PENDING") and
                    (ShoppingItem.currentMutationId eq targetMutationId)
            },
        ) {
            it[ShoppingItem.status] = "REMOVED"
            it[ShoppingItem.removedBy] = actorId
            it[ShoppingItem.removedAt] = occurredAt
            it[ShoppingItem.currentMutationId] = inverseMutationId
        }.map(::toItemRow)
    }

    fun undoBought(
        ids: Collection<UUID>,
        targetMutationId: UUID,
        inverseMutationId: UUID,
    ): List<ShoppingItemRow> {
        if (ids.isEmpty()) return emptyList()
        return ShoppingItem.updateReturning(
            where = {
                (ShoppingItem.id inList ids.distinct()) and
                    (ShoppingItem.status eq "BOUGHT") and
                    (ShoppingItem.currentMutationId eq targetMutationId)
            },
        ) {
            it[ShoppingItem.status] = "PENDING"
            it[ShoppingItem.boughtBy] = null
            it[ShoppingItem.boughtAt] = null
            it[ShoppingItem.currentMutationId] = inverseMutationId
        }.map(::toItemRow)
    }

    private fun toItemRow(r: org.jetbrains.exposed.v1.core.ResultRow) =
        ShoppingItemRow(
            id = r[ShoppingItem.id],
            inboundMessageId = r[ShoppingItem.inboundMessageId],
            item = r[ShoppingItem.item],
            quantity = r[ShoppingItem.quantity],
            note = r[ShoppingItem.note],
            status = r[ShoppingItem.status],
            addedBy = r[ShoppingItem.addedBy],
            addedAt = r[ShoppingItem.addedAt],
            boughtBy = r[ShoppingItem.boughtBy],
            boughtAt = r[ShoppingItem.boughtAt],
            removedBy = r[ShoppingItem.removedBy],
            removedAt = r[ShoppingItem.removedAt],
            currentMutationId = r[ShoppingItem.currentMutationId],
        )

    private fun toMutationRow(r: org.jetbrains.exposed.v1.core.ResultRow) =
        ShoppingMutationRow(
            id = r[ShoppingMutation.id],
            kind = r[ShoppingMutation.kind],
            actorId = r[ShoppingMutation.actorId],
            reversesMutationId = r[ShoppingMutation.reversesMutationId],
            createdAt = r[ShoppingMutation.createdAt],
        )
}
