package com.smartplug.app.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY displayName ASC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Update
    suspend fun update(device: DeviceEntity)

    @Query("UPDATE devices SET lanIp = :lanIp WHERE deviceId = :deviceId")
    suspend fun updateLanIp(deviceId: String, lanIp: String)

    @Query("UPDATE devices SET displayName = :displayName WHERE deviceId = :deviceId")
    suspend fun renameDevice(deviceId: String, displayName: String)

    @Delete
    suspend fun delete(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}
