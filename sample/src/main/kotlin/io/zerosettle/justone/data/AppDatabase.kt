package io.zerosettle.justone.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Habit::class, Completion::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun completionDao(): CompletionDao
}

/** Process-wide singleton. Built lazily; call [Db.get] from anywhere with a Context. */
object Db {
    @Volatile private var instance: AppDatabase? = null

    fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            ctx.applicationContext,
            AppDatabase::class.java,
            "justone.db",
        ).build().also { instance = it }
    }
}
