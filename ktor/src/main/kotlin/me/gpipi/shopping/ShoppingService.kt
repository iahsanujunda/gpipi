package me.gpipi.shopping

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database

data class ShoppingItemText(
    val item: String,
    val quantity: String? = null,
    val note: String? = null,
)

data class ShoppingMutationResult(
    val changed: List<ShoppingItemText>,
    val skipped: List<ShoppingItemText>,
    val mutationId: UUID?,
)

data class ShoppingUndoResult(
    val targetKind: String,
    val changed: List<ShoppingItemText>,
    val skipped: List<ShoppingItemText>,
    val mutationId: UUID?,
)

class ShoppingService(
    private val db: Database,
    private val repository: ShoppingRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun listPending(): List<ShoppingItemRow> =
        dbQuery(db) { repository.listPendingItems() }

    suspend fun confirmAdd(
        draftId: UUID,
        actorId: String,
    ): ShoppingMutationResult? = dbQuery(db) {
        val draft = repository.consumeAddDraft(draftId, "CONFIRMED", now())
            ?: return@dbQuery null

        val skipped = mutableListOf<ShoppingItemText>()
        val unique = LinkedHashMap<String, ShoppingDraftItemInput>()
        draft.items.forEach { row ->
            val normalized = normalize(
                ShoppingDraftItemInput(row.item, row.quantity, row.note),
            )
            val previous = unique.putIfAbsent(identityKey(normalized), normalized)
            if (previous != null) skipped += normalized.toText()
        }

        repository.lockIdentityKeys(unique.keys)
        val mutationId = repository.createMutation("ADD", actorId)
        val addedRows = mutableListOf<ShoppingItemRow>()
        unique.values.forEach { input ->
            val inserted = repository.insertPendingItemIgnore(
                inboundMessageId = draft.inboundMessageId,
                addedBy = draft.userId,
                mutationId = mutationId,
                input = input,
            )
            if (inserted == null) skipped += input.toText()
            else addedRows += inserted
        }

        val durableMutationId = if (addedRows.isEmpty()) {
            repository.discardEmptyMutation(mutationId)
            null
        } else {
            repository.linkMutationItems(mutationId, addedRows.map { it.id })
            mutationId
        }

        ShoppingMutationResult(
            changed = addedRows.map(ShoppingItemRow::toText),
            skipped = skipped,
            mutationId = durableMutationId,
        )
    }

    suspend fun cancelAdd(draftId: UUID): Boolean =
        dbQuery(db) {
            repository.consumeAddDraft(draftId, "CANCELLED", now()) != null
        }

    suspend fun markBought(
        itemIds: Collection<UUID>,
        actorId: String,
    ): ShoppingMutationResult = dbQuery(db) {
        val requested = repository.listItems(itemIds.distinct())
        repository.lockIdentityKeys(requested.map(::identityKey))

        val mutationId = repository.createMutation("MARK_BOUGHT", actorId)
        val changedRows = repository.markBought(
            ids = itemIds,
            actorId = actorId,
            mutationId = mutationId,
            occurredAt = now(),
        )
        val changedIds = changedRows.mapTo(hashSetOf()) { it.id }
        val skipped = requested.filterNot { it.id in changedIds }

        val durableMutationId = if (changedRows.isEmpty()) {
            repository.discardEmptyMutation(mutationId)
            null
        } else {
            repository.linkMutationItems(mutationId, changedRows.map { it.id })
            mutationId
        }

        ShoppingMutationResult(
            changed = changedRows.map(ShoppingItemRow::toText),
            skipped = skipped.map(ShoppingItemRow::toText),
            mutationId = durableMutationId,
        )
    }

    suspend fun undo(
        targetMutationId: UUID,
        actorId: String,
    ): ShoppingUndoResult? = dbQuery(db) {
        val target = repository.findMutation(targetMutationId)
            ?: return@dbQuery null
        val inverseKind = when (target.kind) {
            "ADD" -> "UNDO_ADD"
            "MARK_BOUGHT" -> "UNDO_BOUGHT"
            else -> return@dbQuery null
        }

        val targetItems = repository.listMutationItems(targetMutationId)
        repository.lockIdentityKeys(targetItems.map(::identityKey))

        val inverseId = repository.createInverseMutationIfFirst(
            kind = inverseKind,
            actorId = actorId,
            reversesMutationId = targetMutationId,
        ) ?: return@dbQuery null

        val currentItems = repository.listItems(targetItems.map { it.id })
        val eligibleIds = when (target.kind) {
            "ADD" -> currentItems
                .filter {
                    it.status == "PENDING" &&
                        it.currentMutationId == targetMutationId
                }
                .map { it.id }

            "MARK_BOUGHT" -> {
                val pendingByIdentity = repository.listPendingItems()
                    .groupBy(::identityKey)
                currentItems
                    .filter {
                        it.status == "BOUGHT" &&
                            it.currentMutationId == targetMutationId &&
                            pendingByIdentity[identityKey(it)].isNullOrEmpty()
                    }
                    .map { it.id }
            }

            else -> emptyList()
        }

        val changedRows = when (target.kind) {
            "ADD" -> repository.undoAdd(
                ids = eligibleIds,
                targetMutationId = targetMutationId,
                inverseMutationId = inverseId,
                actorId = actorId,
                occurredAt = now(),
            )

            "MARK_BOUGHT" -> repository.undoBought(
                ids = eligibleIds,
                targetMutationId = targetMutationId,
                inverseMutationId = inverseId,
            )

            else -> emptyList()
        }

        val changedIds = changedRows.mapTo(hashSetOf()) { it.id }
        val skippedRows = currentItems.filterNot { it.id in changedIds }
        val durableMutationId = if (changedRows.isEmpty()) {
            repository.discardEmptyMutation(inverseId)
            null
        } else {
            repository.linkMutationItems(inverseId, changedRows.map { it.id })
            inverseId
        }

        ShoppingUndoResult(
            targetKind = target.kind,
            changed = changedRows.map(ShoppingItemRow::toText),
            skipped = skippedRows.map(ShoppingItemRow::toText),
            mutationId = durableMutationId,
        )
    }

    private fun now(): OffsetDateTime =
        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}

private fun normalize(input: ShoppingDraftItemInput) =
    ShoppingDraftItemInput(
        item = input.item.trim(),
        quantity = input.quantity?.trim()?.takeIf(String::isNotEmpty),
        note = input.note?.trim()?.takeIf(String::isNotEmpty),
    )

private fun identityKey(input: ShoppingDraftItemInput): String =
    listOf(input.item, input.quantity.orEmpty(), input.note.orEmpty())
        .joinToString("\u001f") { it.trim().lowercase(Locale.ROOT) }

private fun identityKey(row: ShoppingItemRow): String =
    identityKey(ShoppingDraftItemInput(row.item, row.quantity, row.note))

private fun ShoppingDraftItemInput.toText() =
    ShoppingItemText(item, quantity, note)

private fun ShoppingItemRow.toText() =
    ShoppingItemText(item, quantity, note)
