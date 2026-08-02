package me.gpipi.category

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetPeriodTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun `monthly cycle ends exclusively at the Friday substituted for a Sunday payday`() {
        val beforePayday = BudgetPeriod.MONTHLY.bucketFor(LocalDate.of(2026, 10, 22), zone)
        val atPayday = BudgetPeriod.MONTHLY.bucketFor(LocalDate.of(2026, 10, 23), zone)

        assertEquals("2026-09-25T00:00+09:00", beforePayday.startInclusive.toString())
        assertEquals("2026-10-23T00:00+09:00", beforePayday.endExclusive.toString())
        assertEquals("2026-10-23T00:00+09:00", atPayday.startInclusive.toString())
        assertEquals("2026-11-25T00:00+09:00", atPayday.endExclusive.toString())
    }
}
