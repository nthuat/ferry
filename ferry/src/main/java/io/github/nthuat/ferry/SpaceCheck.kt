package io.github.nthuat.ferry

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
    fun freeBytes(dir: File): Long
}

/**
 * `usableSpace` works on Android and on the JVM. Android's StatFs reports the same number and is
 * deliberately avoided: depending on it would make every caller of this class need an instrumented
 * test to exercise a branch of arithmetic.
 */
val DefaultFreeSpaceProbe = FreeSpaceProbe { dir -> dir.usableSpace }

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

    fun check(manifest: RepoManifest, targetDir: File): SpaceReport = SpaceReport(
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
