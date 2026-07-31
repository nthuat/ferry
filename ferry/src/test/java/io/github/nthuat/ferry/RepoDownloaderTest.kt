package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val configBody = """{"model_type":"qwen2"}"""
    private val weightsBody = "WEIGHTS-BYTES"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A repo whose files are served by MockWebServer, with hashes computed rather than guessed. */
    private fun fakeRepo(files: List<RemoteFile>) = object : ModelRepo {
        override suspend fun manifest(repoId: String) =
            Result.success(RepoManifest(repoId, files))
    }

    /** Builds a RemoteFile pointing at this test's server. */
    private fun remote(path: String, size: Long, sha256: String? = null) = RemoteFile(
        path = path,
        url = server.url("/resolve/$path").toString(),
        sizeBytes = size,
        sha256 = sha256,
    )

    private fun downloaderFor(
        files: List<RemoteFile>,
        freeBytes: Long = Long.MAX_VALUE,
    ) = RepoDownloader(
        repo = fakeRepo(files),
        downloader = ResumableDownloader(OkHttpClient()),
        spaceCheck = SpaceCheck(probe = { freeBytes }, headroomBytes = 0L),
    )

    private fun shaOf(content: String): String =
        Sha256.of(temp.newFile().apply { writeText(content) })

    @Test
    fun `downloads every file and commits the directory`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        val dir = runBlocking { downloaderFor(files).download("Qwen/Q-0.5B", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dir, "config.json").readText())
        assertEquals(weightsBody, File(dir, "model.bin").readText())
    }

    /** "a/b" flattened to "a--b" would collide with a repo literally named "a--b"; nesting can't. */
    @Test
    fun `repo ids that would collide when flattened resolve to separate directories`() {
        val filesA = listOf(remote("config.json", configBody.length.toLong()))
        val filesB = listOf(remote("model.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        val dirA = runBlocking { downloaderFor(filesA).download("a/b", temp.root) }.getOrThrow()
        val dirB = runBlocking { downloaderFor(filesB).download("a--b", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dirA, "config.json").readText())
        assertEquals(weightsBody, File(dirB, "model.bin").readText())
    }

    @Test
    fun `refuses to start when space is insufficient`() {
        val files = listOf(remote("model.bin", 10_000L))

        val result = runBlocking { downloaderFor(files, freeBytes = 5_000L).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
    }

    @Test
    fun `refusing on space makes no network request`() {
        val files = listOf(remote("model.bin", 10_000L))

        runBlocking { downloaderFor(files, freeBytes = 5_000L).download("a/b", temp.root) }

        assertEquals("must not spend the user's data to discover this", 0, server.requestCount)
    }

    @Test
    fun `the space failure carries the numbers needed to explain it`() {
        val files = listOf(remote("model.bin", 10_000L))

        val result = runBlocking { downloaderFor(files, freeBytes = 4_000L).download("a/b", temp.root) }
        val report = (result.exceptionOrNull() as InsufficientSpaceException).report

        assertEquals(10_000L, report.requiredBytes)
        assertEquals(4_000L, report.freeBytes)
        assertEquals(6_000L, report.shortfallBytes)
    }

    @Test
    fun `a file failing verification fails the whole repo`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")))
        server.enqueue(MockResponse().setBody(weightsBody))

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VerificationException)
    }

    @Test
    fun `nothing is committed when a file fails verification`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        runBlocking { downloaderFor(files).download("a/b", temp.root) }

        val committed = File(temp.root, "a/b")
        assertFalse("a half-verified repo must not be readable", committed.exists())
    }

    @Test
    fun `files without a published hash are accepted`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isSuccess)
    }

    @Test
    fun `progress reports space check, every file, verification and completion`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))

        val seen = mutableListOf<RepoProgress>()
        runBlocking { downloaderFor(files).download("a/b", temp.root) { seen += it } }

        assertTrue(seen.first() is RepoProgress.CheckingSpace)
        assertTrue(seen.any { it is RepoProgress.Downloading && it.path == "model.bin" })
        assertTrue(seen.any { it is RepoProgress.Verifying })
        assertTrue(seen.last() is RepoProgress.Complete)
    }

    @Test
    fun `progress numbers each file within the repo`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong()),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        val seen = mutableListOf<RepoProgress.Downloading>()
        runBlocking {
            downloaderFor(files).download("a/b", temp.root) { if (it is RepoProgress.Downloading) seen += it }
        }

        assertEquals(2, seen.map { it.fileIndex }.distinct().size)
        assertTrue(seen.all { it.fileCount == 2 })
    }

    /**
     * MNN models already-present models as a source of their own. The equivalent here is cheaper:
     * a repo already on disk and verifying is a hit, not a source. Re-fetching gigabytes the device
     * already holds is the most expensive bug this class could have.
     */
    @Test
    fun `an already downloaded and verified repo is not fetched again`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))

        val first = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()
        val requestsAfterFirst = server.requestCount

        val second = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(first, second)
        assertEquals("second call must not transfer bytes", requestsAfterFirst, server.requestCount)
    }

    /** A present-but-wrong file is not a hit. Corruption on disk must be re-fetched, not trusted. */
    @Test
    fun `a present repo failing verification is downloaded again`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        File(temp.root, "a/b").mkdirs()
        File(temp.root, "a/b/model.bin").writeText("CORRUPTED")
        server.enqueue(MockResponse().setBody(weightsBody))

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(weightsBody, File(dir, "model.bin").readText())
    }

    /**
     * Same shape as a bug fixed in Task 1 (an invalid baseUrl thrown instead of returned): the
     * cache-hit check re-hashes an existing file, which is I/O and can fail for reasons unrelated
     * to whether the file is correct. That failure must become Result.failure, not escape download().
     */
    @Test
    fun `a cache-hit check that cannot read a file fails instead of throwing`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        val existing = File(temp.root, "a/b/model.bin")
        existing.parentFile?.mkdirs()
        existing.writeText(weightsBody)
        existing.setReadable(false)

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isFailure)
    }

    /**
     * repoId comes from the calling app; a hostile or buggy caller must not escape `into`. The
     * response is enqueued (even though the fixed code must never consume it) so that a mutated,
     * unguarded version of this code would actually complete the write — otherwise this test would
     * pass for the wrong reason, via an unrelated network timeout instead of the escape being caught.
     */
    @Test
    fun `a repo id that tries to escape the target directory fails instead of writing outside it`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        val escaped = File(temp.root.parentFile, "escape")

        val result = runBlocking { downloaderFor(files).download("../escape", temp.root) }

        assertTrue(result.isFailure)
        assertFalse("must not create anything outside the target directory", escaped.exists())
        assertEquals("must not spend the user's data before validating the path", 0, server.requestCount)
    }

    /**
     * remote.path comes from the hub's manifest over the network. A hostile or compromised listing
     * must not turn a download into an arbitrary file write outside the staging directory.
     */
    @Test
    fun `a manifest file path that tries to escape the staging directory fails and writes nothing outside it`() {
        val files = listOf(remote("../../escaped.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))
        val escaped = File(temp.root, "escaped.bin")

        val result = runBlocking { downloaderFor(files).download("repo", temp.root) }

        assertTrue(result.isFailure)
        assertFalse("must not write outside the staging directory", escaped.exists())
    }

    /** The escape check must reject only real escapes, not ordinary subdirectories within a repo. */
    @Test
    fun `a file path containing a legitimate subdirectory still downloads`() {
        val files = listOf(remote("onnx/model.onnx", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(weightsBody, File(dir, "onnx/model.onnx").readText())
    }

    @Test
    fun `an http failure on one file fails the repo`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isFailure)
    }
}
