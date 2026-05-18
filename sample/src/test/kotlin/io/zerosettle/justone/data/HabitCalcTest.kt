package io.zerosettle.justone.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HabitCalcTest {
    @Test fun dateKey_isIsoFormat() {
        assertThat(dateKey(LocalDate.of(2026, 5, 20))).isEqualTo("2026-05-20")
        assertThat(dateKey(LocalDate.of(2026, 1, 3))).isEqualTo("2026-01-03")
    }
}
