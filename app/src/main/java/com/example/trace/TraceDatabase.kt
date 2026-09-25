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
internal const val TRACE_DB_VERSION = 6

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
//   v4 — v3 + sessions table, and events.sessionId (one chain per session)
//   v5 — v4 + events.evidencePhotoPath (camera snapshot per event)
//   v6 — v5 + events.clipHash / photoHash (evidence file integrity)
@Database(entities = [Event::class, Session::class], version = TRACE_DB_VERSION)
abstract class TraceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sessionDao(): SessionDao

    companion object {

        /** v2 → v3: the extended sensor array added one nullable column. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN sensorBreakdown TEXT")
            }
        }

        /**
         * v3 → v4: sessions become first-class, each with its own hash chain.
         *
         * All existing events are placed in a synthetic legacy session whose
         * chain is anchored on GENESIS, so their recorded hashes and links stay
         * valid — nothing is recomputed or rewritten.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`natureOfWork` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER, " +
                        "`state` TEXT NOT NULL, " +
                        "`sensorSet` TEXT NOT NULL, " +
                        "`sessionHash` TEXT NOT NULL, " +
                        "`eventCount` INTEGER NOT NULL, " +
                        "`confirmedCount` INTEGER NOT NULL, " +
                        "`legacy` INTEGER NOT NULL)"
                )

                // Existing rows default into the legacy session.
                db.execSQL("ALTER TABLE events ADD COLUMN sessionId INTEGER NOT NULL DEFAULT 1")

                // The legacy session only exists if there is legacy evidence to
                // hold; on a fresh install the first real session takes id 1.
                val hasLegacyEvents = db.query("SELECT COUNT(*) FROM events").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }
                if (hasLegacyEvents) {
                    db.execSQL(
                        "INSERT INTO `sessions` (`id`, `natureOfWork`, `startedAt`, `endedAt`, " +
                            "`state`, `sensorSet`, `sessionHash`, `eventCount`, `confirmedCount`, `legacy`) " +
                            "SELECT 1, 'Pre-session timeline (legacy)', MIN(`timestamp`), MAX(`timestamp`), " +
                            "'COMPLETED', '', ?, COUNT(*), " +
                            "SUM(CASE WHEN `status` = 'CONFIRMED' THEN 1 ELSE 0 END), 1 FROM `events`",
                        arrayOf<Any?>(HashChain.GENESIS_HASH)
                    )
                }
            }
        }

        /** v4 → v5: camera snapshot evidence. One nullable column, no rewrite. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN evidencePhotoPath TEXT")
            }
        }

        /** v5 → v6: per-file SHA-256 integrity hashes. Two nullable columns. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN clipHash TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN photoHash TEXT")
            }
        }

        /**
         * Every migration this build ships. The range
         * [TRACE_DB_OLDEST_SUPPORTED] .. [TRACE_DB_VERSION] must be fully
         * covered — enforced by DatabaseMigrationTest.
         */
        val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

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