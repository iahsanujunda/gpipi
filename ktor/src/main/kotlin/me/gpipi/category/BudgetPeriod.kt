package me.gpipi.category

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.YearMonth
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
            MONTHLY -> monthlyPaydayStartFor(date)
        }

        val endDate = when (this) {
            WEEKLY -> startDate.plusWeeks(1)
            MONTHLY -> paydayFor(YearMonth.from(startDate).plusMonths(1))
        }

        return BudgetBucket(
            startInclusive = startDate.atStartOfDay(zone).toOffsetDateTime(),
            endExclusive = endDate.atStartOfDay(zone).toOffsetDateTime(),
        )
    }

    companion object {
        private const val PAYDAY_DAY_OF_MONTH = 25

        fun from(raw: String): BudgetPeriod? =
            entries.firstOrNull { it.name == raw }

        /**
         * The household's monthly budget cycle begins on payday: the 25th, or the
         * preceding Friday when the 25th falls on a weekend.
         */
        private fun monthlyPaydayStartFor(date: LocalDate): LocalDate {
            val paydayThisMonth = paydayFor(YearMonth.from(date))
            return if (date < paydayThisMonth) {
                paydayFor(YearMonth.from(date).minusMonths(1))
            } else {
                paydayThisMonth
            }
        }

        private fun paydayFor(month: YearMonth): LocalDate =
            month.atDay(PAYDAY_DAY_OF_MONTH).let { nominalPayday ->
                when (nominalPayday.dayOfWeek) {
                    DayOfWeek.SATURDAY -> nominalPayday.minusDays(1)
                    DayOfWeek.SUNDAY -> nominalPayday.minusDays(2)
                    else -> nominalPayday
                }
            }
    }
}
