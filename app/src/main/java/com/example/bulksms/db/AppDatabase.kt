package com.example.bulksms.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ContactEntity::class, SendLogEntity::class, ScheduleEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun sendLogDao(): SendLogDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bulk_sms.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
