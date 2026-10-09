package com.smartplug.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartplug.app.data.local.db.AppDatabase
import com.smartplug.app.data.local.db.DeviceDao
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "smartplug.db")
            // Pre-release app under active testing, no migration path defined yet: dropping
            // locally-registered devices on a schema bump is preferable to crashing on launch.
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideLoadSignatureDao(db: AppDatabase): LoadSignatureDao = db.loadSignatureDao()

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `load_signatures` (`deviceId` TEXT NOT NULL, `name` TEXT NOT NULL, `currentA` REAL NOT NULL, `powerFactor` REAL NOT NULL, PRIMARY KEY(`deviceId`, `name`))")
        }
    }
}
