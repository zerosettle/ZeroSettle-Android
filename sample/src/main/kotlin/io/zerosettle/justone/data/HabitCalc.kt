package io.zerosettle.justone.data

import java.time.LocalDate

/** "YYYY-MM-DD" in the device-local timezone. */
fun dateKey(date: LocalDate): String = date.toString()
