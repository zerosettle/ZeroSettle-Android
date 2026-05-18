package io.zerosettle.justone.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "completions",
    primaryKeys = ["habitId", "dateKey"],
    foreignKeys = [ForeignKey(
        entity = Habit::class,
        parentColumns = ["id"],
        childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("habitId"), Index("dateKey")],
)
data class Completion(
    val habitId: String,
    val dateKey: String,
    val loggedAt: Long,
)
