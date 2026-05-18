package io.zerosettle.justone.screens.habit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.Completion
import io.zerosettle.justone.data.Db
import io.zerosettle.justone.data.completionsInWeek
import io.zerosettle.justone.data.currentStreak
import io.zerosettle.justone.data.dateKey
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(habitId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val habit by Db.get(ctx).habitDao().observe(habitId).collectAsState(initial = null)
    val completions by Db.get(ctx).completionDao().observeForHabit(habitId)
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(habit?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (habit == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val h = habit!!
        val today = LocalDate.now()
        val todayKey = dateKey(today)
        val completedToday = completions.any { it.dateKey == todayKey }
        val streak = currentStreak(completions, h.frequencyPerWeek, today)

        val onToggleToday: () -> Unit = {
            scope.launch {
                val dao = Db.get(ctx).completionDao()
                if (completedToday) {
                    dao.unlog(habitId, todayKey)
                } else {
                    dao.upsert(Completion(habitId, todayKey, System.currentTimeMillis()))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HabitHero(
                habit = h,
                completedToday = completedToday,
                streak = streak,
                onToggleToday = onToggleToday,
            )

            StreakSaverSection(habit = h)

            MiniStatsRow(completions = completions, today = today)
        }
    }
}

@Composable
private fun MiniStatsRow(completions: List<Completion>, today: LocalDate) {
    val thisWeek = completionsInWeek(completions, today)
    val thisMonth = completions.count {
        val d = LocalDate.parse(it.dateKey)
        d.month == today.month && d.year == today.year
    }
    val allTime = completions.size

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(label = "This week", value = thisWeek.toString(), modifier = Modifier.weight(1f))
        StatTile(label = "This month", value = thisMonth.toString(), modifier = Modifier.weight(1f))
        StatTile(label = "All time", value = allTime.toString(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
