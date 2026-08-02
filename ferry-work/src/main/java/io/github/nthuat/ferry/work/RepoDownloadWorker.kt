package io.github.nthuat.ferry.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.nthuat.ferry.InsufficientSpaceException
import io.github.nthuat.ferry.RepoDownloader
import io.github.nthuat.ferry.RepoProgress
import io.github.nthuat.ferry.VerificationException
import kotlinx.coroutines.CancellationException
import java.io.File
import androidx.work.ListenableWorker.Result as WorkResult

/**
 * Backgrounds one [RepoDownloader.download] call as a [CoroutineWorker], with a foreground
 * notification the host controls.
 *
 * Not built by WorkManager's default, reflection-based factory: [repoDownloader] and
 * [notifications] cannot travel through [Data] (primitives only) or be conjured from a bare
 * `Context`. Construct this only through [RepoDownloadWorkerFactory] — see its own KDoc for how
 * to register it — or, in a test, through `TestListenableWorkerBuilder.setWorkerFactory`.
 *
 * ## Input and output — what survives the [Data] boundary
 *
 * [KEY_REPO_ID], [KEY_INTO_PATH] and a non-zero [KEY_NOTIFICATION_ID] are the only required input;
 * [enqueueRepoDownload] builds this [Data] correctly so a caller never has to spell these keys out.
 * All three missing or blank fails immediately with [REASON_INVALID_INPUT] — a caller bug that
 * would fail identically on every future attempt, not a transient condition, so retrying it would
 * be exactly the waste the Retry section below exists to avoid.
 *
 * On success, [KEY_OUTPUT_PATH] carries the committed directory's absolute path — the value
 * [RepoDownloader.download] itself returned, not [KEY_INTO_PATH] and [KEY_REPO_ID] rejoined by
 * this worker, so a caller never has to trust it can reproduce Ferry's own path-joining rules.
 *
 * A [WorkResult.failure] carries as much of the triggering exception as [Data]'s primitives allow.
 * [RepoDownloader.download] can fail with [InsufficientSpaceException] (holds a
 * [io.github.nthuat.ferry.SpaceReport]), [VerificationException] (names a path), or a bare
 * [java.io.IOException] (a dropped connection, an HTTP error, a refused overwrite — see Retry).
 * Today only [InsufficientSpaceException] ever reaches [WorkResult.failure]: [KEY_FAILURE_REASON]
 * is [REASON_INSUFFICIENT_SPACE], [KEY_FAILURE_MESSAGE] is its message, and
 * [KEY_REQUIRED_BYTES] / [KEY_FREE_BYTES] / [KEY_SHORTFALL_BYTES] are its report's own numbers.
 * What is lost even here: the exception's Java type (the [String] reason stands in for it), any
 * cause chain, and a stack trace — none of those are primitives. [VerificationException.path] is
 * lost entirely, not merely narrowed — see Retry for why, and what a host can do instead.
 *
 * ## Retry
 *
 * [WorkResult.retry] versus [WorkResult.failure] is the central question of a download worker, and
 * the two are not symmetric: [InsufficientSpaceException] fails identically on every attempt — the
 * device is exactly as full one retry later — so retrying it spends a backoff slot and battery on
 * a result already known. It is therefore the one failure this worker treats as terminal.
 * Everything else retries, on the premise that most of it is transient: a dropped connection, an
 * HTTP 5xx, a [VerificationException] from a file corrupted in transit rather than permanently bad
 * at the source.
 *
 * That is a two-way split, not a full taxonomy, because [RepoDownloader] does not expose one to
 * split further. Besides the two named exceptions, every other failure — a dropped connection, a
 * repo id colliding with Ferry's own staging namespace, a target directory Ferry did not write and
 * refuses to replace (`docs/known-limitations.md`'s "a refused directory needs manual removal") —
 * surfaces as a plain [java.io.IOException], indistinguishable by type from the outside. A repo id
 * that can never resolve retries exactly like a socket that dropped once; telling them apart would
 * need [RepoDownloader] to expose richer error types than it does today. That is a real gap, named
 * here rather than worked around, not a change to `:ferry` — see this module's README for why a
 * change there was out of scope for this module in the first place.
 *
 * A consequence worth stating plainly: a [VerificationException] that will in fact never pass —
 * a hub permanently serving the wrong bytes for a path, not a transit corruption — retries
 * forever rather than failing after some bound, because nothing here can tell the two apart and
 * this worker does not guess. The cost is bounded by WorkManager's own backoff, not by this class:
 * a host that wants a hard cutoff can read `runAttemptCount` off the [androidx.work.WorkInfo] this
 * work reports and cancel it — the platform already has that lever, and duplicating it here would
 * only be a second, worse copy of it.
 *
 * ## Progress
 *
 * [RepoProgress.Downloading] fires once per read buffer — about 700,000 times for a 5.6 GB file.
 * `setProgressAsync` writes to WorkManager's own database and `setForegroundAsync` calls the
 * system `NotificationManager`; both are throttled through [RepoDownloadThrottle] before either is
 * reached — see that class's own KDoc for why it is a port of `:sample`'s `DownloadingThrottle`
 * rather than a shared dependency. The *Async, non-suspend variants are used deliberately:
 * [RepoDownloader.download]'s `onProgress` callback is a plain synchronous lambda, not `suspend`,
 * so it cannot call [setProgress]/[setForeground] directly — [ListenableWorker][androidx.work.ListenableWorker]
 * already has non-suspend counterparts built for exactly this shape of caller.
 *
 * ## Uniqueness
 *
 * Not enforced by this class — WorkManager's uniqueness is a property of how work is *enqueued*,
 * not of the worker that runs it. Use [enqueueRepoDownload] rather than enqueueing this worker
 * directly; see its own KDoc for the policy chosen and why.
 *
 * ## Foreground
 *
 * [android.app.Service.startForeground] needs a notification, and — API 29+ — a
 * [ServiceInfo] foreground-service type, declared as [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]
 * here; omitted below API 29, where the type-less overload is all that exists. This module's own
 * manifest separately declares that same type on WorkManager's `SystemForegroundService` — required
 * from API 34, where the manifest and the runtime call must agree, and not something WorkManager's
 * own manifest does for you (verified directly against work-runtime 2.11.2's merged output; see the
 * module README). minSdk 26 already requires a notification channel to post to; [notifications] is
 * the host's own callback, called once before the first byte moves and again on every throttled
 * progress update, so text, icon, channel and actions all stay the host's decision, never Ferry's.
 */
class RepoDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repoDownloader: RepoDownloader,
    private val notifications: RepoDownloadNotifications,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): WorkResult {
        val repoId = inputData.getString(KEY_REPO_ID)
        val intoPath = inputData.getString(KEY_INTO_PATH)
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)

        if (repoId.isNullOrEmpty() || intoPath.isNullOrEmpty() || notificationId == INVALID_NOTIFICATION_ID) {
            return WorkResult.failure(
                workDataOf(
                    KEY_FAILURE_REASON to REASON_INVALID_INPUT,
                    KEY_FAILURE_MESSAGE to
                        "$KEY_REPO_ID, $KEY_INTO_PATH and a non-zero $KEY_NOTIFICATION_ID are required",
                ),
            )
        }

        return try {
            // Suspend, awaited: promotes this worker to a foreground service before any real work
            // starts, per WorkManager's own guidance, and lets a setForeground failure (a host that
            // never created its notification channel, for instance) surface before a single byte
            // of a possibly multi-gigabyte transfer moves.
            setForeground(foregroundInfo(notifications.notificationFor(repoId, null), notificationId))

            val throttle = RepoDownloadThrottle(nowMillis = nowMillis)
            val result = repoDownloader.download(repoId, File(intoPath)) { progress ->
                if (throttle.shouldEmit(progress)) {
                    setProgressAsync(progress.toProgressData())
                    setForegroundAsync(
                        foregroundInfo(notifications.notificationFor(repoId, progress), notificationId),
                    )
                }
            }

            result.fold(
                onSuccess = { file -> WorkResult.success(workDataOf(KEY_OUTPUT_PATH to file.absolutePath)) },
                onFailure = { error -> failureOrRetry(error) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing may throw across this worker's own public boundary. RepoDownloader.download()
            // already converts what it anticipates into Result.failure; this is this worker's own
            // safety net against whatever it does not — plus setForeground itself, which sits
            // outside that Result entirely and can throw on its own.
            failureOrRetry(e)
        }
    }

    private fun failureOrRetry(error: Throwable): WorkResult =
        if (error is InsufficientSpaceException) {
            WorkResult.failure(
                workDataOf(
                    KEY_FAILURE_REASON to REASON_INSUFFICIENT_SPACE,
                    KEY_FAILURE_MESSAGE to (error.message ?: REASON_INSUFFICIENT_SPACE),
                    KEY_REQUIRED_BYTES to error.report.requiredBytes,
                    KEY_FREE_BYTES to error.report.freeBytes,
                    KEY_SHORTFALL_BYTES to error.report.shortfallBytes,
                ),
            )
        } else {
            WorkResult.retry()
        }

    private fun foregroundInfo(notification: Notification, notificationId: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    private fun RepoProgress.toProgressData(): Data = when (this) {
        is RepoProgress.CheckingSpace -> workDataOf(KEY_PHASE to PHASE_CHECKING_SPACE)

        is RepoProgress.Downloading -> workDataOf(
            KEY_PHASE to PHASE_DOWNLOADING,
            KEY_FILE_INDEX to fileIndex,
            KEY_FILE_COUNT to fileCount,
            KEY_BYTES_WRITTEN to bytesWritten,
            KEY_FILE_BYTES to fileBytes,
        )

        is RepoProgress.Verifying -> workDataOf(KEY_PHASE to PHASE_VERIFYING)

        is RepoProgress.Complete -> workDataOf(KEY_PHASE to PHASE_COMPLETE)
    }

    companion object {
        /** Required input: the repo id to download — see [io.github.nthuat.ferry.Ferry]. */
        const val KEY_REPO_ID = "repoId"

        /** Required input: absolute path of the directory to download into. */
        const val KEY_INTO_PATH = "into"

        /**
         * Required input: the id this download's foreground notification is posted under.
         *
         * Not defaulted: two concurrently running downloads for *different* repo ids — allowed;
         * only same-id concurrency is what [enqueueRepoDownload] closes — need distinct ids or one
         * notification silently replaces the other. [android.app.Service.startForeground] also
         * documents that an id of `0` is invalid, which doubles here as the sentinel for "absent".
         */
        const val KEY_NOTIFICATION_ID = "notificationId"

        /** Output on success: the committed directory's absolute path. */
        const val KEY_OUTPUT_PATH = "outputPath"

        /** Output on failure: one of the `REASON_*` constants below. */
        const val KEY_FAILURE_REASON = "failureReason"

        /** Output on failure: the triggering exception's own message, where one exists. */
        const val KEY_FAILURE_MESSAGE = "failureMessage"

        /** Output on [REASON_INSUFFICIENT_SPACE]: [io.github.nthuat.ferry.SpaceReport.requiredBytes]. */
        const val KEY_REQUIRED_BYTES = "requiredBytes"

        /** Output on [REASON_INSUFFICIENT_SPACE]: [io.github.nthuat.ferry.SpaceReport.freeBytes]. */
        const val KEY_FREE_BYTES = "freeBytes"

        /** Output on [REASON_INSUFFICIENT_SPACE]: [io.github.nthuat.ferry.SpaceReport.shortfallBytes]. */
        const val KEY_SHORTFALL_BYTES = "shortfallBytes"

        /** [KEY_FAILURE_REASON]: the device cannot hold this repo; retrying will not change that. */
        const val REASON_INSUFFICIENT_SPACE = "INSUFFICIENT_SPACE"

        /** [KEY_FAILURE_REASON]: required input ([KEY_REPO_ID]/[KEY_INTO_PATH]/[KEY_NOTIFICATION_ID]) was missing. */
        const val REASON_INVALID_INPUT = "INVALID_INPUT"

        /** [setProgress] key: one of the `PHASE_*` constants below. */
        const val KEY_PHASE = "phase"
        const val PHASE_CHECKING_SPACE = "CHECKING_SPACE"
        const val PHASE_DOWNLOADING = "DOWNLOADING"
        const val PHASE_VERIFYING = "VERIFYING"
        const val PHASE_COMPLETE = "COMPLETE"

        /** [setProgress] keys, [PHASE_DOWNLOADING] only — mirror [RepoProgress.Downloading]'s own fields. */
        const val KEY_FILE_INDEX = "fileIndex"
        const val KEY_FILE_COUNT = "fileCount"
        const val KEY_BYTES_WRITTEN = "bytesWritten"
        const val KEY_FILE_BYTES = "fileBytes"

        private const val INVALID_NOTIFICATION_ID = 0
    }
}
