package com.example.trace

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * TRACE — evidence file integrity.
 *
 * The hash chain proves the *records* have not been altered; these hashes bind
 * the *files* (audio clips, snapshots) to those records. Without them, a clip
 * could be silently replaced after the fact while the chain still verified.
 *
 * Computed once when the file is written and stored on the event row; the
 * detail screen recomputes on view and reports a mismatch loudly. Like the
 * chain, a mismatch is a red flag, not an auto-delete — the discrepancy itself
 * is information for the investigator.
 */
object FileIntegrity {

    /** Lowercase hex SHA-256 of a stream's bytes; the stream is closed by this call. */
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = sha256(file.inputStream())

    /** True when [file] exists and hashes to [expected]. A missing file fails. */
    fun matches(file: File, expected: String?): Boolean =
        !expected.isNullOrBlank() && file.exists() && sha256(file) == expected
}
