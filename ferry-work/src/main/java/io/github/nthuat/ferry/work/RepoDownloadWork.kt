package io.github.nthuat.ferry.work

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * Enqueues a [RepoDownloadWorker] for [repoId] into [into], deduplicated so a second call for the
 * same [repoId] while one is already running or queued does nothing.
 *
 * ## Uniqueness — the policy, and why
 *
 * `docs/known-limitations.md` records that two concurrent `RepoDownloader.download()` calls for
 * the same repo id share a staging directory and can corrupt each other, and `:ferry` documents it
 * as the caller's problem to serialise — a plain library call has no enqueue step to serialise it
 * at. WorkManager does, and this closes it there: [WorkManager.enqueueUniqueWork] with a name
 * derived from [repoId] guarantees at most one [RepoDownloadWorker] for that id is ever running or
 * queued at once.
 *
 * The policy is [ExistingWorkPolicy.KEEP]: if a download for [repoId] is already running or
 * queued, this call is dropped and the existing one keeps going untouched. The alternative,
 * [ExistingWorkPolicy.REPLACE], was rejected — replacing cancels the in-flight worker, whose
 * `finally` block (in `RepoDownloader.download`) deletes its staging directory, forfeiting
 * whatever had already downloaded. A second tap of the same download button, or a retry sweep that
 * re-enqueues everything not yet complete, would then throw away real progress on every repeat
 * instead of being the harmless no-op [ExistingWorkPolicy.KEEP] makes it.
 *
 * **This closes the case only *within* WorkManager.** A host that also calls
 * `RepoDownloader.download` directly for the same repo id and directory — outside this enqueue
 * path entirely — is back to `:ferry`'s own documented caller's-problem: this function cannot see,
 * let alone serialise against, a call it is not part of.
 *
 * A [Constraints.Builder.setRequiredNetworkType] of [NetworkType.CONNECTED] is attached because
 * this worker exists to move bytes over the network — starting it with no network on hand would
 * only spend a doomed attempt before it retries anyway.
 */
fun WorkManager.enqueueRepoDownload(repoId: String, into: File, notificationId: Int): Operation {
    val request = OneTimeWorkRequestBuilder<RepoDownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInputData(
            workDataOf(
                RepoDownloadWorker.KEY_REPO_ID to repoId,
                RepoDownloadWorker.KEY_INTO_PATH to into.absolutePath,
                RepoDownloadWorker.KEY_NOTIFICATION_ID to notificationId,
            ),
        )
        .build()
    return enqueueUniqueWork(repoDownloadWorkName(repoId), ExistingWorkPolicy.KEEP, request)
}

/**
 * The unique work name [enqueueRepoDownload] enqueues [repoId] under.
 *
 * Exposed so a host can look the same download back up — `WorkManager.getWorkInfosForUniqueWork`,
 * `WorkManager.cancelUniqueWork` — without re-deriving or hardcoding this function's own format.
 */
fun repoDownloadWorkName(repoId: String): String = "io.github.nthuat.ferry.work.download:$repoId"
