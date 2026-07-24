package me.gpipi.category

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class BudgetBucket(
    val startInclusive: OffsetDateTime,
    val endExclusive: OffsetDateTime,
)

enum class BudgetPeriod {
    WEEKLY,
    MONTHLY;

    fun bucketFor(
        date: LocalDate,
        zone: ZoneId,
    ): BudgetBucket {
        val startDate = when (this) {
            WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MONTHLY -> date.withDayOfMonth(1)
        }

        val endDate = when (this) {
            WEEKLY -> startDate.plusWeeks(1)
            MONTHLY -> startDate.plusMonths(1)
        }

        return BudgetBucket(
            startInclusive = startDate.atStartOfDay(zone).toOffsetDateTime(),
            endExclusive = endDate.atStartOfDay(zone).toOffsetDateTime(),
        )
    }

    companion object {
        fun from(raw: String): BudgetPeriod? =
            entries.firstOrNull { it.name == raw }
    }
}
