package dev.thuat.ferry.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.thuat.ferry.Ferry
import dev.thuat.ferry.HuggingFace
import dev.thuat.ferry.RepoDownloader
import dev.thuat.ferry.RepoProgress
import dev.thuat.ferry.ResumableDownloader
import dev.thuat.ferry.SpaceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile

/**
 * Holds [SampleUiState] and drives every model's [DownloadState] from real calls into Ferry.
 *
 * `AndroidViewModel` rather than a plain `ViewModel` with a hand-written factory: the only platform
 * thing this needs is `Application.filesDir`, and that constructor shape is exactly what the
 * platform's own default `ViewModelProvider.Factory` already knows how to build — no DI framework,
 * no custom factory, per the brief this app is meant to stay the simplest possible host.
 */
class SampleViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadRoot = File(application.filesDir, "models")

    /** How a real host uses the library. */
    private val realDownloader = Ferry.huggingFace()

    /**
     * Built directly from `RepoDownloader`'s own constructor rather than through `Ferry.huggingFace()`
     * — the facade takes no `FreeSpaceProbe`, deliberately, and this is exactly the seam it leaves
     * open for a caller who needs one: `SpaceCheck(probe = ...)` passed straight into `RepoDownloader`.
     */
    private val sabotageClient = OkHttpClient()
    private val sabotageDownloader = RepoDownloader(
        repo = HuggingFace(client = sabotageClient),
        downloader = ResumableDownloader(sabotageClient),
        spaceCheck = SpaceCheck(probe = { PRETEND_FREE_BYTES }),
    )

    private val _uiState = MutableStateFlow(SampleUiState.Initial)
    val uiState: StateFlow<SampleUiState> = _uiState.asStateFlow()

    /**
     * Repo ids with a `runDownload` currently in flight.
     *
     * `RepoDownloader.download` documents itself as unsafe to call concurrently for the same repo id
     * and target directory — both calls stage into the same scratch directory. The UI already hides
     * a row's action button for as long as it is mid-transfer, which rules out a double-tap, but
     * [setLowDiskSimulation]'s eager sweep calls `runDownload` directly for every eligible row, so a
     * manual tap can still land on the same repo id the sweep just started for it. This is the one
     * guard against that, shared by every call path. A plain `MutableSet` is safe unsynchronized: every
     * mutation happens on `viewModelScope`'s `Dispatchers.Main.immediate`, and every call here starts
     * from the main thread, so `runDownload`'s guard always runs synchronously before its first
     * suspension point — there is no window for two calls to interleave.
     */
    private val inFlightRepoIds = mutableSetOf<String>()

    /**
     * Surfaces staging a process death (or any attempt this app never got to retry) left behind:
     * every catalog entry with [RepoDownloader.stagedBytes] above zero starts life as
     * [DownloadState.Interrupted] instead of [DownloadState.Available], offering Resume/Discard
     * rather than silently sitting on bytes a resumed [download] could reuse.
     *
     * [DownloadState.withStagedBytes] — not this block itself — is what makes this safe against the
     * one real race: a user tapping Download on a row before this file-system walk resolves. That
     * function only ever overrides a row still [DownloadState.Available] by the time it runs, so a
     * row `runDownload` has already moved on from `Available` wins over a stale "interrupted" verdict
     * arriving late, rather than being clobbered by it.
     *
     * Runs once, here, rather than on some recurring timer: nothing other than this app's own
     * [download] and [discard] touch its staging, so nothing can go stale after the first read.
     */
    init {
        viewModelScope.launch {
            val stagedByRepoId = withContext(Dispatchers.IO) {
                SAMPLE_CATALOG.associate { it.repoId to realDownloader.stagedBytes(it.repoId, downloadRoot) }
            }
            _uiState.update { state ->
                state.copy(
                    rows = state.rows.map { row ->
                        row.copy(state = row.state.withStagedBytes(stagedByRepoId[row.catalog.repoId] ?: 0L))
                    },
                )
            }
        }
    }

    /** The Download action — also what an `Interrupted` row's Resume button calls; see that state's own doc. */
    fun download(repoId: String) {
        val downloader = if (_uiState.value.sabotage.lowDiskSimulationEnabled) sabotageDownloader else realDownloader
        viewModelScope.launch { runDownload(repoId, downloader) }
    }

    /**
     * The Re-check action on a `Downloaded` row. Always the real downloader, not routed through the
     * sabotage toggle like [download] is: re-checking a row already known to be a good cache hit is
     * about confirming what Ferry already verified, not about demonstrating the low-disk refusal —
     * routing it through the sabotage downloader would only be interesting for the corruption
     * control below, which already has its own dedicated button. Kept out of scope for this control;
     * see the sample report.
     */
    fun recheck(repoId: String) {
        viewModelScope.launch { runDownload(repoId, realDownloader) }
    }

    /**
     * The Discard action on an `Interrupted` row. Reclaims the staging that row's caption described,
     * via [RepoDownloader.abandon] — which, per its own doc, never touches a previously committed
     * copy of the same repo id, only this attempt's staging.
     *
     * Guarded by [inFlightRepoIds] the same way [runDownload] is: `abandon` and `download` touch the
     * same staging directory, so a Discard tap racing a Resume tap for the same row is exactly the
     * concurrent-access case that field already exists to rule out.
     */
    fun discard(repoId: String) {
        if (!inFlightRepoIds.add(repoId)) return // already busy for this repo id — see the field's doc
        viewModelScope.launch {
            try {
                realDownloader.abandon(repoId, downloadRoot).fold(
                    onSuccess = { setRowState(repoId, DownloadState.Available) },
                    onFailure = { error -> setRowState(repoId, error.toDownloadState()) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // abandon() converts every failure it anticipates into Result.failure; this is the
                // sample's own safety net against whatever it does not — see runDownload's own catch.
                setRowState(repoId, DownloadState.Failed(e.message ?: e::class.java.simpleName))
            } finally {
                inFlightRepoIds.remove(repoId)
            }
        }
    }

    /**
     * Toggles the "pretend the disk is nearly full" control.
     *
     * Turning it on immediately re-checks every model that hasn't already been downloaded, so all
     * three flip to `WontFit` together rather than waiting for three separate taps. Models already
     * downloaded or mid-transfer are left alone — see [recheck]'s doc for why running the sabotage
     * check against something already complete would be misleading rather than illustrative.
     *
     * Turning it off resets any row the toggle put into `WontFit` back to `Available` — including a
     * row whose sabotage check is still in flight when the toggle flips off: [runDownload]'s own
     * staleness check is what makes that half of the promise hold too, since this method alone can
     * only reset rows that have *already* resolved to `WontFit` by the instant it runs.
     */
    fun setLowDiskSimulation(enabled: Boolean) {
        val wasEnabled = _uiState.value.sabotage.lowDiskSimulationEnabled
        _uiState.update { it.copy(sabotage = it.sabotage.copy(lowDiskSimulationEnabled = enabled)) }

        if (enabled && !wasEnabled) {
            _uiState.value.rows
                .filter { it.state.canAttemptFreshCheck }
                .forEach { row -> viewModelScope.launch { runDownload(row.catalog.repoId, sabotageDownloader) } }
        } else if (!enabled && wasEnabled) {
            _uiState.update { state ->
                state.copy(
                    rows = state.rows.map { row ->
                        if (row.state is DownloadState.WontFit) row.copy(state = DownloadState.Available) else row
                    },
                )
            }
        }
    }

    /**
     * Flips one byte inside the largest on-disk file of the first `Downloaded` model found.
     *
     * The largest file, rather than a hardcoded name: every real HuggingFace repo's biggest file is
     * its model weights, and weights are what git-lfs — and so `sha256` — tracks. A file with no
     * published hash is verified by size alone (see `RepoDownloader`), and flipping one byte in place
     * never changes a file's length, so corrupting a hash-less file here would go undetected for a
     * reason that has nothing to do with the guarantee this control exists to demonstrate.
     */
    fun corruptADownloadedFile() {
        val row = _uiState.value.rows.firstOrNull { it.state is DownloadState.Downloaded } ?: return
        viewModelScope.launch {
            val caption = withContext(Dispatchers.IO) { corruptLargestFile(row.catalog) }
            _uiState.update { it.copy(sabotage = it.sabotage.copy(lastCorruption = caption)) }
        }
    }

    private fun corruptLargestFile(catalog: ModelCatalogEntry): String {
        val dir = File(downloadRoot, catalog.repoId)
        // FERRY_MARKER_FILE mirrors RepoDownloader's own private MARKER_FILE constant — not part of
        // Ferry's public API, so this is an assumption about a library internal, not a contract. The
        // actual safety net is maxByOrNull below: a marker is a few bytes of repoId text and will
        // essentially never out-size real model weights, so this exclusion staying accurate is a
        // nice-to-have, not what keeps the corruption targeted at a real, hash-checked file.
        val target = dir.walkTopDown()
            .filter { it.isFile && it.name != FERRY_MARKER_FILE && it.length() > 0 }
            .maxByOrNull { it.length() }
            ?: return "No file found on disk for ${catalog.displayName} to corrupt."

        return try {
            RandomAccessFile(target, "rw").use { file ->
                val original = file.read()
                file.seek(0)
                file.write(original xor 0xFF)
            }
            "Flipped a byte in ${target.name} (${catalog.displayName}). Tap Re-check to watch Ferry notice."
        } catch (e: java.io.IOException) {
            "Could not corrupt ${target.name}: ${e.message}"
        }
    }

    private suspend fun runDownload(repoId: String, downloader: RepoDownloader) {
        if (!inFlightRepoIds.add(repoId)) return // already running for this repo id — see the field's doc

        val throttle = DownloadingThrottle()
        var sawTransfer = false
        var lastFileCount: Int? = null
        try {
            val result = downloader.download(repoId, downloadRoot) { progress ->
                if (progress is RepoProgress.Downloading) {
                    sawTransfer = true
                    lastFileCount = progress.fileCount
                }
                if (throttle.shouldEmit(progress)) {
                    setRowState(repoId, progress.toDownloadState(sawTransfer, lastFileCount))
                }
            }

            if (isStaleSabotageResult(downloader)) {
                // The toggle flipped off while this call — routed through sabotageDownloader — was
                // still in flight (its manifest fetch is a real network round trip, so there is a
                // real window for that). Applying its result now would show a refusal the switch on
                // screen no longer claims to be simulating, so it is discarded in favour of Available
                // rather than trusted: this is what makes setLowDiskSimulation's own "turning it off
                // resets..." promise hold even for a check that outlives the toggle.
                setRowState(repoId, DownloadState.Available)
            } else {
                result.onFailure { error -> setRowState(repoId, error.toDownloadState()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // download() converts every failure it anticipates into Result.failure; this is the
            // sample's own safety net against whatever it does not, so a demo of "refuse cleanly"
            // never regresses into "crash instead" for its own users.
            val fallback = DownloadState.Failed(e.message ?: e::class.java.simpleName)
            setRowState(repoId, if (isStaleSabotageResult(downloader)) DownloadState.Available else fallback)
        } finally {
            inFlightRepoIds.remove(repoId)
        }
    }

    /** True once the sabotage toggle has flipped off since a sabotage-routed call started — see [runDownload]. */
    private fun isStaleSabotageResult(downloader: RepoDownloader): Boolean =
        downloader === sabotageDownloader && !_uiState.value.sabotage.lowDiskSimulationEnabled

    private fun setRowState(repoId: String, state: DownloadState) {
        _uiState.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    if (row.catalog.repoId == repoId) row.copy(state = state) else row
                },
            )
        }
    }

    private val DownloadState.canAttemptFreshCheck: Boolean
        get() = this is DownloadState.Available || this is DownloadState.WontFit || this is DownloadState.Failed

    private companion object {
        /**
         * Smaller than any catalog model's own size (the smallest, tiny-gpt2, is 4.7 MB) — not just
         * smaller than gpt2's — so every model flips to `WontFit`, not only the one too big to fit
         * on a normal device.
         */
        const val PRETEND_FREE_BYTES = 1_000_000L

        const val FERRY_MARKER_FILE = ".ferry"
    }
}
