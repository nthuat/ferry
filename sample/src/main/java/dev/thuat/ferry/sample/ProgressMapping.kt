package dev.thuat.ferry.sample

import dev.thuat.ferry.InsufficientSpaceException
import dev.thuat.ferry.RepoProgress

/**
 * Maps one [RepoProgress] callback to the state its row shows.
 *
 * [sawTransfer] is threaded in rather than recomputed here because a lone `RepoProgress.Complete`
 * carries no memory of what happened earlier in the same attempt, and that memory is the only thing
 * that tells a cache hit apart from a redownload: `RepoDownloader.download`'s cache-hit path fires no
 * `CheckingSpace`, `Downloading` or `Verifying` event at all — it jumps straight to `Complete` (that
 * is what makes it the cheapest outcome, and more truthful: a cache hit never checks space, so it
 * never claims to), while a real transfer always fires at least one `Downloading` event first. The
 * caller sets [sawTransfer] to true the moment any `Downloading` event is seen, and leaves it true
 * for the rest of that attempt.
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

    // No dedicated row state for "already had it" — DownloadState.Verifying is the closest honest
    // fit: a brief, non-transferring check on one file, the same shape RepoProgress.Skipped itself
    // is. Add a dedicated DownloadState only if a row ever needs to say "skipped" rather than
    // "checked".
    is RepoProgress.Skipped -> DownloadState.Verifying(path)

    is RepoProgress.Complete -> DownloadState.Downloaded(
        cacheHit = !sawTransfer,
        fileCount = if (sawTransfer) lastFileCount else null,
    )
}

/**
 * What this row should show once [dev.thuat.ferry.RepoDownloader.stagedBytes] is known for it —
 * applied once per row, when `SampleViewModel` first learns whatever staging survived a process
 * death (see its own `init` doc).
 *
 * Only overrides [DownloadState.Available]: every other state carries more current information than
 * a byte count staged before this attempt was even known about — a row already downloading,
 * downloaded, refused, or failed must not be clobbered by it, including the case `SampleViewModel`'s
 * own doc calls out — a user tapping Download before this check resolves.
 */
fun DownloadState.withStagedBytes(stagedBytes: Long): DownloadState =
    if (this is DownloadState.Available && stagedBytes > 0L) {
        DownloadState.Interrupted(stagedBytes)
    } else {
        this
    }

/**
 * Maps a failed download's [Throwable] to the state its row shows.
 *
 * [InsufficientSpaceException] becomes `WontFit`, carrying [dev.thuat.ferry.SpaceReport]'s
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
