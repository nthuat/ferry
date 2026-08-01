package io.github.nthuat.ferry.sample

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

    /** The manifest is being fetched and free space checked — brief, but real network time. */
    data object CheckingSpace : DownloadState

    /**
     * [SpaceCheck][io.github.nthuat.ferry.SpaceCheck] refused before a single byte was requested.
     * Every field here is copied from the [io.github.nthuat.ferry.SpaceReport] that
     * [io.github.nthuat.ferry.InsufficientSpaceException] carried — never recomputed — so the row
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
     * `Downloading` event at all (see [io.github.nthuat.ferry.sample.toDownloadState]), so there is no
     * live count available for it anywhere in the public API, and showing the catalog's instead would
     * be exactly the "recomputed separately" this app's numbers are otherwise never allowed to be.
     */
    data class Downloaded(val cacheHit: Boolean = false, val fileCount: Int? = null) : DownloadState

    /** [message] is the failure's own message — never a generic "download failed". */
    data class Failed(val message: String) : DownloadState
}
