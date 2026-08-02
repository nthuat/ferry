package dev.thuat.ferry

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

    /**
     * A device with room to finish must not be told it has no room to start. The more progress a
     * resumable download has made, the more this matters — and the more likely a naive check is to
     * refuse it.
     */
    @Test
    fun `a mostly staged download only needs the remaining bytes`() {
        val body = "0123456789"
        File(temp.root, ".staging/a/b/model.bin.part").apply {
            parentFile?.mkdirs()
            writeText(body.take(8))
        }
        File(temp.root, ".staging/a/b/model.bin.validator").writeText("\"v1\"")
        val files = listOf(remote("model.bin", body.length.toLong(), shaOf(body)))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(body.drop(8))
                .addHeader("Content-Range", "bytes 8-9/10"),
        )

        // Room for the two remaining bytes, nowhere near room for all ten.
        val downloader = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(OkHttpClient()),
            spaceCheck = SpaceCheck(probe = { 4 }, headroomBytes = 0L),
        )

        val result = runBlocking { downloader.download("a/b", temp.root) }

        assertTrue("8 of 10 bytes are already on disk", result.isSuccess)
    }

    /**
     * `ResumableDownloader` renames `.part` onto the final name as soon as the *server's* declared
     * length is satisfied — before anything is compared to the manifest — so a complete-looking file
     * can still be one the manifest rejects. Crediting it anyway would under-reserve for bytes that
     * are about to be re-downloaded in full, which is the unsafe direction of error: over-reserving
     * only refuses a download that would have fit, under-reserving starts one that fills the disk.
     */
    @Test
    fun `a complete-looking staged file that fails the manifest's declared size is not credited`() {
        val body = "0123456789"
        File(temp.root, ".staging/a/b/model.bin").apply {
            parentFile?.mkdirs()
            // Complete by the server's own (stale) reckoning, but short of what this manifest declares.
            writeText(body.take(6))
        }
        val files = listOf(remote("model.bin", body.length.toLong(), shaOf(body)))

        // Room for the last four bytes only — sufficient if, and only if, the stale six are wrongly
        // credited.
        val downloader = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(OkHttpClient()),
            spaceCheck = SpaceCheck(probe = { 4 }, headroomBytes = 0L),
        )

        val result = runBlocking { downloader.download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertEquals("an uncredited refusal must still spend no network request", 0, server.requestCount)
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

    /**
     * A file the hub published no sha256 for is documented as "verified by size alone", so the size
     * has to actually be checked somewhere. ResumableDownloader cannot do it: it compares what it
     * wrote against the server's own declared length, which a captive portal's login page satisfies
     * exactly. Without a check against the *manifest's* figure such a file is verified by nothing,
     * and the acceptance test for a fresh download is weaker than the one for a cache hit — a repo
     * that commits, then fails its own cache check on the next call, forever.
     */
    @Test
    fun `a file whose size does not match the manifest fails the repo and commits nothing`() {
        val loginPage = "<html>login</html>" // self-consistent, wrong length, no hash to catch it
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(loginPage))

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VerificationException)
        assertFalse(
            "a repo whose file is the wrong size must not be readable",
            File(temp.root, "a/b").exists(),
        )
    }

    /**
     * `files.all {}` on an empty list is true, so an empty manifest made the cache check vacuously
     * true for any directory that happened to be sitting at the target path — returning
     * Result.success pointing at something Ferry never wrote. With nothing there it fails the other
     * way: the loop runs zero times and the commit publishes a repo containing only its own marker,
     * which is then a permanent cache hit no later call can repair.
     */
    @Test
    fun `a manifest with no files is refused rather than treated as satisfied`() {
        val theirs = File(temp.root, "a/b/notes.txt")
        theirs.parentFile?.mkdirs()
        theirs.writeText("the user's own file")

        val result = runBlocking { downloaderFor(emptyList()).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertEquals(
            "an empty manifest must not adopt a directory Ferry did not write",
            "the user's own file",
            theirs.readText(),
        )
    }

    /**
     * The fix for docs/known-limitations.md's "a file declared with size 0 is verified by nothing":
     * the post-download check used to skip entirely when remote.sizeBytes was 0, while isSatisfiedBy
     * (no such guard) always compared onDisk.length() == remote.sizeBytes unconditionally — so the
     * two disagreed on a hub declaring an explicit zero. Both now apply the same unconditional
     * equality, so a genuinely empty declared-0 file is verified (trivially, by matching) on the
     * fresh-download path and stays a stable hit on the cache-check path, rather than the two ever
     * disagreeing about the same file.
     */
    @Test
    fun `a file declared size 0 that is genuinely empty is a stable cache hit`() {
        val files = listOf(remote("empty.bin", 0L))
        server.enqueue(MockResponse().setBody(""))

        val first = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()
        val requestsAfterFirst = server.requestCount

        val second = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(0L, File(first, "empty.bin").length())
        assertEquals(first, second)
        assertEquals(
            "a genuinely empty declared-0 file must be a stable cache hit, not re-downloaded",
            requestsAfterFirst,
            server.requestCount,
        )
    }

    /**
     * The consequence docs/known-limitations.md named for the asymmetry this fix closes: a hub
     * declaring size 0 while actually serving a non-empty body used to be accepted at download time
     * (the guard skipped the check) and then fail isSatisfiedBy forever after (which never had the
     * guard) — committed once, then silently re-downloaded and re-committed on every subsequent
     * call, never a hit and never failing loudly either. Dropping the download-time guard instead
     * fails the very first attempt, cleanly and repeatably, rather than committing something that
     * can never satisfy its own manifest.
     */
    @Test
    fun `a file declared size 0 with a non-empty body fails cleanly instead of looping forever`() {
        val files = listOf(remote("empty.bin", 0L))
        server.enqueue(MockResponse().setBody(weightsBody))

        val first = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull() is VerificationException)
        assertFalse(
            "a size-0 mismatch must not commit a directory that can never satisfy its own manifest",
            File(temp.root, "a/b").exists(),
        )

        server.enqueue(MockResponse().setBody(weightsBody))
        val second = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue("must fail the same way every time, not loop into some other state", second.isFailure)
        assertTrue(second.exceptionOrNull() is VerificationException)
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
     * The bug this reorder fixes: `RepoDownloader.download()` used to check free space before
     * checking whether the repo was already present and verified, so an already-downloaded, fully
     * verified repo became unreachable the moment its device filled up — refusing to confirm what it
     * already held, on a path that writes nothing and needs no space at all.
     */
    @Test
    fun `a cache hit succeeds even when free space is almost entirely gone`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))
        runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()
        val requestsAfterFirst = server.requestCount

        val seen = mutableListOf<RepoProgress>()
        val result = runBlocking {
            downloaderFor(files, freeBytes = 1L).download("a/b", temp.root) { seen += it }
        }

        assertTrue(result.isSuccess)
        assertEquals(
            "a cache hit must not transfer any bytes, no matter how little space is free",
            requestsAfterFirst,
            server.requestCount,
        )
        // Pins the one observable API change the reorder makes: a cache hit used to report
        // CheckingSpace then Complete; it now reports Complete alone, since the space check it
        // used to precede never runs at all on this path (see ProgressMapping.kt's own doc).
        assertEquals("a cache hit must fire exactly one progress event", 1, seen.size)
        assertTrue(
            "a cache hit must fire Complete alone; CheckingSpace never fires when the space check " +
                "itself never runs",
            seen.single() is RepoProgress.Complete,
        )
    }

    /** The guarantee this reorder must not weaken: a repo genuinely not present yet still needs the space. */
    @Test
    fun `a repo not already present still refuses when free space is almost entirely gone`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong()))

        val result = runBlocking { downloaderFor(files, freeBytes = 1L).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
    }

    /**
     * If the cache-hit check ever ran before the security guards — or the guards were skipped — an
     * escaping repo id pointing at a real, manifest-satisfying directory would be handed back as a
     * successful "hit", turning a hostile repo id into a way to read arbitrary directories on disk. A
     * directory that actually satisfies the manifest is planted at the escape target so this test
     * would fail for that reason specifically, not merely because nothing happened to be sitting there.
     */
    @Test
    fun `an escaping repo id is refused rather than treated as a cache hit`() {
        val downloadRoot = temp.newFolder("root")
        val escapeTarget = File(temp.root, "escape").apply { mkdirs() }
        File(escapeTarget, "model.bin").writeText(weightsBody)
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))

        val result = runBlocking { downloaderFor(files).download("../escape", downloadRoot) }

        assertTrue("an escaping repo id must be refused, not treated as a cache hit", result.isFailure)
        assertEquals("must not spend the user's data before validating the path", 0, server.requestCount)
    }

    /**
     * `File.usableSpace` — the default `FreeSpaceProbe` — returns 0 for a directory that does not
     * exist, and nothing creates `into` before the space check runs: a first-ever download into a
     * fresh directory (exactly what a clean install looks like) would otherwise refuse every model,
     * permanently, regardless of how much space is actually free. `temp.newFolder` is deliberately
     * not used for `into` itself — it creates the folder, which is exactly the condition this test
     * must not have.
     */
    @Test
    fun `a download into a directory that does not exist yet still succeeds when space is real`() {
        val into = File(temp.newFolder("parent"), "fresh-install")
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))
        // The real default SpaceCheck() — backed by the real File.usableSpace — not downloaderFor's
        // fake lambda probe, which ignores its `dir` argument and so cannot observe this bug either way.
        val fresh = RepoDownloader(repo = fakeRepo(files), downloader = ResumableDownloader(OkHttpClient()))

        val result = runBlocking { fresh.download("a/b", into) }

        assertTrue(result.isSuccess)
    }

    /**
     * Proves the fix answers the real question rather than just always succeeding for a directory
     * that does not exist yet — using the real default probe end to end, not a custom one: the walk
     * now lives in `DefaultFreeSpaceProbe` itself (see its doc in `SpaceCheck.kt`), and a custom
     * probe deliberately does not get it, so a custom probe can no longer be used here to distinguish
     * "refused because genuinely starved" from "refused because `into` merely does not exist yet" —
     * that distinction is what `SpaceCheckTest`'s own new case proves directly against the probe.
     * This test instead proves the guarantee survives end to end: a requirement no real disk could
     * ever satisfy still refuses, deterministically, on any machine.
     */
    @Test
    fun `a download into a directory that does not exist yet still refuses when the repo cannot possibly fit`() {
        val into = File(temp.newFolder("parent"), "fresh-install")
        val files = listOf(remote("model.bin", Long.MAX_VALUE / 2))
        val impossible = RepoDownloader(repo = fakeRepo(files), downloader = ResumableDownloader(OkHttpClient()))

        val result = runBlocking { impossible.download("a/b", into) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
    }

    /**
     * "owner" and "owner/model" are both perfectly legitimate repo ids, and both resolve to strict
     * children of `into` — so no containment check catches this one. But into/owner *contains* the
     * committed into/owner/model, and the commit step's deleteRecursively() would take it along.
     * What saves it here is the ownership marker: into/owner has none, so the commit is refused.
     *
     * The reverse order — commit "owner" first, then "owner/model", then re-download "owner" after
     * a cache miss — used to be a known, accepted gap: into/owner *does* carry a marker naming
     * "owner", and the commit deleted it recursively, taking the inner repo along. Closed by the
     * nested-marker check below the ownership-marker check; see
     * `a nested repo survives a re-download of the outer repo after a cache miss`.
     */
    @Test
    fun `an outer repo id is refused when the inner repo was committed first`() {
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
     * The destructive order docs/known-limitations.md named: commit "owner", commit "owner/model"
     * underneath it — nothing objects, a parent marker naming "owner" says nothing about what gets
     * nested inside it afterwards — then re-download "owner" after a cache miss. into/owner's
     * marker still reads "owner" and matches, so the unguarded commit step's deleteRecursively()
     * took into/owner/model with it and returned Result.success: a fully verified repo destroyed
     * with no failure signal. The nested-marker check must refuse this before the delete runs.
     */
    @Test
    fun `a nested repo survives a re-download of the outer repo after a cache miss`() {
        val outerFirst = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        runBlocking { downloaderFor(outerFirst).download("owner", temp.root) }.getOrThrow()

        val inner = listOf(remote("weights.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))
        val committedInner =
            runBlocking { downloaderFor(inner).download("owner/model", temp.root) }.getOrThrow()

        // A different manifest for the same "owner" id, so the cache check at the top of
        // download() misses and this call reaches the commit step instead of returning the
        // already-satisfied directory untouched.
        val otherBody = configBody + "-different"
        val outerSecond = listOf(remote("config.json", otherBody.length.toLong()))
        server.enqueue(MockResponse().setBody(otherBody))
        val result = runBlocking { downloaderFor(outerSecond).download("owner", temp.root) }

        assertTrue("a directory containing a nested repo must not be replaced", result.isFailure)
        assertEquals(
            "the nested repo must survive its outer repo being re-downloaded",
            weightsBody,
            File(committedInner, "weights.bin").readText(),
        )
    }

    /**
     * PROBE1 — the Critical code review found in an earlier version of this fix, verified against
     * an isolated copy of the repo, not argued: a design that moved the ownership marker entirely to
     * a shadow tree (`into/.ferry/<repoId>/.ferry`) made ownership a property of a *name*, not of the
     * directory. Nothing ever deletes a shadow entry, so removing `into/owner` out of band — the
     * only way to delete a model, and the remedy known-limitations.md names for every refusal — left
     * the shadow marker standing with nothing left to describe. Foreign content placed at the same
     * path afterwards inherited a stranger's ownership record: the next `download("owner", ...)`
     * read a marker that still said "owner", passed the nested check too, and deleted the user's own
     * directory on its way to `Result.success`.
     *
     * Revert-check: this must fail against a design where ownership lives only in the shadow tree,
     * and pass once ownership is restored to `target/.ferry` — a marker that lives and dies with the
     * same `renameTo`/`deleteRecursively` as the directory it describes has nothing left to claim
     * once that directory is gone.
     */
    @Test
    fun `a directory ferry once committed but that was removed out of band is refused after foreign content replaces it`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        val committed = runBlocking { downloaderFor(files).download("owner", temp.root) }.getOrThrow()

        // Out of band: not through Ferry, exactly the remedy known-limitations.md names for every
        // refusal ("remove the directory to retry"). A real user could do this with a file manager.
        committed.deleteRecursively()
        val theirs = File(committed, "notes.txt")
        theirs.parentFile?.mkdirs()
        theirs.writeText("the user's own file, unrelated to the repo Ferry once committed here")

        server.enqueue(MockResponse().setBody(configBody))
        val result = runBlocking { downloaderFor(files).download("owner", temp.root) }

        assertTrue(
            "a directory removed out of band and replaced with foreign content must not inherit " +
                "the old commit's ownership",
            result.isFailure,
        )
        assertEquals(
            "foreign content must survive a refused replace",
            "the user's own file, unrelated to the repo Ferry once committed here",
            theirs.readText(),
        )
    }

    /**
     * The HIGH the same review found: the nested-repo guard's own remediation text says "remove
     * that nested repo first", but removing it did not actually clear the refusal, because the
     * shadow entry recording the nesting was never deleted along with it — an orphan that blocked
     * the parent forever, verified identical before and after removal. Fixed by cross-checking each
     * shadow child against whether the real, corresponding directory still exists: the shadow tree
     * nominates a candidate, reality confirms or dismisses it.
     */
    @Test
    fun `removing a nested repo out of band clears the refusal on its parent`() {
        val outerFirst = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))
        runBlocking { downloaderFor(outerFirst).download("owner", temp.root) }.getOrThrow()

        val inner = listOf(remote("weights.bin", weightsBody.length.toLong()))
        server.enqueue(MockResponse().setBody(weightsBody))
        val committedInner =
            runBlocking { downloaderFor(inner).download("owner/model", temp.root) }.getOrThrow()

        // Exactly what the refused error message tells the caller to do.
        committedInner.deleteRecursively()

        // A different manifest for the same "owner" id, so the cache check at the top of
        // download() misses and this call reaches the commit step instead of returning the
        // already-satisfied directory untouched.
        val otherBody = configBody + "-different"
        val outerSecond = listOf(remote("config.json", otherBody.length.toLong()))
        server.enqueue(MockResponse().setBody(otherBody))
        val result = runBlocking { downloaderFor(outerSecond).download("owner", temp.root) }

        assertTrue(
            "removing the nested repo the error message named must actually clear the refusal",
            result.isSuccess,
        )
    }

    /**
     * The bug docs/known-limitations.md's now-closed entry describes: a manifest declaring a file
     * literally named ".ferry" inside a subdirectory used to brick the repo permanently, because the
     * nested-repo guard walked the *real* tree hunting any file named ".ferry" and had no way to tell
     * this one apart from a real nested repo's marker — distinguishing them by name alone is exactly
     * the kind of cleverness this codebase does not attempt.
     *
     * Closed by moving the *nested-repo* question to a shadow tree under `into/.ferry` that a hub's
     * manifest can never write into, named ".ferry" or anything else — see `MARKER_ROOT`'s doc. The
     * ownership marker itself stays at `target/.ferry`, unchanged; only the nested-check stopped
     * walking the real tree by name. This is the revert-check the fix's own task named: run this
     * test against the code before that move and it fails on `result.isSuccess` the same way the
     * test it replaces used to pin as a documented limit.
     */
    @Test
    fun `a manifest-declared file literally named ferry in a subdirectory no longer bricks the repo`() {
        val notAMarker = "not a marker, just a downloaded file"
        val first = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("sub/.ferry", notAMarker.length.toLong()),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(notAMarker))
        val committed = runBlocking { downloaderFor(first).download("owner", temp.root) }.getOrThrow()

        // A different manifest for the same id, so the cache check at the top of download() misses
        // and this call reaches the commit step instead of returning the already-satisfied
        // directory untouched.
        val otherBody = configBody + "-different"
        val second = listOf(
            remote("config.json", otherBody.length.toLong()),
            remote("sub/.ferry", notAMarker.length.toLong()),
        )
        server.enqueue(MockResponse().setBody(otherBody))
        server.enqueue(MockResponse().setBody(notAMarker))
        val result = runBlocking { downloaderFor(second).download("owner", temp.root) }

        assertTrue(
            "a file named .ferry in a subdirectory must not make the repo permanently unreplaceable",
            result.isSuccess,
        )
        assertEquals(otherBody, File(committed, "config.json").readText())
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

    /**
     * End-to-end version of `HuggingFaceTest`'s equivalent, wired through the real adapter rather
     * than `fakeRepo`. Proves the whole pipeline fails at `repo.manifest(repoId)`, the very first
     * line of `download()`, so the escaped URL is never fetched and no second request happens —
     * asserted on `server.requestCount`, not only on the `Result`.
     *
     * Revert-checked and found **not to isolate `HuggingFace`'s new namespace check specifically**:
     * with that check disabled, this test still passes, because `resolveInside(stagingDir,
     * remote.path)` below independently refuses the same `path` on the filesystem side first —
     * for this exact leading-`..` shape, both checks trip at the first `..` token, so either alone
     * is currently sufficient. The isolating proof that `HuggingFace.manifest()`'s own check does
     * its job independently of `RepoDownloader` is
     * `HuggingFaceTest`'s `a manifest file path that traverses out of the resolve namespace fails
     * the whole manifest call`, which calls `manifest()` directly with no `resolveInside` in the
     * picture at all. Kept here anyway as an end-to-end regression pin: the two checks guard
     * different layers (network request vs. filesystem write) that happen to agree today only
     * because of how deep `stagingDir` and the URL's own prefix each are — not because either was
     * designed to cover the other, so nothing keeps that agreement from drifting apart later.
     */
    @Test
    fun `a hub-supplied file path that traverses out of HuggingFace's resolve namespace is refused before any download request`() {
        val hf = HuggingFace(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
        val evilPath = "../../../../other/repo/resolve/main/secret.bin"
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "$evilPath", "size": 5 } ]"""),
        )
        val downloader = RepoDownloader(
            repo = hf,
            downloader = ResumableDownloader(OkHttpClient()),
            spaceCheck = SpaceCheck(probe = { Long.MAX_VALUE }, headroomBytes = 0L),
        )

        val result = runBlocking { downloader.download("owner/model", temp.root) }

        assertTrue(result.isFailure)
        assertEquals(
            "only the tree listing may be requested, never the escaped file",
            1,
            server.requestCount,
        )
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

    /**
     * ".ferry" is the literal name of the shadow directory `into/.ferry` reserves for every repo's
     * own marker (see `MARKER_FILE`'s doc). `resolveInside(into, ".ferry")` alone would not catch
     * this — the result is a perfectly ordinary strict child of `into` — so this is the marker
     * namespace's own analogue of the ".staging/evil" case below, guarded the same way: a repo id
     * whose target resolves onto the reserved root itself, or inside it.
     */
    @Test
    fun `a repo id of the marker directory's own name fails without destroying the repos already downloaded`() {
        assertCannotDestroyCommittedRepos(".ferry")
    }

    /** And so does anything inside it, the same way ".staging/evil" does for the staging namespace. */
    @Test
    fun `a repo id inside the marker namespace fails without destroying the repos already downloaded`() {
        assertCannotDestroyCommittedRepos(".ferry/evil")
    }

    @Test
    fun `an http failure on one file fails the repo`() {
        val files = listOf(remote("model.bin", 100L))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isFailure)
    }

    /**
     * The bytes already fetched are the whole point of resuming. Before this change the finally
     * block deleted them, so a second attempt re-downloaded a multi-gigabyte model from zero.
     */
    @Test
    fun `a failed download leaves its partial bytes in staging`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        // A body shorter than declared fails the size check after writing what it sent.
        server.enqueue(MockResponse().setBody(weightsBody.take(5)))

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        // Not "model.bin.part": ResumableDownloader compares what it wrote against this response's
        // own Content-Length (5, matching what MockWebServer actually sent), agrees they match, and
        // renames part onto the final in-staging name before returning success. It is the outer,
        // stricter check against the manifest's declared size (13) that then fails.
        val staged = File(temp.root, ".staging/a/b/model.bin")
        assertTrue("the partial file is the resume point and must survive", staged.isFile)
        assertEquals(5, staged.length())
    }

    /**
     * Durable staging (Task 1) means a `.part` can outlive the manifest that produced it: the hub
     * removed the file, or renamed it. Left alone it is never completed or committed, and a
     * long-lived repo accretes it forever.
     *
     * Asserted at the *committed* path, not the staging one: on success `stagingDir` is renamed
     * wholesale onto `target` (`RepoDownloader.download`'s own commit step), so checking the old
     * `.staging/...` path proves nothing — it is always empty afterwards purely because the whole
     * directory moved, whether or not the orphan was pruned. An unpruned orphan does not merely
     * survive in staging forever; it rides that rename straight into the committed, "verified"
     * repo, which is the failure this assertion actually has to catch.
     */
    @Test
    fun `a staged file the manifest no longer lists is discarded`() {
        File(temp.root, ".staging/a/b/gone.bin.part").apply {
            parentFile?.mkdirs()
            writeText("stale bytes from a manifest that no longer lists this file")
        }
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertFalse(
            "an orphan must not accrete forever, and must never ride the commit rename into the repo",
            File(dir, "gone.bin.part").exists(),
        )
    }

    /**
     * The counterpart to the orphan test above: pruning must not be so eager it deletes progress
     * still worth keeping. A validator is staged alongside the `.part` — mirroring
     * `ResumableDownloaderTest`'s own resume fixtures — because `ResumableDownloader` refuses to
     * resume without one (see its own KDoc). Without the validator this attempt would restart from
     * byte zero and could still land on the right final bytes, proving nothing about pruning at all;
     * the Range header assertion below is what makes this a proof of resume rather than a coincidence
     * of a passing restart.
     *
     * This may already pass before pruning is implemented — pruning only has to *not delete* this
     * file, and resuming itself is `ResumableDownloader`'s pre-existing behaviour, exercised through
     * `RepoDownloader` for the first time here. Kept as a regression pin either way: the thing worth
     * protecting is pruning never becoming so aggressive it takes a live `.part` with it.
     */
    @Test
    fun `a staged file the manifest still lists survives to be resumed`() {
        val partial = File(temp.root, ".staging/a/b/config.json.part").apply {
            parentFile?.mkdirs()
            writeText(configBody.take(4))
        }
        File(temp.root, ".staging/a/b/config.json.validator").writeText("\"v1\"")
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(configBody.drop(4))
                .addHeader("Content-Range", "bytes 4-${configBody.length - 1}/${configBody.length}"),
        )

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dir, "config.json").readText())
        assertFalse(partial.exists())
        val request = server.takeRequest()
        assertEquals(
            "must actually have asked for a range, not restarted from zero",
            "bytes=4-",
            request.getHeader("Range"),
        )
    }

    /**
     * The shape the plan's own correction names: `ResumableDownloader` renames `.part` onto the
     * final name as soon as the *server's* declared length is satisfied, before `RepoDownloader`
     * ever compares anything to the manifest — so an orphan can sit under its final name, not only
     * as `.part`. A naive `endsWith(".part")` filter would miss exactly this file.
     *
     * Asserted at the committed path for the same reason as the `.part` orphan test above: the
     * staging path is trivially empty after a successful commit regardless of pruning, because the
     * whole directory is renamed away. An unpruned bare-name orphan would otherwise land inside the
     * committed repo directory, indistinguishable from a real file to anything reading it back.
     */
    @Test
    fun `a staged file under its final name that the manifest no longer lists is discarded too`() {
        File(temp.root, ".staging/a/b/gone.bin").apply {
            parentFile?.mkdirs()
            writeText("a file the server considered complete, but the manifest no longer lists")
        }
        val files = listOf(remote("config.json", configBody.length.toLong()))
        server.enqueue(MockResponse().setBody(configBody))

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertFalse(
            "a completed-looking orphan must not accrete forever, or end up inside the committed repo",
            File(dir, "gone.bin").exists(),
        )
    }

    /**
     * Task 4b: the download loop called `download` for every file in the manifest unconditionally,
     * even one already sitting in staging, correct, under its final name — the gap Task 4's own
     * space credit exposed (crediting a staged file toward the space check while still re-fetching
     * it is incoherent). Only model.bin's response is queued; config.json must be satisfied from
     * staging alone. Asserted on server.requestCount, not only on success — the whole claim is that
     * bytes did not move for the file already staged.
     */
    @Test
    fun `a staged file that already satisfies the manifest is not re-fetched`() {
        File(temp.root, ".staging/a/b/config.json").apply {
            parentFile?.mkdirs()
            writeText(configBody)
        }
        val files = listOf(
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("config.json", configBody.length.toLong(), shaOf(configBody)),
        )
        server.enqueue(MockResponse().setBody(weightsBody))

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isSuccess)
        assertEquals("the staged file must not be re-fetched", 1, server.requestCount)
    }

    /**
     * The shape RepoProgress.Skipped exists for: a caller watching fileIndex advance must see why
     * config.json's index never appears in a Downloading event, rather than an unexplained gap.
     */
    @Test
    fun `a skipped file reports Skipped instead of Downloading`() {
        File(temp.root, ".staging/a/b/config.json").apply {
            parentFile?.mkdirs()
            writeText(configBody)
        }
        val files = listOf(
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("config.json", configBody.length.toLong(), shaOf(configBody)),
        )
        server.enqueue(MockResponse().setBody(weightsBody))

        val seen = mutableListOf<RepoProgress>()
        runBlocking { downloaderFor(files).download("a/b", temp.root) { seen += it } }

        val skipped = seen.filterIsInstance<RepoProgress.Skipped>().single()
        assertEquals("config.json", skipped.path)
        assertEquals(2, skipped.fileCount)
        assertTrue(
            "a skipped file must never also report Downloading — no bytes moved for it",
            seen.none { it is RepoProgress.Downloading && it.path == "config.json" },
        )
    }

    /**
     * Task 4b's own load-bearing guard: skipping must key off correctness, not mere presence. A
     * staged file whose bytes are wrong must still be fetched — skipping on existence alone would
     * commit a corrupt file, which is guarantee 2 (README's guarantee table: "never a corrupt
     * model"). Revert-checked: with the predicate weakened to a bare `destination.exists()`, this
     * test fails (0 requests, and the corrupt bytes ride straight into the committed repo) — see
     * task-4b-report.md for the observed failure.
     */
    @Test
    fun `a staged file whose bytes do not match the manifest is still fetched`() {
        File(temp.root, ".staging/a/b/model.bin").apply {
            parentFile?.mkdirs()
            writeText("wrong bytes, wrong length, staged under the right name")
        }
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))

        val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

        assertEquals("a corrupt staged file must be fetched, not skipped", 1, server.requestCount)
        assertEquals(weightsBody, File(dir, "model.bin").readText())
    }

    @Test
    fun `abandon removes only this repo's staging`() {
        val mine = File(temp.root, ".staging/a/b/model.bin.part").apply {
            parentFile?.mkdirs()
            writeText("mine")
        }
        val other = File(temp.root, ".staging/c/d/model.bin.part").apply {
            parentFile?.mkdirs()
            writeText("other")
        }

        val result = runBlocking { downloaderFor(emptyList()).abandon("a/b", temp.root) }

        assertTrue(result.isSuccess)
        assertFalse(mine.exists())
        assertTrue("another repo's staging is not this call's business", other.exists())
    }

    /**
     * The property the KDoc calls out by name: abandoning an in-progress download says nothing
     * about a previously committed copy of the same repo id, which may be complete, verified, and in
     * use by the host right now. `abandon` must never resolve against `into` itself, only against
     * `into/.staging`.
     */
    @Test
    fun `abandon does not touch an already committed repo`() {
        val committed = File(temp.root, "a/b/model.bin").apply {
            parentFile?.mkdirs()
            writeText("committed bytes")
        }
        File(temp.root, "a/b/.ferry").writeText("a/b")

        runBlocking { downloaderFor(emptyList()).abandon("a/b", temp.root) }

        assertTrue("abandoning a download says nothing about a completed one", committed.exists())
        assertEquals("committed bytes", committed.readText())
    }

    /**
     * repoId is caller-supplied, same as in [download]; `abandon` must refuse the same escapes
     * rather than trust its own, separate reasoning about what's safe.
     */
    @Test
    fun `abandon cannot escape into`() {
        val outside = File(temp.root, "outside.txt").apply { writeText("not yours") }

        val result = runBlocking { downloaderFor(emptyList()).abandon("../..", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(outside.exists())
    }

    /** The caller asked for a state — no staging for this repo id — that already holds. */
    @Test
    fun `abandoning a repo with no staging succeeds`() {
        assertTrue(runBlocking { downloaderFor(emptyList()).abandon("never/started", temp.root) }.isSuccess)
    }
}
