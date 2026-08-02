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
 * Three distinct reasons reach [WorkResult.failure] today, each in [KEY_FAILURE_REASON]:
 * [REASON_INVALID_INPUT] (bad enqueue, caught before any of the above ever runs),
 * [REASON_INSUFFICIENT_SPACE] ([KEY_FAILURE_MESSAGE] plus [KEY_REQUIRED_BYTES] /
 * [KEY_FREE_BYTES] / [KEY_SHORTFALL_BYTES] from the report), and [REASON_RETRIES_EXHAUSTED]
 * ([KEY_FAILURE_MESSAGE] alone — see Retry for when this one fires). What is lost even so: the
 * exception's Java type (a [String] reason stands in for it), any cause chain, and a stack trace —
 * none of those are primitives. [VerificationException.path] is never carried structurally, in
 * either terminal state; it survives only as text, inside [KEY_FAILURE_MESSAGE], because
 * [VerificationException]'s own `message` already embeds the path (see its KDoc in `:ferry`) — see
 * Retry for why this exception in particular usually reaches [REASON_RETRIES_EXHAUSTED] rather
 * than [WorkResult.retry] forever.
 *
 * Progress carries less structure than either terminal state: [toProgressData] reports only a
 * phase name and, for [RepoProgress.Downloading], byte/file counters — [RepoProgress.Downloading.path],
 * [RepoProgress.Verifying.path] and [RepoProgress.Complete.dir] are all dropped, and
 * `repoId` is never repeated since the host already supplied it as input. Progress is a much
 * higher-frequency boundary than the terminal result — see Progress below — and none of those
 * fields were needed to answer "how far along is this," which is the only question a throttled,
 * primitives-only progress channel is answering.
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
 * **That split alone is not a bound.** [androidx.work.WorkRequest]'s exponential backoff makes
 * retries less *frequent*, never fewer — a [VerificationException] that will in fact never pass
 * (a hub permanently serving the wrong bytes for a path, not a transit corruption) would otherwise
 * retry for the life of the install, waking the device and re-downloading a possibly multi-gigabyte
 * repo each time to fail identically. Retrying it at all is still correct — most verification
 * failures are transit corruption, which a second attempt fixes — but the retry needs a ceiling for
 * the case where it will not. [MAX_RETRY_ATTEMPTS] is that ceiling, read off [runAttemptCount] —
 * **`0` on a worker's real first execution, not `1`.** `WorkerWrapper` (in `work-runtime`) builds
 * a worker's [WorkerParameters] from `WorkSpec.runAttemptCount` — which defaults to `0` for a
 * freshly enqueued item — and only increments the stored count afterwards, when it marks the work
 * running; the worker itself never observes the post-increment value for its own attempt. So real
 * attempts are numbered 0, 1, 2, … from this class's point of view, one behind how a human would
 * count them. [attemptNumber] converts to that 1-based count once, so the comparison below reads
 * as "attempt number versus how many are allowed" rather than repeating a `+ 1` at every call site.
 * (`TestListenableWorkerBuilder` defaults `runAttemptCount` to `1`, not `0` — a test-harness
 * convenience, not the value any real first attempt has; [RepoDownloadWorkerTest] sets it
 * explicitly rather than relying on either default.) [MAX_RETRY_ATTEMPTS] itself is chosen as
 * "a few, not many" — enough that an ordinary transient blip (a dropped wifi handoff, a brief 5xx)
 * gets several real chances across a growing backoff window, small enough that a permanently bad
 * file stops re-downloading a large repo after a handful of full attempts rather than for the life
 * of the install. Past the ceiling, [REASON_RETRIES_EXHAUSTED] reuses [KEY_FAILURE_REASON] rather
 * than adding a second signal, so a host can tell "exhausted retries" apart from "failed once"
 * through the one field it already reads. A host wanting a different ceiling has the same lever
 * this class does — [androidx.work.WorkInfo.getRunAttemptCount] — and can cancel the work itself;
 * duplicating that as a constructor parameter here would only be a second, worse copy of it.
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

    /**
     * This attempt, counted the way a human would — `1` for a worker's real first execution.
     *
     * [runAttemptCount] itself is `0` on that same first execution (see the class KDoc's Retry
     * section for why), so this is `runAttemptCount + 1` computed in exactly one place, rather
     * than repeating that `+ 1` at every comparison against [MAX_RETRY_ATTEMPTS] — which is itself
     * how many attempts are allowed, not a raw [runAttemptCount] value to compare against directly.
     */
    private val attemptNumber: Int get() = runAttemptCount + 1

    private fun failureOrRetry(error: Throwable): WorkResult = when {
        error is InsufficientSpaceException -> WorkResult.failure(
            workDataOf(
                KEY_FAILURE_REASON to REASON_INSUFFICIENT_SPACE,
                KEY_FAILURE_MESSAGE to (error.message ?: REASON_INSUFFICIENT_SPACE),
                KEY_REQUIRED_BYTES to error.report.requiredBytes,
                KEY_FREE_BYTES to error.report.freeBytes,
                KEY_SHORTFALL_BYTES to error.report.shortfallBytes,
            ),
        )
        // Below the ceiling: still worth a fresh attempt. At or past it: retrying has already had
        // its fair chances (see the class KDoc's Retry section for why a bound is needed at all,
        // and why this compares attemptNumber rather than runAttemptCount directly).
        attemptNumber < MAX_RETRY_ATTEMPTS -> WorkResult.retry()
        else -> WorkResult.failure(
            workDataOf(
                KEY_FAILURE_REASON to REASON_RETRIES_EXHAUSTED,
                KEY_FAILURE_MESSAGE to (error.message ?: REASON_RETRIES_EXHAUSTED),
            ),
        )
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

        /** [KEY_FAILURE_REASON]: failed on [MAX_RETRY_ATTEMPTS] separate attempts; see the class KDoc's Retry section. */
        const val REASON_RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"

        /**
         * How many attempts — first try plus retries — a non-space failure gets before this worker
         * gives up rather than returning [androidx.work.ListenableWorker.Result.retry] again. See
         * the class KDoc's Retry section for why a bound is needed at all and why this number.
         */
        const val MAX_RETRY_ATTEMPTS = 5

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
