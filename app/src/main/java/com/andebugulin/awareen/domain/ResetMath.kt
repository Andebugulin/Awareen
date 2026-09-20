package com.andebugulin.awareen.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Wall-clock math for the daily reset.
 *
 * Deliberately free of Android types and of any reference to the current time:
 * `now` and the zone are always parameters. The reset is the app's core
 * correctness claim and has three independent trigger paths that must agree,
 * so it has to be exercisable at arbitrary instants — DST transitions, a reset
 * time of exactly 00:00, a clock moved backwards — none of which can be
 * reproduced against a live device clock.
 *
 * Day arithmetic is calendar-day based (`plusDays`/`minusDays`), not 24-hour
 * based, so a DST day is still one day. On a spring-forward gap the requested
 * local time does not exist; `atZone` shifts forward by the gap, which keeps
 * exactly one reset instant per day.
 */
object ResetMath {

    /** Most recent instant matching [hour]:[minute] that is <= [nowMillis]. */
    fun mostRecentReset(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayReset = resetOn(today, hour, minute, zone)
        return if (todayReset > nowMillis) resetOn(today.minusDays(1), hour, minute, zone)
        else todayReset
    }

    /** Next instant matching [hour]:[minute] that is strictly > [nowMillis]. */
    fun nextReset(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayReset = resetOn(today, hour, minute, zone)
        return if (todayReset <= nowMillis) resetOn(today.plusDays(1), hour, minute, zone)
        else todayReset
    }

    /**
     * True when a scheduled reset moment has passed since the last actual
     * reset. Deterministic — no grace periods.
     */
    fun shouldReset(
        lastResetMillis: Long,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId,
    ): Boolean = lastResetMillis < mostRecentReset(nowMillis, hour, minute, zone)

    /**
     * Stored hour/minute are coerced rather than trusted. A corrupt or
     * hand-edited preference reaches this on the service's one-second tick, so
     * an out-of-range value must degrade to a valid reset time rather than
     * raise and take the loop down.
     */
    private fun resetOn(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
