package dev.thuat.ferry.sample

/**
 * What one row shows for one model.
 *
 * Every variant renders inside the same fixed-height row (see `ModelRowCard`) — a list that resizes
 * as rows settle reflows under the user's thumb, which is exactly the sort of thing Ferry itself is
 * about not doing.
 */
sealed interface DownloadState {

    /** Nothing has been asked of this repo yet. */
    data object Available : DownloadState

    /**
     * Staging survived from an earlier attempt on this repo — a failure, a cancellation, or the
     * process dying — holding [stagedBytes] this app found reusable via
     * [dev.thuat.ferry.RepoDownloader.stagedBytes]. Computed once, when `SampleViewModel` is
     * constructed (see its own doc), not continuously: nothing outside this app touches its staging,
     * so there is nothing to watch for after that first check.
     *
     * [stagedBytes] is not re-verified here any more than `RepoDownloader.stagedBytes` re-verifies it
     * itself (see that method's own KDoc) — it is a hint, not a promise. The caption this state
     * renders says only what is staged, never what tapping Resume is guaranteed to transfer: a
     * `.part` with no validator restarts from zero regardless of what this number says, and this row
     * must not claim otherwise (see `captionFor`'s handling of this state).
     */
    data class Interrupted(val stagedBytes: Long) : DownloadState

    /**
     * Free space is being checked — never network time, since the manifest has already been fetched
     * by the time this ever fires, but not only a fast local stat (`File.usableSpace`) either: a
     * resumed download credits whatever of a file is already staged and correct, and that credit
     * check re-hashes every such file to decide, so a mostly-resumed multi-gigabyte model can spend
     * real, visible time here re-reading disk before the first network request. Skipped entirely on
     * a cache hit, which needs no space check at all — a `Downloaded` row can arrive with no
     * `CheckingSpace` in between (see `ProgressMapping.kt`'s own doc on `toDownloadState`).
     */
    data object CheckingSpace : DownloadState

    /**
     * [SpaceCheck][dev.thuat.ferry.SpaceCheck] refused before a single byte was requested.
     * Every field here is copied from the [dev.thuat.ferry.SpaceReport] that
     * [dev.thuat.ferry.InsufficientSpaceException] carried — never recomputed — so the row
     * and the refusal agree by construction.
     */
    data class WontFit(
        val requiredBytes: Long,
        val freeBytes: Long,
        val shortfallBytes: Long,
    ) : DownloadState

    /** [fileIndex] and [fileCount] say how far through the repo; [bytesWritten]/[fileBytes], the file. */
    data class Downloading(
        val fileIndex: Int,
        val fileCount: Int,
        val path: String,
        val bytesWritten: Long,
        val fileBytes: Long,
    ) : DownloadState

    data class Verifying(val path: String) : DownloadState

    /**
     * [cacheHit] is true only right after a check that transferred zero bytes — the cheapest outcome
     * the library has, and invisible unless said out loud, which is what asking for this bit is for.
     *
     * [fileCount] is the live count observed from the attempt's own `RepoProgress.Downloading` events
     * — never the static catalog number — so it is null on a cache hit: that path fires no
     * `Downloading` event at all (see [dev.thuat.ferry.sample.toDownloadState]), so there is no
     * live count available for it anywhere in the public API, and showing the catalog's instead would
     * be exactly the "recomputed separately" this app's numbers are otherwise never allowed to be.
     */
    data class Downloaded(val cacheHit: Boolean = false, val fileCount: Int? = null) : DownloadState

    /** [message] is the failure's own message — never a generic "download failed". */
    data class Failed(val message: String) : DownloadState
}
