package com.smartplug.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DeviceEntity::class, HistoryPointEntity::class, LoadSignatureEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun historyDao(): HistoryDao
    abstract fun loadSignatureDao(): LoadSignatureDao
}
