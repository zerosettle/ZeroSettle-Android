package io.zerosettle.justone.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.Completion
import io.zerosettle.justone.data.dateKey
import io.zerosettle.justone.data.heatmapIntensity
import java.time.DayOfWeek
import java.time.LocalDate

private val CELL_SIZE = 12.dp
private val CELL_SPACING = 3.dp
private val CELL_CORNER = 4.dp
private const val WEEKS = 12
private const val DAYS_PER_WEEK = 7

/**
 * Shared heatmap cell color: low-alpha primary tinted by [intensity] (0f..1f).
 *
 * Exposed as `internal` so HabitRow (Task 18) can reuse the same shading logic
 * without duplicating the formula.
 */
@Composable
internal fun heatmapCellColor(intensity: Float): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f + 0.85f * intensity.coerceIn(0f, 1f))

/**
 * GitHub-contributions-style heatmap over the last ~12 weeks, aggregated across
 * all habits.
 *
 * Grid layout: columns = weeks (oldest → newest, left → right),
 * rows = days of week (Monday top, Sunday bottom). Exactly [WEEKS] × [DAYS_PER_WEEK]
 * cells are rendered; the grid is Monday-aligned so the leftmost column always
 * starts on a Monday.
 *
 * Pure UI: receives [completions] as a parameter, no Room/SDK access.
 */
@Composable
fun AggregatedHeatmap(
    completions: List<Completion>,
    modifier: Modifier = Modifier,
) {
    // Build per-day count map once; re-compute only when completions list changes.
    val byDay: Map<String, Int> = remember(completions) {
        completions.groupingBy { it.dateKey }.eachCount()
    }

    // Determine grid start: Monday of the week that was (WEEKS-1) weeks ago.
    // LocalDate.now() → go back to the Monday of the current week, then back
    // another (WEEKS-1) weeks so the rightmost column is the current week.
    val today = LocalDate.now()
    val gridStart: LocalDate = remember(today) {
        val currentMonday = today.with(DayOfWeek.MONDAY)
        currentMonday.minusWeeks((WEEKS - 1).toLong())
    }

    // Outer Row = weeks (columns, oldest left)
    Row(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        repeat(WEEKS) { weekIndex ->
            // Inner Column = days within the week (Monday top, Sunday bottom)
            Column(verticalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
                repeat(DAYS_PER_WEEK) { dayIndex ->
                    val date = gridStart
                        .plusWeeks(weekIndex.toLong())
                        .plusDays(dayIndex.toLong())
                    val key = dateKey(date)
                    val intensity = heatmapIntensity(byDay, key)
                    val cellColor = heatmapCellColor(intensity)

                    Box(
                        modifier = Modifier
                            .size(CELL_SIZE)
                            .clip(RoundedCornerShape(CELL_CORNER))
                            .background(cellColor),
                    )
                }
            }
        }
    }
}
