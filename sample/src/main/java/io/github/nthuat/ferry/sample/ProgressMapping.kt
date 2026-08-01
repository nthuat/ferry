package io.github.nthuat.ferry.sample

import io.github.nthuat.ferry.InsufficientSpaceException
import io.github.nthuat.ferry.RepoProgress

/**
 * Maps one [RepoProgress] callback to the state its row shows.
 *
 * [sawTransfer] is threaded in rather than recomputed here because a lone `RepoProgress.Complete`
 * carries no memory of what happened earlier in the same attempt, and that memory is the only thing
 * that tells a cache hit apart from a redownload: `RepoDownloader.download`'s cache-hit path jumps
 * straight from `CheckingSpace` to `Complete` with no `Downloading` or `Verifying` event in between
 * (that is what makes it the cheapest outcome), while a real transfer always fires at least one
 * `Downloading` event first. The caller sets [sawTransfer] to true the moment any `Downloading`
 * event is seen, and leaves it true for the rest of that attempt.
 *
 * [lastFileCount] is the most recent `Downloading.fileCount` the same caller has seen, for the same
 * reason: `Complete` carries no file count of its own, so a real transfer's live count has to arrive
 * this way or not at all. Null on a cache hit, where no `Downloading` event ever fires to supply one.
 */
fun RepoProgress.toDownloadState(sawTransfer: Boolean, lastFileCount: Int? = null): DownloadState = when (this) {
    is RepoProgress.CheckingSpace -> DownloadState.CheckingSpace

    is RepoProgress.Downloading -> DownloadState.Downloading(
        fileIndex = fileIndex,
        fileCount = fileCount,
        path = path,
        bytesWritten = bytesWritten,
        fileBytes = fileBytes,
    )

    is RepoProgress.Verifying -> DownloadState.Verifying(path)

    is RepoProgress.Complete -> DownloadState.Downloaded(
        cacheHit = !sawTransfer,
        fileCount = if (sawTransfer) lastFileCount else null,
    )
}

/**
 * Maps a failed download's [Throwable] to the state its row shows.
 *
 * [InsufficientSpaceException] becomes `WontFit`, carrying [io.github.nthuat.ferry.SpaceReport]'s
 * own numbers rather than anything computed separately. Everything else becomes `Failed`, carrying
 * the exception's actual message — a generic "download failed" would hide exactly the information
 * this app exists to surface.
 */
fun Throwable.toDownloadState(): DownloadState = when (this) {
    is InsufficientSpaceException -> DownloadState.WontFit(
        requiredBytes = report.requiredBytes,
        freeBytes = report.freeBytes,
        shortfallBytes = report.shortfallBytes,
    )

    else -> DownloadState.Failed(message ?: this::class.java.simpleName)
}
