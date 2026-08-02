package dev.thuat.ferry.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.thuat.ferry.RepoDownloader

/**
 * Wires a host's [RepoDownloader] and [RepoDownloadNotifications] into every [RepoDownloadWorker]
 * WorkManager creates.
 *
 * WorkManager's default worker factory instantiates workers by reflection on a
 * `(Context, WorkerParameters)` constructor. [RepoDownloadWorker] takes more than that —
 * [repoDownloader] wraps an `OkHttpClient` and a hub choice, [notifications] wraps a host's
 * notification design — and neither can travel through [androidx.work.Data], which is primitives
 * only (see [RepoDownloadWorker]'s own KDoc). A [WorkerFactory] is WorkManager's own answer to
 * exactly this: register one, and it stands in for the default for every worker class it claims.
 *
 * Register once, at process startup, not per download — typically `Application`'s own
 * `Configuration.Provider`:
 *
 * ```kotlin
 * class App : Application(), Configuration.Provider {
 *     override val workManagerConfiguration: Configuration
 *         get() = Configuration.Builder()
 *             .setWorkerFactory(
 *                 RepoDownloadWorkerFactory(
 *                     repoDownloader = Ferry.huggingFace(),
 *                     notifications = MyRepoDownloadNotifications(this),
 *                 ),
 *             )
 *             .build()
 * }
 * ```
 *
 * A host already using WorkManager for other workers, with its own factory, should delegate to
 * this one from theirs — for example via [androidx.work.DelegatingWorkerFactory] — rather than
 * replace it outright.
 */
class RepoDownloadWorkerFactory(
    private val repoDownloader: RepoDownloader,
    private val notifications: RepoDownloadNotifications,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        RepoDownloadWorker::class.java.name ->
            RepoDownloadWorker(appContext, workerParameters, repoDownloader, notifications)
        else -> null
    }
}
