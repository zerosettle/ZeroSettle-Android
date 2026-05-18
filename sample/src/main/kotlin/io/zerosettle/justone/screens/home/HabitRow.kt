package io.zerosettle.justone.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.Completion
import io.zerosettle.justone.data.Habit
import io.zerosettle.justone.data.currentStreak
import io.zerosettle.justone.data.dateKey
import io.zerosettle.justone.data.heatmapIntensity
import io.zerosettle.justone.data.startOfWeek
import java.time.LocalDate

private val MINI_CELL_SIZE = 12.dp
private val MINI_CELL_SPACING = 3.dp
private val MINI_CELL_CORNER = 4.dp

/**
 * A single habit list row — a Card showing the habit name, a 7-cell mini-heatmap for
 * the current Mon–Sun week, the current streak, and an animated "log today" check button.
 *
 * Pure UI: no Room/SDK access. All data and callbacks are passed in.
 *
 * @param habit         The habit to display.
 * @param completions   All completions for this habit (used for streak + heatmap).
 * @param completedToday Whether the habit has been logged today.
 * @param onToggleToday Called when the circular check button is tapped.
 * @param onOpen        Called when the card body is tapped (not the check button).
 */
@Composable
fun HabitRow(
    habit: Habit,
    completions: List<Completion>,
    completedToday: Boolean,
    onToggleToday: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Compute per-day count map once per completions change.
    val byDay: Map<String, Int> = remember(completions) {
        completions.groupingBy { it.dateKey }.eachCount()
    }

    val today = LocalDate.now()
    val weekStart = remember(today) { startOfWeek(today) }
    val streak = remember(completions, habit.frequencyPerWeek) {
        currentStreak(completions, habit.frequencyPerWeek, today)
    }

    // Completion-tap animation (~15 LOC as per spec §6).
    val scale by animateFloatAsState(
        targetValue = if (completedToday) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "checkScale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left/main section: name + mini-heatmap + streak
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )

                // 7-cell mini-heatmap for the current Mon–Sun week
                Row(horizontalArrangement = Arrangement.spacedBy(MINI_CELL_SPACING)) {
                    for (dayOffset in 0..6) {
                        val date = weekStart.plusDays(dayOffset.toLong())
                        val key = dateKey(date)
                        val intensity = heatmapIntensity(byDay, key)
                        val cellColor = heatmapCellColor(intensity)
                        Box(
                            modifier = Modifier
                                .size(MINI_CELL_SIZE)
                                .clip(RoundedCornerShape(MINI_CELL_CORNER))
                                .background(cellColor),
                        )
                    }
                }

                if (streak > 0) {
                    Text(
                        text = "🔥 $streak-week streak",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.size(12.dp))

            // Right: animated circular "log today" check button.
            // IconButton intercepts taps so they never bubble to the Card's clickable.
            CheckButton(
                completedToday = completedToday,
                scale = scale,
                onToggleToday = onToggleToday,
            )
        }
    }
}

/**
 * Extracted to its own function to give [AnimatedVisibility] a clean receiver scope —
 * inside the outer [Row] content lambda the compiler would otherwise resolve to the
 * RowScope overload of AnimatedVisibility, which is not what we want here.
 */
@Composable
private fun CheckButton(
    completedToday: Boolean,
    scale: Float,
    onToggleToday: () -> Unit,
) {
    IconButton(
        onClick = onToggleToday,
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Background circle: primary when checked, surfaceVariant when not.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (completedToday)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )

            // Checkmark fades in when completed. No RowScope/ColumnScope ambiguity here.
            AnimatedVisibility(
                visible = completedToday,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Logged today",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
