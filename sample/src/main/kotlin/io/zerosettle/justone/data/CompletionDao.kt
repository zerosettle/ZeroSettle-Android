package io.zerosettle.justone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completions WHERE habitId = :habitId ORDER BY dateKey DESC")
    fun observeForHabit(habitId: String): Flow<List<Completion>>

    @Query("SELECT * FROM completions WHERE dateKey BETWEEN :start AND :end")
    fun observeInRange(start: String, end: String): Flow<List<Completion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Completion)

    @Query("DELETE FROM completions WHERE habitId = :habitId AND dateKey = :dateKey")
    suspend fun unlog(habitId: String, dateKey: String)
}
