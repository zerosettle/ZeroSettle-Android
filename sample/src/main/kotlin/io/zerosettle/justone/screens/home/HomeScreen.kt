package io.zerosettle.justone.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.ui.ZeroSettleCheckoutHost
import com.zerosettle.ui.ZeroSettleOfferTip
import com.zerosettle.ui.ZeroSettlePendingActionBanner
import io.zerosettle.justone.data.Completion
import io.zerosettle.justone.data.Db
import io.zerosettle.justone.data.UserPrefs
import io.zerosettle.justone.data.dateKey
import io.zerosettle.justone.sdk.OfferHolder
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The post-identify home screen — habit list, aggregated heatmap, and the three
 * ZeroSettle SDK surfaces (offer tip, pending-action banner, checkout host).
 *
 * Owns all Room/SDK observation; the child composables ([HomeHeader], [HabitRow],
 * [AggregatedHeatmap]) stay pure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenHabit: (String) -> Unit,
    onAddHabit: () -> Unit,
    onShowUpsell: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { UserPrefs(ctx) }
    val identity by prefs.identity.collectAsState(initial = null)
    val habits by Db.get(ctx).habitDao().observeAll().collectAsState(initial = emptyList())
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val pendingActions by ZeroSettle.pendingActions.collectAsState()
    val isPremium = entitlements.any { it.isActive && it.productType != "consumable" }
    val scope = rememberCoroutineScope()

    // Single wide-range flow feeds the aggregated heatmap across all habits.
    val allCompletions by Db.get(ctx).completionDao()
        .observeInRange("0000-01-01", "9999-12-31")
        .collectAsState(initial = emptyList())

    val displayName = identity?.displayName ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JustOne") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val count = Db.get(ctx).habitDao().count()
                        if (count >= 3 && !isPremium) onShowUpsell()
                        else onAddHabit()
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add habit")
            }
        },
    ) { innerPadding ->
        // CheckoutHost lives at the screen root (a no-op until pendingCheckoutUrl is
        // set), NOT inside the LazyColumn — it must stay mounted regardless of scroll.
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item {
                    HomeHeader(
                        displayName = displayName,
                        habitCount = habits.size,
                        isPremium = isPremium,
                    )
                }

                items(pendingActions, key = { it.transactionId }) { action ->
                    ZeroSettlePendingActionBanner(
                        action = action,
                        onDeepLink = { /* no-op: sample has no external deep targets */ },
                        onDismiss = { scope.launch { ZeroSettle.dismissPendingAction(it) } },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }

                item {
                    ZeroSettleOfferTip(
                        offerManager = OfferHolder.get(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }

                item {
                    AggregatedHeatmap(completions = allCompletions)
                }

                items(habits, key = { it.id }) { habit ->
                    val completions by Db.get(ctx).completionDao()
                        .observeForHabit(habit.id)
                        .collectAsState(initial = emptyList())
                    val completedToday = completions.any { it.dateKey == dateKey(LocalDate.now()) }

                    HabitRow(
                        habit = habit,
                        completions = completions,
                        completedToday = completedToday,
                        onToggleToday = {
                            val today = dateKey(LocalDate.now())
                            scope.launch {
                                val dao = Db.get(ctx).completionDao()
                                if (completedToday) {
                                    dao.unlog(habit.id, today)
                                } else {
                                    dao.upsert(Completion(habit.id, today, System.currentTimeMillis()))
                                }
                            }
                        },
                        onOpen = { onOpenHabit(habit.id) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            ZeroSettleCheckoutHost(offerManager = OfferHolder.get())
        }
    }
}
