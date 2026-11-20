package com.oneproxyclick.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HttpTransactionEntity::class], version = 1, exportSchema = false)
abstract class OneProxyDatabase : RoomDatabase() {
    abstract fun transactions(): HttpTransactionDao

    companion object {
        @Volatile private var instance: OneProxyDatabase? = null

        fun get(context: Context): OneProxyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, OneProxyDatabase::class.java, "oneproxyclick.db")
                    .fallbackToDestructiveMigration()
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
    }
}
