package io.github.nthuat.ferry.work

import android.app.Notification
import io.github.nthuat.ferry.RepoProgress

/**
 * Builds the foreground notification a [RepoDownloadWorker] shows while it runs.
 *
 * Ferry owns none of this on purpose: text, icon, notification channel and actions are all
 * product decisions that belong to the host, not to a library — the same reasoning `:ferry` itself
 * never picks a UI framework or a background-work strategy (see `Ferry.kt`'s own KDoc). The host
 * must have already created [notificationFor]'s target channel; nothing here does it, since a
 * channel has a user-visible name and importance that are equally the host's to choose.
 *
 * [progress] is null exactly once per download — the first call, made before any [RepoProgress]
 * exists — so a host can still show an initial "starting…" notification before real numbers are
 * available. Every later call passes the latest [RepoProgress], throttled by [RepoDownloadThrottle]
 * so this is not called once per read buffer.
 */
fun interface RepoDownloadNotifications {
    fun notificationFor(repoId: String, progress: RepoProgress?): Notification
}
