package io.zerosettle.justone.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val id: String,
    val name: String,
    val frequencyPerWeek: Int,
    val sortOrder: Int,
    val createdAt: Long,
)
