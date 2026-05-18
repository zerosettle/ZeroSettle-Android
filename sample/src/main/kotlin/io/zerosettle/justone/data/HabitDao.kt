package io.zerosettle.justone.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<Habit>>

    @Query("SELECT * FROM habits WHERE id = :id")
    fun observe(id: String): Flow<Habit?>

    @Insert
    suspend fun insert(habit: Habit)

    @Delete
    suspend fun delete(habit: Habit)

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun count(): Int
}
