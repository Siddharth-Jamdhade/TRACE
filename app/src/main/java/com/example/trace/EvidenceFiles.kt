package com.example.trace

import android.content.Context
import java.io.File

/**
 * TRACE — evidence file storage.
 *
 * Clips and snapshots are written to **internal** app-private storage
 * (`files/TRACE/`). This is a deliberate privacy property, not a convenience:
 *
 *   - internal storage has no path other apps can read without root,
 *   - `allowBackup="false"` keeps it out of Google cloud backup, and
 *   - the files are covered by file-based encryption while the device is
 *     locked (the old `Android/data` location was not treated the same way).
 *
 * Earlier builds stored evidence in `getExternalFilesDir(null)` — readable by
 * the user over MTP and by any app holding media permissions. On first launch
 * this build migrates those files into internal storage, so an upgrading
 * install does not leave evidence behind in the readable location; the old
 * directory is then emptied of TRACE files.
 *
 * Deleting a session deletes its files too — leaving orphaned audio for
 * deleted evidence would keep "deleted" material recoverable, the opposite of
 * what an operator asking for deletion wants. Best-effort by design: a file
 * that is already gone is not an error, and a file that refuses to die is
 * reported by count rather than aborting the DB deletion — the rows are the
 * evidence of record.
 */
object EvidenceFiles {

    /** Directory name inside internal storage. */
    const val DIR_NAME = "TRACE"

    /** Internal app-private evidence directory. */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * One-time migration from the old external location. Moves every
     * `evidence_*.amr` / `photo_*.jpg` / `*.json` file into internal storage,
     * then removes the empty directory. Missing/duplicate files are skipped;
     * a file that fails to move is copied instead.
     *
     * Safe to run on every launch: it is a no-op once the external dir is gone.
     */
    fun migrateExternalEvidenceToInternal(context: Context) {
        val legacy = context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) } ?: return
        if (!legacy.isDirectory) return

        val target = dir(context)
        legacy.listFiles { f -> f.isFile }.orEmpty().forEach { file ->
            val dest = File(target, file.name)
            if (!dest.exists()) {
                if (!file.renameTo(dest)) {
                    runCatching { file.copyTo(dest, overwrite = true) }
                }
            }
            // Remove from the readable location either way — the goal is that
            // nothing of TRACE's stays where other surfaces could read it.
            runCatching { file.delete() }
        }
        runCatching { legacy.delete() }   // succeeds only when empty
    }

    /**
     * Deletes the clip and photo files referenced by [events].
     *
     * Takes no Context on purpose: stored paths are absolute, so deletion is
     * pure file work and stays unit-testable on the JVM.
     *
     * @return how many files were actually removed (missing files are skipped).
     */
    fun deleteEventFiles(events: List<Event>): Int {
        var removed = 0
        events.forEach { e ->
            listOfNotNull(e.evidenceClipPath, e.evidencePhotoPath).forEach { path ->
                if (File(path).delete()) removed++
            }
        }
        return removed
    }
}
