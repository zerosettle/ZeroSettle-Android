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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.Habit
import io.zerosettle.justone.data.UserPrefs
import kotlinx.coroutines.launch

@Composable
fun StreakSaverSection(habit: Habit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val count by UserPrefs(ctx).streakSaverCount.collectAsState(initial = 0)
    if (count == 0) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Streak Saver",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "You have $count streak saver${if (count == 1) "" else "s"}. " +
                    "Using one protects a missed week from breaking your streak on \"${habit.name}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { scope.launch { ctx.let { UserPrefs(it).useStreakSaver() } } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use streak saver")
            }
        }
    }
}
