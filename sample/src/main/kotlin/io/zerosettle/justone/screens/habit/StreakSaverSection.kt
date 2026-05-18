package io.zerosettle.justone.screens.habit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import io.zerosettle.justone.data.Habit

@Composable
fun StreakSaverSection(habit: Habit, modifier: Modifier = Modifier) {
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val streakSavers = entitlements.filter { it.isActive && it.productType == "consumable" }
    if (streakSavers.isEmpty()) return

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
                text = "You have ${streakSavers.size} streak saver${if (streakSavers.size == 1) "" else "s"} — " +
                    "they protect a missed week from breaking your streak on \"${habit.name}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
