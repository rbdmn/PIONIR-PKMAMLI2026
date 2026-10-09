package com.smartplug.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LoadSignatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signature: LoadSignatureEntity)

    @Query("SELECT * FROM load_signatures WHERE deviceId = :deviceId")
    suspend fun forDevice(deviceId: String): List<LoadSignatureEntity>

    @Query("DELETE FROM load_signatures WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: String)
}
