package io.github.nthuat.ferry.sample

import io.github.nthuat.ferry.RepoProgress

/**
 * Gates how often a [RepoProgress.Downloading] event is allowed to reach the UI.
 *
 * `RepoProgress` arrives on Ferry's IO dispatcher and fires once per read buffer — 8 KB at a time
 * (see `ResumableDownloader`), which is roughly 700,000 callbacks for the 5.6 GB `gpt2` file.
 * Recomposing a row on every one of them would make the whole list stutter under the user's thumb,
 * so only one update per [minIntervalMillis] window is let through; the byte count that arrives with
 * it is still the true, latest one, so nothing shown is ever stale, just less frequent.
 *
 * The other `RepoProgress` kinds — checking space, verifying, complete — fire at most once per file
 * and always pass straight through; throttling them would only make the state that matters most
 * (a file finishing, a repo completing) arrive late.
 *
 * A plain time gate rather than `Flow.sample`: `onProgress` is a synchronous callback, not a `Flow`,
 * and wrapping it in one just to sample it would be more code than this for the same result.
 */
class DownloadingThrottle(
    private val minIntervalMillis: Long = 100L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    // Null, not 0L: "0L" would mean "last emitted at the epoch", which is indistinguishable from a
    // real elapsed-time check whenever `now` itself starts near 0 — true of a fake clock in a test,
    // never true of System.currentTimeMillis(). Modelling "never emitted yet" as its own state keeps
    // the first-event guarantee real instead of an accident of the wall clock being a huge number.
    private var lastEmittedAtMillis: Long? = null

    /**
     * Whether [progress] should reach the UI now.
     *
     * A file's last byte always passes, even mid-window — otherwise a throttled-out final update
     * could leave a row visibly stuck just under 100% until the next file's first event arrives.
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
