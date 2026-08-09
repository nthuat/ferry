package dev.thuat.ferry

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.io.File

/**
 * The answer to "can this download finish?", produced before the first byte is requested.
 *
 * Carries the raw figures rather than a boolean alone so a caller can say *why* it refused —
 * "needs 4.1 GB, 2.3 GB free" is actionable, "download failed" is not.
 */
data class SpaceReport(
    val requiredBytes: Long,
    val freeBytes: Long,
    val headroomBytes: Long,
) {
    val sufficient: Boolean get() = freeBytes >= requiredBytes + headroomBytes

    val shortfallBytes: Long get() = maxOf(0L, requiredBytes + headroomBytes - freeBytes)
}

/** Indirection so tests can state a free-space figure instead of filling a real disk. */
fun interface FreeSpaceProbe {
    fun freeBytes(dir: Path): Long
}

/** Free bytes on the volume holding [path]. Becomes the expect/actual platform leaf in Task 7. */
internal fun availableBytes(path: Path): Long = path.toFile().usableSpace

/**
 * The nearest ancestor of [dir] that already exists on disk — [dir] itself, if it already does.
 *
 * `File.usableSpace` returns 0 — not an exception, a silent, plausible-looking zero — for a path
 * that does not exist, and a directory not existing yet is the ordinary case for a first-ever
 * download, not an edge case. Free space is a property of the volume a path sits on, not of one
 * specific directory on it, so probing the nearest real ancestor answers the same question probing
 * [dir] itself would, without needing [dir] to exist first, and without creating it just to ask.
 * Imperfect only across a mount-point boundary between the ancestor and wherever [dir] would
 * actually be created — not a concern for a single-volume app-private directory tree, which is the
 * only place this library runs (see docs/known-limitations.md). Falls back to [dir] itself if
 * nothing above it exists either, so this is never worse than probing [dir] directly would have been.
 */
private fun nearestExistingAncestor(fileSystem: FileSystem, dir: Path): Path =
    generateSequence(dir) { it.parent }.firstOrNull { fileSystem.exists(it) } ?: dir

/**
 * `usableSpace` works on Android and on the JVM. Android's StatFs reports the same number and is
 * deliberately avoided: depending on it would make every caller of this class need an instrumented
 * test to exercise a branch of arithmetic.
 *
 * Probes [nearestExistingAncestor], not `dir` directly — see that function's own doc for why. Fixed
 * here rather than in a caller: this is the one place "measure this directory's free space" is
 * actually implemented, so any caller of the default probe is covered, not only `RepoDownloader`.
 * `SpaceCheck` is public, exported on the `api` configuration, and a direct `SpaceCheck().check(...)`
 * preflight call is exactly the kind of use this library expects — the same phantom zero would have
 * hit it too had this stayed a `RepoDownloader`-only fix.
 */
val DefaultFreeSpaceProbe = FreeSpaceProbe { dir ->
    availableBytes(nearestExistingAncestor(FileSystem.SYSTEM, dir))
}

/**
 * Guarantee 3 — never start what cannot finish.
 *
 * Neither of the two reference implementations of this problem checks free space, so both begin a
 * multi-gigabyte transfer on a device that cannot hold it and fail near the end, having spent the
 * user's data allowance to get there.
 */
class SpaceCheck(
    private val probe: FreeSpaceProbe = DefaultFreeSpaceProbe,
    private val headroomBytes: Long = DEFAULT_HEADROOM_BYTES,
) {

    fun check(manifest: RepoManifest, targetDir: Path): SpaceReport = SpaceReport(
        requiredBytes = manifest.totalBytes,
        freeBytes = probe.freeBytes(targetDir),
        headroomBytes = headroomBytes,
    )

    companion object {
        /**
         * A filesystem at zero free bytes misbehaves in ways unrelated to this download, and the
         * staging directory briefly holds a file while it is being moved into place.
         */
        const val DEFAULT_HEADROOM_BYTES: Long = 256L * 1024 * 1024
    }
}
