package io.zerosettle.justone.data

import android.content.Context
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Inserts two starter habits on first launch so the app isn't empty on first open.
 * Idempotent: gated on the DataStore `seeded` flag.
 */
suspend fun seedHabitsIfNeeded(ctx: Context, prefs: UserPrefs) {
    if (prefs.seeded.first()) return
    val dao = Db.get(ctx).habitDao()
    val now = System.currentTimeMillis()
    dao.insert(Habit(UUID.randomUUID().toString(), "Drink water", 7, 0, now))
    dao.insert(Habit(UUID.randomUUID().toString(), "Read 10 pages", 5, 1, now))
    prefs.setSeeded(true)
}
