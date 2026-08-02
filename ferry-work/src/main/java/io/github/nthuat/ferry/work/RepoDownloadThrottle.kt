package io.github.nthuat.ferry.work

import io.github.nthuat.ferry.RepoProgress

/**
 * Gates how often a [RepoProgress.Downloading] event is allowed to reach WorkManager.
 *
 * `RepoProgress` fires once per read buffer (8 KB — see `ResumableDownloader`), which is roughly
 * 700,000 callbacks for a 5.6 GB file. [RepoDownloadWorker] feeds every one of them to
 * `setProgressAsync` (a write to WorkManager's own database) and `setForegroundAsync` (a system
 * `NotificationManager` call); doing either that often would make the database the bottleneck and
 * the notification a strobe nobody can read.
 *
 * Same shape of fix as `:sample`'s `DownloadingThrottle`, ported rather than shared: the only
 * module both could live in is `:ferry`, which must not gain a WorkManager dependency, and
 * `:sample` is an app, not something a library depends on. [minIntervalMillis] defaults higher
 * than `:sample`'s 100 ms — a database write and a `NotificationManager` call both cost more than
 * a Compose recomposition, and nobody reads a background download's notification at 10 Hz.
 */
class RepoDownloadThrottle(
    private val minIntervalMillis: Long = 1_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    // Null, not 0L: "0L" would mean "last emitted at the epoch", indistinguishable from a real
    // elapsed-time check when `now` itself starts near 0 — true of a fake clock in a test, never
    // true of System.currentTimeMillis(). Modelling "never emitted yet" as its own state keeps the
    // first-event guarantee real instead of an accident of the wall clock being a huge number.
    private var lastEmittedAtMillis: Long? = null

    /**
     * Whether [progress] should be reported now.
     *
     * A file's last byte always passes, even mid-window — otherwise a throttled-out final update
     * could leave WorkManager's own progress row, and the notification, stuck just under 100%
     * until the next file's first event arrives.
     */
    fun shouldEmit(progress: RepoProgress): Boolean {
        if (progress !is RepoProgress.Downloading) return true

        val fileComplete = progress.bytesWritten >= progress.fileBytes
        val now = nowMillis()
        val sinceLastEmit = lastEmittedAtMillis?.let { now - it }
        if (fileComplete || sinceLastEmit == null || sinceLastEmit >= minIntervalMillis) {
            lastEmittedAtMillis = now
            return true
        }
        return false
    }
}
