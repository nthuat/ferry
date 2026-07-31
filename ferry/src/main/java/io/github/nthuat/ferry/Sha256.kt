package io.github.nthuat.ferry

import java.io.File
import java.security.MessageDigest

/**
 * Guarantee 2 — never a corrupt model.
 *
 * Streams in fixed chunks because model files are gigabytes and reading one into memory to hash it
 * would exhaust the heap on exactly the devices that most need this to work.
 *
 * Internal primitive: I/O failures are caught by [RepoDownloader.download], the public boundary,
 * which converts them to [Result.failure].
 */
internal object Sha256 {

    private const val BUFFER_BYTES = 64 * 1024

    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Case-insensitive because hubs are inconsistent about hex case, and the bug that causes is
     * indistinguishable from real corruption: a perfectly good file, reported broken.
     */
    fun matches(file: File, expectedHex: String): Boolean =
        of(file).equals(expectedHex.trim(), ignoreCase = true)
}
