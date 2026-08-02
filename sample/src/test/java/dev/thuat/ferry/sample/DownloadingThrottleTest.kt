package dev.thuat.ferry.sample

import dev.thuat.ferry.RepoProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadingThrottleTest {

    private fun downloading(bytesWritten: Long, fileBytes: Long = 1_000L) = RepoProgress.Downloading(
        repoId = "owner/model",
        path = "model.safetensors",
        fileIndex = 0,
        fileCount = 1,
        bytesWritten = bytesWritten,
        fileBytes = fileBytes,
    )

    @Test
    fun `the first downloading event always passes`() {
        val throttle = DownloadingThrottle(minIntervalMillis = 100L, nowMillis = { 0L })

        assertTrue(throttle.shouldEmit(downloading(bytesWritten = 100L)))
    }

    @Test
    fun `a second downloading event inside the window is dropped`() {
        var now = 0L
        val throttle = DownloadingThrottle(minIntervalMillis = 100L, nowMillis = { now })
        throttle.shouldEmit(downloading(bytesWritten = 100L))

        now = 50L
        assertFalse(throttle.shouldEmit(downloading(bytesWritten = 200L)))
    }

    @Test
    fun `a downloading event once the window has elapsed passes`() {
        var now = 0L
        val throttle = DownloadingThrottle(minIntervalMillis = 100L, nowMillis = { now })
        throttle.shouldEmit(downloading(bytesWritten = 100L))

        now = 150L
        assertTrue(throttle.shouldEmit(downloading(bytesWritten = 200L)))
    }

    @Test
    fun `a file's last byte always passes even inside the window`() {
        var now = 0L
        val throttle = DownloadingThrottle(minIntervalMillis = 100L, nowMillis = { now })
        throttle.shouldEmit(downloading(bytesWritten = 100L, fileBytes = 1_000L))

        now = 10L
        assertTrue(
            "the final chunk must never be stuck behind the throttle window",
            throttle.shouldEmit(downloading(bytesWritten = 1_000L, fileBytes = 1_000L)),
        )
    }

    @Test
    fun `checking space, verifying and complete always pass regardless of timing`() {
        val throttle = DownloadingThrottle(minIntervalMillis = 100L, nowMillis = { 0L })
        throttle.shouldEmit(downloading(bytesWritten = 100L))

        assertTrue(throttle.shouldEmit(RepoProgress.CheckingSpace("owner/model")))
        assertTrue(throttle.shouldEmit(RepoProgress.Verifying("owner/model", "model.safetensors")))
        assertTrue(
            throttle.shouldEmit(RepoProgress.Complete("owner/model", java.io.File("/tmp/owner/model"))),
        )
    }
}
