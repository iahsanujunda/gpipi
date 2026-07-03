package me.gpipi.inbound

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object InboundMessages : UUIDTable("inbound_message") {
    val eventId     = text("event_id").uniqueIndex()
    val userId      = text("user_id")
    val channelId   = text("channel_id")
    val text        = text("text").nullable()
    val slackTs     = text("slack_ts")
    val status      = text("status").default("RECEIVED")
    val failReason  = text("fail_reason").nullable()
    val receivedAt  = timestampWithTimeZone("received_at").defaultExpression(CurrentTimestampWithTimeZone)
}