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

    /**
     * A present-but-wrong file is not a hit. Corruption on disk must be re-fetched, not trusted.
     *
     * The repo is downloaded for real and *then* corrupted, rather than hand-built on disk. That
     * makes the marker under test the one Ferry itself wrote, so this proves the ownership marker
     * round-trips — Ferry accepts its own — instead of baking whatever format this implementation
     * happens to use into a fixture. (The hand-built directory this test used to set up is not a
     * corrupted Ferry repo at all; it is a foreign directory, which is now its own test below.)
     */
    @Test
    fun `a present repo failing verification is downloaded again`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))
        val first = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()
        File(first, "model.bin").writeText("CORRUPTED")

        server.enqueue(MockResponse().setBody(weightsBody))
        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(weightsBody, File(dir, "model.bin").readText())
    }

    /**
     * "owner" and "owner/model" are both perfectly legitimate repo ids, and both resolve to strict
     * children of `into` — so no containment check catches this one. But into/owner *contains* the
     * committed into/owner/model, and the commit step's deleteRecursively() took it along.
     */
    @Test
    fun `a repo id whose directory contains another repo does not destroy it`() {
        val inner = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        val committed =
            runBlocking { downloaderFor(inner).download("owner/model", temp.root) }.getOrThrow()

        val outer = listOf(remote("evil.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))
        val result = runBlocking { downloaderFor(outer).download("owner", temp.root) }

        assertEquals(
            "a repo nested under another repo's id must survive",
            configBody,
            File(committed, "config.json").readText(),
        )
        assertTrue(result.isFailure)
    }

    /**
     * A directory Ferry did not write — the user's own files, another tool's output — carries no
     * ownership marker, so the commit step must refuse it rather than delete it to make room.
     */
    @Test
    fun `a directory at the target path that ferry did not write is refused, not deleted`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))
        val theirs = File(temp.root, "a/b/notes.txt")
        theirs.parentFile?.mkdirs()
        theirs.writeText("the user's own file")

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertEquals(
            "a directory ferry did not create must not be deleted to make room",
            "the user's own file",
            theirs.readText(),
        )
        assertTrue(result.isFailure)
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

    /**
     * The Critical this guards against: "../X" resolves harmlessly against `into` (the ".staging/"
     * prefix and the ".." cancel out), landing exactly on the committed directory of an unrelated
     * repo literally named "X" — not escaping `into` at all, just escaping the staging area while
     * staying inside it. A single fresh-directory call can't observe this: `.staging` has to already
     * exist on disk (true after the first download the library ever makes) for the raw path to
     * resolve through it back to a sibling. Hence two calls: commit "X" for real, then attack it.
     */
    @Test
    fun `a repo id that resolves through the staging area to another repo does not destroy it`() {
        val filesX = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        val dirX = runBlocking { downloaderFor(filesX).download("X", temp.root) }.getOrThrow()

        val filesEvil = listOf(remote("evil.bin", weightsBody.length.toLong()))
        val result = runBlocking { downloaderFor(filesEvil).download("../X", temp.root) }

        assertTrue(result.isFailure)
        assertEquals(
            "an unrelated, already-committed repo must survive an attack routed through staging",
            configBody,
            File(dirX, "config.json").readText(),
        )
    }

    /** ".staging/evil" never leaves `into`, but a target there collides with the reserved staging namespace. */
    @Test
    fun `a repo id inside the reserved staging namespace fails`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))

        val result = runBlocking { downloaderFor(files).download(".staging/evil", temp.root) }

        assertTrue(result.isFailure)
        assertEquals("must not spend the user's data before validating the path", 0, server.requestCount)
    }

    /**
     * Downloads a real repo, then calls download again with [badRepoId], and asserts the already
     * committed repo is still readable afterwards.
     *
     * The Result is not the evidence here and never was: the destructive version of this bug
     * returned a perfectly ordinary Result.failure — because the staging directory it had just
     * deleted could no longer be renamed — after `into.deleteRecursively()` had already taken
     * every downloaded model with it. Only the filesystem can tell the two apart.
     */
    private fun assertCannotDestroyCommittedRepos(badRepoId: String) {
        val good = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        val committed =
            runBlocking { downloaderFor(good).download("owner/model", temp.root) }.getOrThrow()

        // Enqueued so an unguarded implementation completes its write rather than stalling on an
        // empty queue — otherwise this test could pass on a network timeout instead of the guard.
        val evil = listOf(remote("evil.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))

        val result = runBlocking { downloaderFor(evil).download(badRepoId, temp.root) }

        assertTrue("'$badRepoId' must not be accepted as a repo id", result.isFailure)
        assertEquals(
            "an already committed repo must survive a repo id of '$badRepoId'",
            configBody,
            File(committed, "config.json").readText(),
        )
    }

    /**
     * An empty repo id is not exotic — it is a search field submitted blank, or a null coalesced
     * to "". File(parent, "") is exactly parent, so a containment check that permits equality
     * makes `target` the download root itself, and the commit step then deletes every repo the
     * user has ever downloaded on its way to a failure that reads like a no-op.
     */
    @Test
    fun `an empty repo id fails without destroying the repos already downloaded`() {
        assertCannotDestroyCommittedRepos("")
    }

    /** File(parent, ".") canonicalizes to parent too, reaching the same place by a second route. */
    @Test
    fun `a repo id of a single dot fails without destroying the repos already downloaded`() {
        assertCannotDestroyCommittedRepos(".")
    }

    /** And so does any id whose ".." segments cancel out, which needs no leading "..". */
    @Test
    fun `a repo id that cancels back to the download root fails without destroying it`() {
        assertCannotDestroyCommittedRepos("owner/..")
    }

    @Test
    fun `an http failure on one file fails the repo`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isFailure)
    }
}
