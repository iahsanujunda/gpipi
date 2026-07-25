package me.gpipi.inbound

import java.util.UUID
import me.gpipi.generated.db.base.public1.InboundMessage
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update

enum class InboundStatus {
    RECEIVED,
    RECORDED,
    COMMAND,
    FAILED_COMMAND,
    FAILED_PARSE,
    NON_EXPENSE,
    SKIPPED,
}

class InboundRepository {
    fun captureOrSkip(eventId: String, userId: String, channelId: String, text: String?, slackTs: String): UUID? {
        val id = UUID.randomUUID()
        val inserted = InboundMessage.insertIgnore {
            it[InboundMessage.id]        = id
            it[InboundMessage.eventId]   = eventId
            it[InboundMessage.userId]    = userId
            it[InboundMessage.channelId] = channelId
            it[InboundMessage.text]      = text
            it[InboundMessage.slackTs]   = slackTs
        }.insertedCount
        return if (inserted > 0) id else null
    }

    fun markFailed(id: UUID, reason: String?) {
        InboundMessage.update({ InboundMessage.id eq id }) {
            it[status]     = InboundStatus.FAILED_PARSE.name
            it[failReason] = reason
        }
    }

    fun markRecorded(id: UUID) {
        InboundMessage.update({ InboundMessage.id eq id }) {
            it[status] = InboundStatus.RECORDED.name
        }
    }

    fun markCommand(id: UUID) {
        InboundMessage.update({
            (InboundMessage.id eq id) and
                (InboundMessage.status eq InboundStatus.RECEIVED.name)
        }) {
            it[status] = InboundStatus.COMMAND.name
            it[failReason] = null
        }
    }

    fun markCommandFailed(id: UUID, reason: String) {
        InboundMessage.update({
            (InboundMessage.id eq id) and
                (InboundMessage.status eq InboundStatus.RECEIVED.name)
        }) {
            it[status] = InboundStatus.FAILED_COMMAND.name
            it[failReason] = reason
        }
    }
}
