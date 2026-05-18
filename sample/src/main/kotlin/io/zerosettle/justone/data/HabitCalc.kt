package io.zerosettle.justone.data

import java.time.DayOfWeek
import java.time.LocalDate

/** "YYYY-MM-DD" in the device-local timezone. */
fun dateKey(date: LocalDate): String = date.toString()

/** Monday-anchored start of the week containing [date]. */
fun startOfWeek(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Count of completions whose dateKey falls in the Mon–Sun week containing [weekOf]. */
fun completionsInWeek(completions: List<Completion>, weekOf: LocalDate): Int {
    val start = startOfWeek(weekOf)
    val end = start.plusDays(6)
    return completions.count {
        val d = LocalDate.parse(it.dateKey)
        !d.isBefore(start) && !d.isAfter(end)
    }
}

/**
 * Count of consecutive weeks (ending with the week containing [today]) in which the
 * habit met [frequencyPerWeek]. The current week, if still short, does not break the
 * streak (it is in progress) — it simply isn't counted yet.
 */
fun currentStreak(
    completions: List<Completion>,
    frequencyPerWeek: Int,
    today: LocalDate,
): Int {
    if (completions.isEmpty()) return 0
    val earliestWeek = startOfWeek(completions.minOf { LocalDate.parse(it.dateKey) })
    var week = startOfWeek(today)
    var streak = 0
    var isCurrentWeek = true
    while (!week.isBefore(earliestWeek)) {
        val met = completionsInWeek(completions, week) >= frequencyPerWeek
        if (met) {
            streak++
        } else if (!isCurrentWeek) {
            break
        }
        isCurrentWeek = false
        week = week.minusWeeks(1)
    }
    return streak
}
