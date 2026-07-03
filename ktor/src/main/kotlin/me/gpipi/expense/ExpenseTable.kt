package me.gpipi.expense

import me.gpipi.inbound.InboundMessages
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object Expenses : UUIDTable("expense") {
    val inboundMessageId = reference("inbound_message_id", InboundMessages)
    val userId    = text("user_id")
    val amount    = long("amount")
    val currency  = text("currency").default("JPY")
    val category  = text("category")
    val merchant  = text("merchant").nullable()
    val note      = text("note").nullable()
    val spentAt   = timestampWithTimeZone("spent_at").defaultExpression(CurrentTimestampWithTimeZone)
    val sourceCol = text("source").default("SLACK")   // property renamed: `source` clashes with ColumnSet.source
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
}