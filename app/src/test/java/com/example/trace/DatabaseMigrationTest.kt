package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 0 regression tests for schema migrations.
 *
 * `fallbackToDestructiveMigration()` is gone, which means a schema bump without
 * a matching migration now crashes the app on open instead of wiping evidence.
 * These tests make that omission fail at build time instead of on a demo phone.
 */
class DatabaseMigrationTest {

    @Test
    fun everyVersionGapHasAMigration() {
        val covered = TraceDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()

        for (version in TRACE_DB_OLDEST_SUPPORTED until TRACE_DB_VERSION) {
            assertTrue(
                "Missing migration $version -> ${version + 1}. Without it, Room " +
                    "throws on open and evidence cannot be read.",
                covered.contains(version to (version + 1))
            )
        }
    }

    @Test
    fun migrationsReachTheCurrentVersion() {
        assertTrue("no migrations are registered", TraceDatabase.MIGRATIONS.isNotEmpty())
        assertEquals(
            "the newest migration must land exactly on TRACE_DB_VERSION",
            TRACE_DB_VERSION,
            TraceDatabase.MIGRATIONS.maxOf { it.endVersion }
        )
    }
}
