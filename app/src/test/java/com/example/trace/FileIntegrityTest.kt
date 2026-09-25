package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the SHA-256 file binding: known-vector correctness, stream vs file
 * equality, and the rules that a missing file or a blank expected hash can
 * never verify.
 */
class FileIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `known vector - empty input`() {
        val hex = FileIntegrity.sha256("".byteInputStream())
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex
        )
    }

    @Test
    fun `known vector - abc`() {
        val hex = FileIntegrity.sha256("abc".byteInputStream())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex
        )
    }

    @Test
    fun `file and stream paths agree`() {
        val f = tmp.newFile("evidence_1.amr").apply { writeText("audio bytes here") }
        assertEquals(FileIntegrity.sha256(f), FileIntegrity.sha256(f.inputStream()))
    }

    @Test
    fun `a modified file no longer matches`() {
        val f = tmp.newFile("photo_1.jpg").apply { writeText("original frame") }
        val expected = FileIntegrity.sha256(f)

        assertTrue(FileIntegrity.matches(f, expected))

        f.writeText("tampered frame")
        assertFalse(FileIntegrity.matches(f, expected))
    }

    @Test
    fun `missing file and blank expected hash never verify`() {
        val gone = File(tmp.root, "never_was.amr")
        assertFalse(FileIntegrity.matches(gone, "a".repeat(64)))
        val f = tmp.newFile("x.amr")
        assertFalse(FileIntegrity.matches(f, null))
        assertFalse(FileIntegrity.matches(f, ""))
    }
}
