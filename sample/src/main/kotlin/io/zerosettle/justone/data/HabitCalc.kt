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
