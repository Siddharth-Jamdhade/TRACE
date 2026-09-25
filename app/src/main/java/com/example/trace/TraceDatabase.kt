package com.example.trace

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Current schema version. Bump this AND add the matching migration to
 * [TraceDatabase.MIGRATIONS] whenever an entity changes.
 */
internal const val TRACE_DB_VERSION = 3

/**
 * Oldest schema version this build can open without destroying evidence.
 *
 * Version 1 is deliberately unsupported: the previous build configured
 * `fallbackToDestructiveMigration()`, so any v1 database was already wiped and
 * recreated at a newer version. No v1 database can exist in the wild.
 */
internal const val TRACE_DB_OLDEST_SUPPORTED = 2

// Schema history:
//   v2 — id, type, timestamp, source, confidence, status, cameraConfidence,
//        audioConfidence, motionConfidence, evidenceClipPath, hash, previousHash
//   v3 — v2 + sensorBreakdown (extended sensor array, v2.1)
@Database(entities = [Event::class], version = TRACE_DB_VERSION)
abstract class TraceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {

        /** v2 → v3: the extended sensor array added one nullable column. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN sensorBreakdown TEXT")
            }
        }

        /**
         * Every migration this build ships. The range
         * [TRACE_DB_OLDEST_SUPPORTED] .. [TRACE_DB_VERSION] must be fully
         * covered — enforced by DatabaseMigrationTest.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)

        @Volatile private var INSTANCE: TraceDatabase? = null

        fun getInstance(context: Context): TraceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TraceDatabase::class.java,
                    "trace_database"
                )
                    // There is intentionally NO fallbackToDestructiveMigration()
                    // here — a missing migration must fail loudly rather than
                    // silently delete evidence.
                    .addMigrations(*MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}