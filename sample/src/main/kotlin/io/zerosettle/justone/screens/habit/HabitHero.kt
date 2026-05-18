package io.zerosettle.justone.screens.habit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.Habit

@Composable
fun HabitHero(
    habit: Habit,
    completedToday: Boolean,
    streak: Int,
    onToggleToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = habit.name,
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (completedToday) "Done today ✓" else "Not logged yet",
                style = MaterialTheme.typography.bodyMedium,
                color = if (completedToday)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (streak > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$streak-week streak",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onToggleToday,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (completedToday) "Logged ✓" else "Log today")
            }
        }
    }
}
