package io.github.nthuat.ferry.work

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.nthuat.ferry.ModelRepo
import io.github.nthuat.ferry.RemoteFile
import io.github.nthuat.ferry.RepoDownloader
import io.github.nthuat.ferry.RepoManifest
import io.github.nthuat.ferry.RepoProgress
import io.github.nthuat.ferry.ResumableDownloader
import io.github.nthuat.ferry.SpaceCheck
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs [RepoDownloadWorker] through `work-testing`'s [TestListenableWorkerBuilder] — a plain JVM
 * unit test, no Robolectric and no device, confirmed by these tests actually running that way:
 * this module's `build.gradle.kts` carries the same `unitTests.isReturnDefaultValues = true`
 * `:ferry` does, and adds nothing Android-instrumented. A bare [Application] stands in for the
 * [Context] `TestListenableWorkerBuilder` requires — its no-arg constructor does no platform work
 * under that flag, and this worker never calls a platform method on its own `applicationContext`
 * directly, only on the fakes `TestListenableWorkerBuilder` wires `setForeground`/
 * `setProgressAsync` through.
 */
class RepoDownloadWorkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val configBody = """{"model_type":"qwen2"}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Mirrors `RepoDownloaderTest`'s own helper: a repo whose files MockWebServer serves. */
    private fun fakeRepo(files: List<RemoteFile>) = object : ModelRepo {
        override suspend fun manifest(repoId: String) = Result.success(RepoManifest(repoId, files))
    }

    private fun remote(path: String, size: Long) = RemoteFile(
        path = path,
        url = server.url("/resolve/$path").toString(),
        sizeBytes = size,
        sha256 = null,
    )

    private fun downloaderFor(files: List<RemoteFile>, freeBytes: Long = Long.MAX_VALUE) = RepoDownloader(
        repo = fakeRepo(files),
        downloader = ResumableDownloader(OkHttpClient()),
        spaceCheck = SpaceCheck(probe = { freeBytes }, headroomBytes = 0L),
    )

    private fun buildWorker(
        repoDownloader: RepoDownloader,
        repoId: String = "owner/model",
        notificationId: Int = 42,
        notifications: RepoDownloadNotifications = RepoDownloadNotifications { _, _ -> Notification() },
        nowMillis: () -> Long = System::currentTimeMillis,
        runAttemptCount: Int = 1,
    ): RepoDownloadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = RepoDownloadWorker(appContext, workerParameters, repoDownloader, notifications, nowMillis)
        }
        return TestListenableWorkerBuilder<RepoDownloadWorker>(Application())
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(
                workDataOf(
                    RepoDownloadWorker.KEY_REPO_ID to repoId,
                    RepoDownloadWorker.KEY_INTO_PATH to temp.root.absolutePath,
                    RepoDownloadWorker.KEY_NOTIFICATION_ID to notificationId,
                ),
            )
            .build()
    }

    @Test
    fun `a successful download returns success and reports the output`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))

        val result = runBlocking { buildWorker(downloaderFor(files)).doWork() }

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            File(temp.root, "owner/model").absolutePath,
            (result as ListenableWorker.Result.Success).outputData.getString(RepoDownloadWorker.KEY_OUTPUT_PATH),
        )
    }

    /**
     * The central retry-vs-fail distinction this worker exists to make: a full device fails the
     * same way on every future attempt, so retrying it is a wasted backoff slot, not a fresh
     * chance — see [RepoDownloadWorker]'s own "Retry" KDoc section.
     */
    @Test
    fun `insufficient space fails rather than retries`() {
        val files = listOf(remote("model.bin", 10_000L))

        val result = runBlocking { buildWorker(downloaderFor(files, freeBytes = 5_000L)).doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
        val data = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(RepoDownloadWorker.REASON_INSUFFICIENT_SPACE, data.getString(RepoDownloadWorker.KEY_FAILURE_REASON))
        assertEquals(10_000L, data.getLong(RepoDownloadWorker.KEY_REQUIRED_BYTES, -1))
        assertEquals(5_000L, data.getLong(RepoDownloadWorker.KEY_FREE_BYTES, -1))
        assertEquals(5_000L, data.getLong(RepoDownloadWorker.KEY_SHORTFALL_BYTES, -1))
    }

    /**
     * The other half of the same distinction: a dropped connection is exactly the kind of failure
     * a later attempt might not hit again, so it must retry rather than give up permanently.
     */
    @Test
    fun `a transient IO failure retries rather than fails`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = runBlocking { buildWorker(downloaderFor(files)).doWork() }

        assertTrue(result is ListenableWorker.Result.Retry)
    }

    /**
     * The bound on that same retry: exponential backoff makes attempts less frequent, never fewer,
     * so an always-failing case (a permanently wrong published hash, a permanently offline host)
     * needs an actual ceiling, not just a longer wait between identical failures. One attempt short
     * of [RepoDownloadWorker.MAX_RETRY_ATTEMPTS] must still behave like the ordinary case above.
     */
    @Test
    fun `a transient IO failure one attempt short of the retry ceiling still retries`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val worker = buildWorker(downloaderFor(files), runAttemptCount = RepoDownloadWorker.MAX_RETRY_ATTEMPTS - 1)
        val result = runBlocking { worker.doWork() }

        assertTrue(result is ListenableWorker.Result.Retry)
    }

    /** The ceiling itself: the next attempt must fail outright and say why, rather than retry again. */
    @Test
    fun `a transient IO failure at the retry ceiling fails and reports why`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val worker = buildWorker(downloaderFor(files), runAttemptCount = RepoDownloadWorker.MAX_RETRY_ATTEMPTS)
        val result = runBlocking { worker.doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            RepoDownloadWorker.REASON_RETRIES_EXHAUSTED,
            (result as ListenableWorker.Result.Failure).outputData.getString(RepoDownloadWorker.KEY_FAILURE_REASON),
        )
    }

    /**
     * A frozen clock makes the throttle's own window never elapse, so every `Downloading` tick
     * except the very first and the file's own last byte (which always passes — see
     * [RepoDownloadThrottle]) must be suppressed, regardless of how many raw reads the transfer
     * actually took — robust to OkHttp/MockWebServer's own read-chunking, which this test does not
     * control. A body many times the 8 KB read buffer proves this is a real reduction, not merely
     * a file too small to tell throttled apart from unthrottled.
     */
    @Test
    fun `progress is throttled rather than reported for every buffer`() {
        val body = "x".repeat(50_000)
        val files = listOf(remote("model.bin", body.length.toLong()))
        server.enqueue(MockResponse().setBody(body))

        val seenProgress = mutableListOf<RepoProgress?>()
        val notifications = RepoDownloadNotifications { _, progress -> seenProgress += progress; Notification() }

        val result = runBlocking {
            buildWorker(downloaderFor(files), notifications = notifications, nowMillis = { 0L }).doWork()
        }

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "a frozen clock must collapse every mid-window tick to just the first and the final byte",
            2,
            seenProgress.filterIsInstance<RepoProgress.Downloading>().size,
        )
    }
}
