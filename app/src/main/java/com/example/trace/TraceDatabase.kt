package com.example.trace

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Schema version 2 — added per-sensor confidence columns, evidenceClipPath, hash chain fields.
// fallbackToDestructiveMigration is used for hackathon speed; add proper migrations before production.
@Database(entities = [Event::class], version = 2)
abstract class TraceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: TraceDatabase? = null

        fun getInstance(context: Context): TraceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TraceDatabase::class.java,
                    "trace_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}