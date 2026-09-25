package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the deletion helper that backs per-session deletion: only referenced
 * files die, already-missing files are skipped silently, and the count reports
 * what was actually removed.
 */
class EvidenceFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(clip: String?, photo: String?) = Event(
        id = 1, type = "impact", timestamp = 0, source = "motion", confidence = 0.5f,
        evidenceClipPath = clip, evidencePhotoPath = photo
    )

    @Test
    fun `deletes both files of an event and reports the count`() {
        val clip = tmp.newFile("evidence_1.amr").apply { writeText("x") }
        val photo = tmp.newFile("photo_1.jpg").apply { writeText("x") }

        val removed = EvidenceFiles.deleteEventFiles(listOf(event(clip.path, photo.path)))

        assertEquals(2, removed)
        assertFalse(clip.exists())
        assertFalse(photo.exists())
    }

    @Test
    fun `missing files are skipped without error`() {
        val gone = File(tmp.root, "never_existed.amr")
        val removed = EvidenceFiles.deleteEventFiles(listOf(event(gone.path, null)))
        assertEquals(0, removed)
    }

    @Test
    fun `events with no evidence touch nothing`() {
        val removed = EvidenceFiles.deleteEventFiles(listOf(event(null, null)))
        assertEquals(0, removed)
        assertEquals(0, tmp.root.listFiles()?.size ?: 0)
    }

    @Test
    fun `files of other events survive`() {
        val keep = tmp.newFile("evidence_99.amr").apply { writeText("keep") }
        val kill = tmp.newFile("evidence_1.amr").apply { writeText("kill") }

        EvidenceFiles.deleteEventFiles(listOf(event(kill.path, null)))

        assertTrue(keep.exists())
        assertFalse(kill.exists())
    }
}
