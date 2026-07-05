package me.gpipi.inbound

import java.util.UUID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.update

enum class InboundStatus { RECEIVED, RECORDED, FAILED_PARSE, NON_EXPENSE, SKIPPED}

class InboundRepository {
    fun captureOrSkip(eventId: String, userId: String, channelId: String, text: String?, slackTs: String): UUID? =
        InboundMessages.insertIgnoreAndGetId {
            it[InboundMessages.eventId] = eventId
            it[InboundMessages.userId] = userId
            it[InboundMessages.channelId] = channelId
            it[InboundMessages.text] = text
            it[InboundMessages.slackTs] = slackTs
        }?.value

    fun markFailed(id: UUID, reason: String?) {
        InboundMessages.update({ InboundMessages.id eq id }) {
            it[status] = InboundStatus.FAILED_PARSE.name
            it[failReason] = reason
        }
    }

    fun markRecorded(id: UUID) {
        InboundMessages.update({ InboundMessages.id eq id }) {
            it[status] = InboundStatus.RECORDED.name
        }
    }
}