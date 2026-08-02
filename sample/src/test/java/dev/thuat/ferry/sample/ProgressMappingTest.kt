package dev.thuat.ferry.sample

import dev.thuat.ferry.InsufficientSpaceException
import dev.thuat.ferry.RepoProgress
import dev.thuat.ferry.SpaceReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The mapping between `RepoProgress`/`Throwable` and `DownloadState` is the one piece of this
 * sample worth testing on its own: everything downstream of Ferry's own exhaustively-tested
 * behaviour, but easy to get wrong, especially the cache-hit bit — a lone `Complete` event carries
 * no memory of whether a `Downloading` event preceded it in the same attempt.
 */
class ProgressMappingTest {

    @Test
    fun `checking space maps to the checking state regardless of prior transfer`() {
        val progress = RepoProgress.CheckingSpace("owner/model")

        assertEquals(DownloadState.CheckingSpace, progress.toDownloadState(sawTransfer = false))
        assertEquals(DownloadState.CheckingSpace, progress.toDownloadState(sawTransfer = true))
    }

    @Test
    fun `downloading carries file position and byte counts through unchanged`() {
        val progress = RepoProgress.Downloading(
            repoId = "owner/model",
            path = "model.safetensors",
            fileIndex = 2,
            fileCount = 9,
            bytesWritten = 4_096L,
            fileBytes = 10_000L,
        )

        val state = progress.toDownloadState(sawTransfer = false)

        assertEquals(
            DownloadState.Downloading(
                fileIndex = 2,
                fileCount = 9,
                path = "model.safetensors",
                bytesWritten = 4_096L,
                fileBytes = 10_000L,
            ),
            state,
        )
    }

    @Test
    fun `verifying carries the file path through unchanged`() {
        val progress = RepoProgress.Verifying("owner/model", "model.safetensors")

        assertEquals(DownloadState.Verifying("model.safetensors"), progress.toDownloadState(sawTransfer = true))
    }

    @Test
    fun `skipped maps to the verifying state, carrying the file path`() {
        val progress = RepoProgress.Skipped("owner/model", "config.json", fileIndex = 0, fileCount = 2)

        assertEquals(DownloadState.Verifying("config.json"), progress.toDownloadState(sawTransfer = false))
    }

    @Test
    fun `complete after a real transfer is not a cache hit`() {
        val progress = RepoProgress.Complete("owner/model", File("/tmp/owner/model"))

        val state = progress.toDownloadState(sawTransfer = true)

        assertEquals(DownloadState.Downloaded(cacheHit = false), state)
    }

    @Test
    fun `complete with no prior downloading event is a cache hit`() {
        val progress = RepoProgress.Complete("owner/model", File("/tmp/owner/model"))

        val state = progress.toDownloadState(sawTransfer = false)

        assertEquals(DownloadState.Downloaded(cacheHit = true), state)
    }

    @Test
    fun `complete after a real transfer carries the live file count from the last downloading event`() {
        val progress = RepoProgress.Complete("owner/model", File("/tmp/owner/model"))

        val state = progress.toDownloadState(sawTransfer = true, lastFileCount = 9)

        assertEquals(DownloadState.Downloaded(cacheHit = false, fileCount = 9), state)
    }

    @Test
    fun `a cache hit carries no file count even if one is passed in`() {
        // Ferry's cache-hit path never fires a Downloading event, so there is never a real file
        // count available for it — passing one anyway (a stale value left over from a previous
        // attempt, say) must not leak into the row, or the row would show a number Ferry never
        // actually reported for this outcome.
        val progress = RepoProgress.Complete("owner/model", File("/tmp/owner/model"))

        val state = progress.toDownloadState(sawTransfer = false, lastFileCount = 9)

        assertEquals(DownloadState.Downloaded(cacheHit = true, fileCount = null), state)
    }

    @Test
    fun `insufficient space maps to wont-fit carrying the report's own numbers`() {
        val report = SpaceReport(requiredBytes = 5_632_417_295L, freeBytes = 1_000_000L, headroomBytes = 0L)
        val error: Throwable = InsufficientSpaceException(report)

        val state = error.toDownloadState()

        assertEquals(
            DownloadState.WontFit(
                requiredBytes = 5_632_417_295L,
                freeBytes = 1_000_000L,
                shortfallBytes = report.shortfallBytes,
            ),
            state,
        )
    }

    @Test
    fun `any other failure maps to failed carrying its own message`() {
        val error: Throwable = IOException("HTTP 503 listing owner/model")

        val state = error.toDownloadState()

        assertEquals(DownloadState.Failed("HTTP 503 listing owner/model"), state)
    }

    @Test
    fun `a failure with no message falls back to its class name rather than showing nothing`() {
        val error: Throwable = NoMessageException()

        val state = error.toDownloadState()

        // Hardcoded rather than re-derived from `error::class.java.simpleName`: recomputing the same
        // expression the code under test uses would make this pass even if that expression degenerated
        // to something unhelpful — an anonymous class's simpleName is "", for instance, which is why
        // this uses a named class instead of one.
        assertEquals(DownloadState.Failed("NoMessageException"), state)
    }

    /** A named class, deliberately: an anonymous one's `simpleName` is "", which would hide a blank fallback. */
    private class NoMessageException : IOException()
}
