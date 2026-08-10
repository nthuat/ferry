package dev.thuat.ferry

import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * Guarantee 2 — never a corrupt model.
 *
 * Streams through Okio's HashingSink because model files are gigabytes and reading one into memory
 * to hash it would exhaust the heap on exactly the devices that most need this to work.
 *
 * Internal primitive: I/O failures are caught by [RepoDownloader.download], the public boundary,
 * which converts them to [Result.failure].
 */
internal object Sha256 {

    fun of(fileSystem: FileSystem, file: Path): String =
        HashingSink.sha256(blackholeSink()).use { hashing ->
            fileSystem.source(file).use { source ->
                hashing.buffer().use { sink -> sink.writeAll(source) }
            }
            hashing.hash.hex()
        }

    /**
     * Case-insensitive because hubs are inconsistent about hex case, and the bug that causes is
     * indistinguishable from real corruption: a perfectly good file, reported broken.
     */
    fun matches(fileSystem: FileSystem, file: Path, expectedHex: String): Boolean =
        of(fileSystem, file).equals(expectedHex.trim(), ignoreCase = true)
}
