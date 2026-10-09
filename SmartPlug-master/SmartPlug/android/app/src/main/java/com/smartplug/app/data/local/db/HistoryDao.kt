package com.smartplug.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Query(
        "SELECT * FROM history_points WHERE deviceId = :deviceId AND resolution = :resolution " +
            "AND timestampUtcMs BETWEEN :fromMs AND :toMs ORDER BY timestampUtcMs ASC"
    )
    suspend fun getRange(
        deviceId: String,
        resolution: String,
        fromMs: Long,
        toMs: Long,
    ): List<HistoryPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(points: List<HistoryPointEntity>)

    @Query("DELETE FROM history_points WHERE resolution = :resolution AND timestampUtcMs < :beforeMs")
    suspend fun deleteOlderThan(resolution: String, beforeMs: Long)

    @Query("DELETE FROM history_points WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: String)
}
