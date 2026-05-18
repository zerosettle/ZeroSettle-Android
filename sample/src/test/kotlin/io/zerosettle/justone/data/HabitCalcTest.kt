package io.zerosettle.justone.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HabitCalcTest {
    @Test fun dateKey_isIsoFormat() {
        assertThat(dateKey(LocalDate.of(2026, 5, 20))).isEqualTo("2026-05-20")
        assertThat(dateKey(LocalDate.of(2026, 1, 3))).isEqualTo("2026-01-03")
    }

    private fun c(habitId: String, day: String) = Completion(habitId, day, 0L)

    @Test fun startOfWeek_snapsToMonday() {
        // 2026-05-20 is a Wednesday; week starts Mon 2026-05-18.
        assertThat(startOfWeek(LocalDate.of(2026, 5, 20))).isEqualTo(LocalDate.of(2026, 5, 18))
        assertThat(startOfWeek(LocalDate.of(2026, 5, 18))).isEqualTo(LocalDate.of(2026, 5, 18))
    }

    @Test fun completionsInWeek_countsOnlyDaysInThatWeek() {
        val completions = listOf(
            c("h", "2026-05-18"), c("h", "2026-05-20"), c("h", "2026-05-24"), // week of May 18
            c("h", "2026-05-25"),                                            // next week
        )
        assertThat(completionsInWeek(completions, LocalDate.of(2026, 5, 20))).isEqualTo(3)
        assertThat(completionsInWeek(completions, LocalDate.of(2026, 5, 25))).isEqualTo(1)
    }
}
