@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.thuat.ferry

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem

class RepoDownloaderTest {

    private val fs = FakeFileSystem()
    private val root = "/downloads".toPath()

    private lateinit var queue: QueueClient

    private val configBody = """{"model_type":"qwen2"}"""
    private val weightsBody = "WEIGHTS-BYTES"

    private var shaCounter = 0

    @BeforeTest
    fun setUp() {
        fs.createDirectories(root)
        queue = QueueClient()
    }

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    /** Runs [block] on a test dispatcher and returns what it produced. */
    private fun <T> await(block: suspend () -> T): T {
        var result: T? = null
        runTest { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** A repo whose files are served by [queue], with hashes computed rather than guessed. */
    private fun fakeRepo(files: List<RemoteFile>) = object : ModelHub {
        override suspend fun manifest(repoId: String) =
            Result.success(RepoManifest(repoId, files))
    }

    /** Builds a RemoteFile pointing at a synthetic URL — QueueClient answers FIFO regardless of it. */
    private fun remote(path: String, size: Long, sha256: String? = null) = RemoteFile(
        path = path,
        url = "https://example.test/resolve/$path",
        sizeBytes = size,
        sha256 = sha256,
    )

    private fun downloaderFor(
        files: List<RemoteFile>,
        freeBytes: Long = Long.MAX_VALUE,
    ) = RepoDownloader(
        repo = fakeRepo(files),
        downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
        spaceCheck = SpaceCheck(probe = { freeBytes }, headroomBytes = 0L),
        fileSystem = fs,
        dispatcher = UnconfinedTestDispatcher(),
    )

    private fun shaOf(content: String): String {
        val path = root / "sha-tmp-${shaCounter++}"
        fs.write(path) { writeUtf8(content) }
        return Sha256.of(fs, path)
    }

    private fun readText(path: Path): String = fs.read(path) { readUtf8() }

    private fun writeText(path: Path, text: String) {
        path.parent?.let { fs.createDirectories(it) }
        fs.write(path) { writeUtf8(text) }
    }

    private fun sizeOf(path: Path): Long = fs.metadataOrNull(path)?.size ?: 0L

    private fun isFile(path: Path): Boolean = fs.metadataOrNull(path)?.isRegularFile == true

    @Test
    fun `downloads every file and commits the directory`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
        )
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)

        val dir = await { downloaderFor(files).download("Qwen/Q-0.5B", root) }.getOrThrow()

        assertEquals(configBody, readText(dir / "config.json"))
        assertEquals(weightsBody, readText(dir / "model.bin"))
    }

    /** "a/b" flattened to "a--b" would collide with a repo literally named "a--b"; nesting can't. */
    @Test
    fun `repo ids that would collide when flattened resolve to separate directories`() {
        val filesA = listOf(remote("config.json", configBody.length.toLong()))
        val filesB = listOf(remote("model.bin", weightsBody.length.toLong()))
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)

        val dirA = await { downloaderFor(filesA).download("a/b", root) }.getOrThrow()
        val dirB = await { downloaderFor(filesB).download("a--b", root) }.getOrThrow()

        assertEquals(configBody, readText(dirA / "config.json"))
        assertEquals(weightsBody, readText(dirB / "model.bin"))
    }

    @Test
    fun `refuses to start when space is insufficient`() {
        val files = listOf(remote("model.bin", 10_000L))

        val result = await { downloaderFor(files, freeBytes = 5_000L).download("a/b", root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
    }

    @Test
    fun `refusing on space makes no network request`() {
        val files = listOf(remote("model.bin", 10_000L))

        await { downloaderFor(files, freeBytes = 5_000L).download("a/b", root) }

        assertEquals(0, queue.requests.size, "must not spend the user's data to discover this")
    }

    @Test
    fun `the space failure carries the numbers needed to explain it`() {
        val files = listOf(remote("model.bin", 10_000L))

        val result = await { downloaderFor(files, freeBytes = 4_000L).download("a/b", root) }
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
        writeText(root / ".staging/a/b.d/model.bin.part", body.take(8))
        writeText(root / ".staging/a/b.d/model.bin.validator", "\"v1\"")
        val files = listOf(remote("model.bin", body.length.toLong(), shaOf(body)))
        queue.enqueue(
            body = body.drop(8),
            status = HttpStatusCode.PartialContent,
            headers = headersOf("Content-Range", "bytes 8-9/10"),
        )

        // Room for the two remaining bytes, nowhere near room for all ten.
        val downloader = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            spaceCheck = SpaceCheck(probe = { 4 }, headroomBytes = 0L),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("a/b", root) }

        assertTrue(result.isSuccess, "8 of 10 bytes are already on disk")
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
        // Complete by the server's own (stale) reckoning, but short of what this manifest declares.
        writeText(root / ".staging/a/b.d/model.bin", body.take(6))
        val files = listOf(remote("model.bin", body.length.toLong(), shaOf(body)))

        // Room for the last four bytes only — sufficient if, and only if, the stale six are wrongly
        // credited.
        val downloader = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            spaceCheck = SpaceCheck(probe = { 4 }, headroomBytes = 0L),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("a/b", root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertEquals(0, queue.requests.size, "an uncredited refusal must still spend no network request")
    }

    @Test
    fun `a file failing verification fails the whole repo`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")))
        queue.enqueue(body = weightsBody)

        val result = await { downloaderFor(files).download("a/b", root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VerificationException)
    }

    @Test
    fun `nothing is committed when a file fails verification`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")),
        )
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)

        await { downloaderFor(files).download("a/b", root) }

        assertFalse(fs.exists(root / "a/b"), "a half-verified repo must not be readable")
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
        queue.enqueue(body = loginPage)

        val result = await { downloaderFor(files).download("a/b", root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VerificationException)
        assertFalse(fs.exists(root / "a/b"), "a repo whose file is the wrong size must not be readable")
    }

    /**
     * The race this guards against: `abandonStaging`, or a second concurrent `download` call, can
     * remove a file after this loop already verified it and moved on — the loop has no way to notice,
     * so without a final check the commit below would still find everything *it* looks at correct and
     * publish a repo silently missing whatever vanished. Deleting the first file from staging once the
     * *second* file's `Verifying` event fires reproduces exactly that shape without a second coroutine:
     * by then the loop has already checked and moved past the first file, the same way a real race
     * would leave it.
     */
    @Test
    fun `a file that vanishes from staging after being verified fails the download instead of committing`() {
        val files = listOf(
            remote("config.json", configBody.length.toLong()),
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
        )
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)

        val result = await {
            downloaderFor(files).download("a/b", root) { progress ->
                if (progress is RepoProgress.Verifying && progress.path == "model.bin") {
                    val firstFile = root / ".staging/a/b.d/config.json"
                    assertTrue(
                        fs.exists(firstFile),
                        "setup: the first file must still be staged when it is removed",
                    )
                    fs.delete(firstFile)
                }
            }
        }

        assertTrue(result.isFailure, "a file removed after the loop already verified it must fail, not commit")
        assertTrue(result.exceptionOrNull() is VerificationException)
        assertFalse(fs.exists(root / "a/b"), "nothing must be committed to the target directory")
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
        val theirs = root / "a/b/notes.txt"
        writeText(theirs, "the user's own file")

        val result = await { downloaderFor(emptyList()).download("a/b", root) }

        assertTrue(result.isFailure)
        assertEquals(
            "the user's own file",
            readText(theirs),
            "an empty manifest must not adopt a directory Ferry did not write",
        )
    }

    /**
     * `repo.manifest()` moved inside `download`'s own try specifically so this cannot happen: a
     * third-party ModelHub is free to throw instead of returning Result.failure, and before that move
     * the throw would have escaped this method's own Result<Path> contract entirely.
     */
    @Test
    fun `a hub whose manifest throws fails cleanly instead of escaping as an exception`() {
        val throwingHub = object : ModelHub {
            override suspend fun manifest(repoId: String): Result<RepoManifest> =
                throw IllegalStateException("hub blew up")
        }
        val downloader = RepoDownloader(
            repo = throwingHub,
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("a/b", root) }

        assertTrue(result.isFailure, "a throw from a third-party hub must become Result.failure, not escape")
        assertTrue(result.exceptionOrNull() is okio.IOException)
    }

    /** A hub is free to fail with any Throwable; download() must still only ever hand back an IOException. */
    @Test
    fun `a hub's non-IOException Result failure is wrapped rather than passed through`() {
        val boom = IllegalStateException("hub said no")
        val failingHub = object : ModelHub {
            override suspend fun manifest(repoId: String): Result<RepoManifest> = Result.failure(boom)
        }
        val downloader = RepoDownloader(
            repo = failingHub,
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("a/b", root) }

        val failure = result.exceptionOrNull()
        assertTrue(failure is okio.IOException, "a non-IOException failure must be wrapped, not passed through as-is")
        assertEquals(boom, failure?.cause, "the original cause must still be reachable")
    }

    /**
     * The fix for docs/known-limitations.md's "a file declared with size 0 is verified by nothing":
     * the post-download check used to skip entirely when remote.sizeBytes was 0, while isSatisfiedBy
     * (no such guard) always compared onDisk.length() == remote.sizeBytes unconditionally — so the
     * two disagreed. Both now apply the same unconditional equality, so a genuinely empty declared-0
     * file is verified (trivially, by matching) on the fresh-download path and stays a stable hit on
     * the cache-check path, rather than the two ever disagreeing about the same file.
     */
    @Test
    fun `a file declared size 0 that is genuinely empty is a stable cache hit`() {
        val files = listOf(remote("empty.bin", 0L))
        queue.enqueue(body = "")

        val first = await { downloaderFor(files).download("a/b", root) }.getOrThrow()
        val requestsAfterFirst = queue.requests.size

        val second = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(0L, sizeOf(first / "empty.bin"))
        assertEquals(first, second)
        assertEquals(
            requestsAfterFirst,
            queue.requests.size,
            "a genuinely empty declared-0 file must be a stable cache hit, not re-downloaded",
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
        queue.enqueue(body = weightsBody)

        val first = await { downloaderFor(files).download("a/b", root) }

        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull() is VerificationException)
        assertFalse(
            fs.exists(root / "a/b"),
            "a size-0 mismatch must not commit a directory that can never satisfy its own manifest",
        )

        queue.enqueue(body = weightsBody)
        val second = await { downloaderFor(files).download("a/b", root) }

        assertTrue(second.isFailure, "must fail the same way every time, not loop into some other state")
        assertTrue(second.exceptionOrNull() is VerificationException)
    }

    @Test
    fun `files without a published hash are accepted`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)

        assertTrue(await { downloaderFor(files).download("a/b", root) }.isSuccess)
    }

    @Test
    fun `progress reports space check - every file - verification and completion`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        queue.enqueue(body = weightsBody)

        val seen = mutableListOf<RepoProgress>()
        await { downloaderFor(files).download("a/b", root) { seen += it } }

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
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)

        val seen = mutableListOf<RepoProgress.Downloading>()
        await {
            downloaderFor(files).download("a/b", root) { if (it is RepoProgress.Downloading) seen += it }
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
        queue.enqueue(body = weightsBody)

        val first = await { downloaderFor(files).download("a/b", root) }.getOrThrow()
        val requestsAfterFirst = queue.requests.size

        val second = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(first, second)
        assertEquals(requestsAfterFirst, queue.requests.size, "second call must not transfer bytes")
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
        queue.enqueue(body = weightsBody)
        val first = await { downloaderFor(files).download("a/b", root) }.getOrThrow()
        writeText(first / "model.bin", "CORRUPTED")

        queue.enqueue(body = weightsBody)
        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model.bin"))
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
        queue.enqueue(body = weightsBody)
        await { downloaderFor(files).download("a/b", root) }.getOrThrow()
        val requestsAfterFirst = queue.requests.size

        val seen = mutableListOf<RepoProgress>()
        val result = await {
            downloaderFor(files, freeBytes = 1L).download("a/b", root) { seen += it }
        }

        assertTrue(result.isSuccess)
        assertEquals(
            requestsAfterFirst,
            queue.requests.size,
            "a cache hit must not transfer any bytes, no matter how little space is free",
        )
        // Pins the one observable API change the reorder makes: a cache hit used to report
        // CheckingSpace then Complete; it now reports Complete alone, since the space check it
        // used to precede never runs at all on this path.
        assertEquals(1, seen.size, "a cache hit must fire exactly one progress event")
        assertTrue(
            seen.single() is RepoProgress.Complete,
            "a cache hit must fire Complete alone; CheckingSpace never fires when the space check " +
                "itself never runs",
        )
    }

    /** The guarantee this reorder must not weaken: a repo genuinely not present yet still needs the space. */
    @Test
    fun `a repo not already present still refuses when free space is almost entirely gone`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong()))

        val result = await { downloaderFor(files, freeBytes = 1L).download("a/b", root) }

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
        val downloadRoot = root / "root"
        fs.createDirectories(downloadRoot)
        val escapeTarget = root / "escape"
        writeText(escapeTarget / "model.bin", weightsBody)
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))

        val result = await { downloaderFor(files).download("../escape", downloadRoot) }

        assertTrue(result.isFailure, "an escaping repo id must be refused, not treated as a cache hit")
        assertEquals(0, queue.requests.size, "must not spend the user's data before validating the path")
    }

    /**
     * The default `FreeSpaceProbe` returns 0 for a directory that does not exist, and nothing creates
     * `into` before the space check runs: a first-ever download into a fresh directory (exactly what
     * a clean install looks like) would otherwise refuse every model, permanently, regardless of how
     * much space is actually free. `into` is deliberately left uncreated here — that absence is
     * exactly the condition this test must not have masked.
     *
     * Under `FakeFileSystem` this does not exercise the nearest-*fake*-ancestor walk it would against
     * a real `File`: `DefaultFreeSpaceProbe` is hardcoded to `FileSystem.SYSTEM` (see its own doc in
     * `SpaceCheck.kt`), which cannot see any directory this test creates on `fs`. Its ancestor walk
     * therefore climbs straight past `into`, `parent` and `root` — none of which exist on the real
     * host filesystem — and lands on the real `/`, reading *that* volume's free space. This test
     * still proves something real end to end: `SpaceCheck()`'s default probe resolves to an actual,
     * positive figure for a path that does not exist rather than the phantom zero `File.usableSpace`
     * would report directly, so the download is not refused. It does not prove the walk stops at the
     * *nearest* ancestor specifically — `SpaceCheckTest` covers that choice directly, against real
     * `File.usableSpace` calls it can actually observe.
     */
    @Test
    fun `a download into a directory that does not exist yet still succeeds when space is real`() {
        val parent = root / "parent"
        fs.createDirectories(parent)
        val into = parent / "fresh-install"
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        queue.enqueue(body = weightsBody)
        // The real default SpaceCheck() — backed by the real host disk via FileSystem.SYSTEM, not
        // downloaderFor's fake lambda probe, which ignores its `dir` argument and so cannot observe
        // this bug either way.
        val fresh = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { fresh.download("a/b", into) }

        assertTrue(result.isSuccess)
    }

    /**
     * Proves the fix answers the real question rather than just always succeeding for a directory
     * that does not exist yet — using the real default probe end to end, not a custom one. This test
     * instead proves the guarantee survives end to end: a requirement no real disk could ever satisfy
     * still refuses, deterministically, on any machine.
     *
     * Same `FakeFileSystem` caveat as the sibling test above: `DefaultFreeSpaceProbe`'s ancestor walk
     * cannot see `into`/`parent`/`root` on `fs` and lands on the real host `/`, so this measures the
     * real root volume's free space, not the nearest `fs`-created ancestor. `Long.MAX_VALUE / 2` is
     * chosen so the assertion holds regardless of exactly which real ancestor answers — no volume on
     * any real machine is that large.
     */
    @Test
    fun `a download into a directory that does not exist yet still refuses when the repo cannot possibly fit`() {
        val parent = root / "parent"
        fs.createDirectories(parent)
        val into = parent / "fresh-install"
        val files = listOf(remote("model.bin", Long.MAX_VALUE / 2))
        val impossible = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { impossible.download("a/b", into) }

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
        queue.enqueue(body = configBody)
        val committed =
            await { downloaderFor(inner).download("owner/model", root) }.getOrThrow()

        val outer = listOf(remote("evil.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)
        val result = await { downloaderFor(outer).download("owner", root) }

        assertEquals(
            configBody,
            readText(committed / "config.json"),
            "a repo nested under another repo's id must survive",
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
        queue.enqueue(body = configBody)
        await { downloaderFor(outerFirst).download("owner", root) }.getOrThrow()

        val inner = listOf(remote("weights.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)
        val committedInner =
            await { downloaderFor(inner).download("owner/model", root) }.getOrThrow()

        // A different manifest for the same "owner" id, so the cache check at the top of
        // download() misses and this call reaches the commit step instead of returning the
        // already-satisfied directory untouched.
        val otherBody = configBody + "-different"
        val outerSecond = listOf(remote("config.json", otherBody.length.toLong()))
        queue.enqueue(body = otherBody)
        val result = await { downloaderFor(outerSecond).download("owner", root) }

        assertTrue(result.isFailure, "a directory containing a nested repo must not be replaced")
        assertEquals(
            weightsBody,
            readText(committedInner / "weights.bin"),
            "the nested repo must survive its outer repo being re-downloaded",
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
        queue.enqueue(body = configBody)
        val committed = await { downloaderFor(files).download("owner", root) }.getOrThrow()

        // Out of band: not through Ferry, exactly the remedy known-limitations.md names for every
        // refusal ("remove the directory to retry"). A real user could do this with a file manager.
        fs.deleteRecursively(committed)
        val theirs = committed / "notes.txt"
        writeText(theirs, "the user's own file, unrelated to the repo Ferry once committed here")

        queue.enqueue(body = configBody)
        val result = await { downloaderFor(files).download("owner", root) }

        assertTrue(
            result.isFailure,
            "a directory removed out of band and replaced with foreign content must not inherit " +
                "the old commit's ownership",
        )
        assertEquals(
            "the user's own file, unrelated to the repo Ferry once committed here",
            readText(theirs),
            "foreign content must survive a refused replace",
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
        queue.enqueue(body = configBody)
        await { downloaderFor(outerFirst).download("owner", root) }.getOrThrow()

        val inner = listOf(remote("weights.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)
        val committedInner =
            await { downloaderFor(inner).download("owner/model", root) }.getOrThrow()

        // Exactly what the refused error message tells the caller to do.
        fs.deleteRecursively(committedInner)

        // A different manifest for the same "owner" id, so the cache check at the top of
        // download() misses and this call reaches the commit step instead of returning the
        // already-satisfied directory untouched.
        val otherBody = configBody + "-different"
        val outerSecond = listOf(remote("config.json", otherBody.length.toLong()))
        queue.enqueue(body = otherBody)
        val result = await { downloaderFor(outerSecond).download("owner", root) }

        assertTrue(
            result.isSuccess,
            "removing the nested repo the error message named must actually clear the refusal",
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
        queue.enqueue(body = configBody)
        queue.enqueue(body = notAMarker)
        val committed = await { downloaderFor(first).download("owner", root) }.getOrThrow()

        // A different manifest for the same id, so the cache check at the top of download() misses
        // and this call reaches the commit step instead of returning the already-satisfied
        // directory untouched.
        val otherBody = configBody + "-different"
        val second = listOf(
            remote("config.json", otherBody.length.toLong()),
            remote("sub/.ferry", notAMarker.length.toLong()),
        )
        queue.enqueue(body = otherBody)
        queue.enqueue(body = notAMarker)
        val result = await { downloaderFor(second).download("owner", root) }

        assertTrue(
            result.isSuccess,
            "a file named .ferry in a subdirectory must not make the repo permanently unreplaceable",
        )
        assertEquals(otherBody, readText(committed / "config.json"))
    }

    /**
     * A directory Ferry did not write — the user's own files, another tool's output — carries no
     * ownership marker, so the commit step must refuse it rather than delete it to make room.
     */
    @Test
    fun `a directory at the target path that ferry did not write is refused - not deleted`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)
        val theirs = root / "a/b/notes.txt"
        writeText(theirs, "the user's own file")

        val result = await { downloaderFor(files).download("a/b", root) }

        assertEquals(
            "the user's own file",
            readText(theirs),
            "a directory ferry did not create must not be deleted to make room",
        )
        assertTrue(result.isFailure)
    }

    /**
     * Same shape as a bug fixed in Task 1 (an invalid baseUrl thrown instead of returned): the
     * cache-hit check re-hashes an existing file, which is I/O and can fail for reasons unrelated
     * to whether the file is correct. That failure must become Result.failure, not escape download().
     *
     * FakeFileSystem has no notion of Unix read permissions, so the fault is injected instead: a
     * `ForwardingFileSystem` that throws from `source()` for this one path, standing in for
     * `File.setReadable(false)`.
     */
    @Test
    fun `a cache-hit check that cannot read a file fails instead of throwing`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        val existing = root / "a/b/model.bin"
        writeText(existing, weightsBody)

        val unreadableFs = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source =
                if (file == existing) throw okio.IOException("permission denied: $file") else super.source(file)
        }
        val downloader = RepoDownloader(
            repo = fakeRepo(files),
            downloader = ResumableDownloader(queue.client, unreadableFs, UnconfinedTestDispatcher()),
            spaceCheck = SpaceCheck(probe = { Long.MAX_VALUE }, headroomBytes = 0L),
            fileSystem = unreadableFs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("a/b", root) }

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
        queue.enqueue(body = configBody)
        val escaped = root.parent!! / "escape"

        val result = await { downloaderFor(files).download("../escape", root) }

        assertTrue(result.isFailure)
        assertFalse(fs.exists(escaped), "must not create anything outside the target directory")
        assertEquals(0, queue.requests.size, "must not spend the user's data before validating the path")
    }

    /**
     * remote.path comes from the hub's manifest over the network. A hostile or compromised listing
     * must not turn a download into an arbitrary file write outside the staging directory.
     */
    @Test
    fun `a manifest file path that tries to escape the staging directory fails and writes nothing outside it`() {
        val files = listOf(remote("../../escaped.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)
        val escaped = root / "escaped.bin"

        val result = await { downloaderFor(files).download("repo", root) }

        assertTrue(result.isFailure)
        assertFalse(fs.exists(escaped), "must not write outside the staging directory")
    }

    /**
     * End-to-end version of `HuggingFaceTest`'s equivalent, wired through the real adapter rather
     * than `fakeRepo`. Proves the whole pipeline fails at `repo.manifest(repoId)`, the very first
     * line of `download()`, so the escaped URL is never fetched and no second request happens —
     * asserted on the request count, not only on the `Result`.
     *
     * `HuggingFace` now shares [queue] with `downloader` rather than a separate real server — both
     * are backed by the same single-engine [QueueClient] (Task 5), so one request count covers both
     * the tree listing `HuggingFace.manifest()` issues and any file request `downloader` would have
     * issued had the escape not been caught first.
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
        val hf = HuggingFace(queue.client, baseUrl = "http://hub.test")
        val evilPath = "../../../../other/repo/resolve/main/secret.bin"
        queue.enqueue("""[ { "type": "file", "path": "$evilPath", "size": 5 } ]""")
        val downloader = RepoDownloader(
            repo = hf,
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            spaceCheck = SpaceCheck(probe = { Long.MAX_VALUE }, headroomBytes = 0L),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )

        val result = await { downloader.download("owner/model", root) }

        assertTrue(result.isFailure)
        assertEquals(1, queue.requests.size, "only the tree listing may be requested, never the escaped file")
    }

    /** The escape check must reject only real escapes, not ordinary subdirectories within a repo. */
    @Test
    fun `a file path containing a legitimate subdirectory still downloads`() {
        val files = listOf(remote("onnx/model.onnx", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "onnx/model.onnx"))
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
        queue.enqueue(body = configBody)
        val dirX = await { downloaderFor(filesX).download("X", root) }.getOrThrow()

        val filesEvil = listOf(remote("evil.bin", weightsBody.length.toLong()))
        val result = await { downloaderFor(filesEvil).download("../X", root) }

        assertTrue(result.isFailure)
        assertEquals(
            configBody,
            readText(dirX / "config.json"),
            "an unrelated, already-committed repo must survive an attack routed through staging",
        )
    }

    /** ".staging/evil" never leaves `into`, but a target there collides with the reserved staging namespace. */
    @Test
    fun `a repo id inside the reserved staging namespace fails`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)

        val result = await { downloaderFor(files).download(".staging/evil", root) }

        assertTrue(result.isFailure)
        assertEquals(0, queue.requests.size, "must not spend the user's data before validating the path")
    }

    /**
     * `parent / "/abs"` in okio drops `parent` entirely and returns the absolute path alone. A repo
     * id that is itself an absolute path must still be refused, not resolved as though it had been
     * safely joined to `into`. `download()` happens to have defense in depth here — `stagingDir`,
     * `target` and `markerDir` are each checked against a *different* reserved root (`into/.staging`,
     * `into`, `into/.ferry`), and no single absolute string can lexically fall inside all three at
     * once — so this pin holds regardless of which check catches it; see the `abandonStaging` sibling
     * below for the test that isolates `resolveInside`'s own `relative.startsWith("/")` guard
     * specifically, on the one call site that checks against a single root.
     */
    @Test
    fun `a repo id that is an absolute path is refused rather than resolved against root`() {
        val files = listOf(remote("config.json", configBody.length.toLong()))
        val absoluteRepoId = "$root/evil"

        val result = await { downloaderFor(files).download(absoluteRepoId, root) }

        assertTrue(result.isFailure)
        assertEquals(0, queue.requests.size, "must not spend the user's data before validating the path")
    }

    /**
     * `abandonStaging` resolves its repo id against exactly one root (`into/.staging`), so unlike
     * `download()` there is no second, differently-rooted check to fall back on — this is the test
     * that actually isolates `resolveInside`'s `relative.startsWith("/")` guard.
     *
     * The absolute string "$root/.staging/evil" is deliberately chosen to alias the *same* staging
     * path the ordinary repo id "evil" resolves to (`into/.staging/evil.d`): without the guard,
     * `parent / relative` drops `parent` and returns the absolute path unchanged, which still lies
     * lexically inside `into/.staging` and so passes the "strictly inside" check — letting an
     * absolute-looking repo id delete a *different*, legitimate repo's own in-flight staging.
     */
    @Test
    fun `abandonStaging refuses a repo id that is an absolute path rather than aliasing another repo's staging`() {
        val legitimateStaging = root / ".staging/evil.d/model.bin.part"
        writeText(legitimateStaging, "legitimate in-flight bytes staged under the ordinary repo id 'evil'")
        val absoluteRepoId = "$root/.staging/evil"

        val result = await { downloaderFor(emptyList()).abandonStaging(absoluteRepoId, root) }

        assertTrue(result.isFailure)
        assertTrue(
            fs.exists(legitimateStaging),
            "an absolute-path repo id must not alias, and delete, another repo's own staging directory",
        )
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
        queue.enqueue(body = configBody)
        val committed =
            await { downloaderFor(good).download("owner/model", root) }.getOrThrow()

        // Enqueued so an unguarded implementation completes its write rather than stalling on an
        // empty queue — otherwise this test could pass on a network timeout instead of the guard.
        val evil = listOf(remote("evil.bin", weightsBody.length.toLong()))
        queue.enqueue(body = weightsBody)

        val result = await { downloaderFor(evil).download(badRepoId, root) }

        assertTrue(result.isFailure, "'$badRepoId' must not be accepted as a repo id")
        assertEquals(
            configBody,
            readText(committed / "config.json"),
            "an already committed repo must survive a repo id of '$badRepoId'",
        )
    }

    /**
     * An empty repo id is not exotic — it is a search field submitted blank, or a null coalesced
     * to "". `parent / ""` is exactly parent, so a containment check that permits equality makes
     * `target` the download root itself, and the commit step then deletes every repo the user has
     * ever downloaded on its way to a failure that reads like a no-op.
     */
    @Test
    fun `an empty repo id fails without destroying the repos already downloaded`() {
        assertCannotDestroyCommittedRepos("")
    }

    /** `parent / "."` normalizes to parent too, reaching the same place by a second route. */
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
        queue.enqueue(status = HttpStatusCode.InternalServerError)

        assertTrue(await { downloaderFor(files).download("a/b", root) }.isFailure)
    }

    /**
     * The bytes already fetched are the whole point of resuming. Before this change the finally
     * block deleted them, so a second attempt re-downloaded a multi-gigabyte model from zero.
     */
    @Test
    fun `a failed download leaves its partial bytes in staging`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        // A body shorter than declared fails the size check after writing what it sent.
        queue.enqueue(body = weightsBody.take(5))

        val result = await { downloaderFor(files).download("a/b", root) }

        assertTrue(result.isFailure)
        // Not "model.bin.part": ResumableDownloader compares what it wrote against this response's
        // own Content-Length (5, matching what was actually sent), agrees they match, and renames
        // part onto the final in-staging name before returning success. It is the outer, stricter
        // check against the manifest's declared size (13) that then fails.
        val staged = root / ".staging/a/b.d/model.bin"
        assertTrue(isFile(staged), "the partial file is the resume point and must survive")
        assertEquals(5L, sizeOf(staged))
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
        writeText(
            root / ".staging/a/b.d/gone.bin.part",
            "stale bytes from a manifest that no longer lists this file",
        )
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertFalse(
            fs.exists(dir / "gone.bin.part"),
            "an orphan must not accrete forever, and must never ride the commit rename into the repo",
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
        val partial = root / ".staging/a/b.d/config.json.part"
        writeText(partial, configBody.take(4))
        writeText(root / ".staging/a/b.d/config.json.validator", "\"v1\"")
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(
            body = configBody.drop(4),
            status = HttpStatusCode.PartialContent,
            headers = headersOf("Content-Range", "bytes 4-${configBody.length - 1}/${configBody.length}"),
        )

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(configBody, readText(dir / "config.json"))
        assertFalse(fs.exists(partial))
        val request = queue.requests[0]
        assertEquals(
            "bytes=4-",
            request.headers[HttpHeaders.Range],
            "must actually have asked for a range, not restarted from zero",
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
        writeText(
            root / ".staging/a/b.d/gone.bin",
            "a file the server considered complete, but the manifest no longer lists",
        )
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertFalse(
            fs.exists(dir / "gone.bin"),
            "a completed-looking orphan must not accrete forever, or end up inside the committed repo",
        )
    }

    /**
     * Branch review: `pruneOrphans` deleted an orphan file but never the directory pruning it left
     * empty, so an orphaned subdirectory survived the file pass and rode the commit rename straight
     * into the committed repo — indistinguishable from real content once there. The orphan here sits
     * inside its own subdirectory rather than directly in staging, so pruning its file empties
     * "gone/" and this asserts that directory does not survive into the commit either.
     */
    @Test
    fun `pruneOrphans removes a directory it empties - not only the files inside it`() {
        writeText(
            root / ".staging/a/b.d/gone/gone.bin.part",
            "stale bytes under a subdirectory the manifest no longer names",
        )
        val files = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertFalse(
            fs.exists(dir / "gone"),
            "a directory emptied by pruning must not ride the commit rename into the repo",
        )
    }

    /**
     * Task 4b: the download loop called `download` for every file in the manifest unconditionally,
     * even one already sitting in staging, correct, under its final name — the gap Task 4's own
     * space credit exposed (crediting a staged file toward the space check while still re-fetching
     * it is incoherent). Only model.bin's response is queued; config.json must be satisfied from
     * staging alone. Asserted on the request count, not only on success — the whole claim is that
     * bytes did not move for the file already staged.
     */
    @Test
    fun `a staged file that already satisfies the manifest is not re-fetched`() {
        writeText(root / ".staging/a/b.d/config.json", configBody)
        val files = listOf(
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("config.json", configBody.length.toLong(), shaOf(configBody)),
        )
        queue.enqueue(body = weightsBody)

        val result = await { downloaderFor(files).download("a/b", root) }

        assertTrue(result.isSuccess)
        assertEquals(1, queue.requests.size, "the staged file must not be re-fetched")
    }

    /**
     * The shape RepoProgress.Skipped exists for: a caller watching fileIndex advance must see why
     * config.json's index never appears in a Downloading event, rather than an unexplained gap.
     */
    @Test
    fun `a skipped file reports Skipped instead of Downloading`() {
        writeText(root / ".staging/a/b.d/config.json", configBody)
        val files = listOf(
            remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("config.json", configBody.length.toLong(), shaOf(configBody)),
        )
        queue.enqueue(body = weightsBody)

        val seen = mutableListOf<RepoProgress>()
        await { downloaderFor(files).download("a/b", root) { seen += it } }

        val skipped = seen.filterIsInstance<RepoProgress.Skipped>().single()
        assertEquals("config.json", skipped.path)
        assertEquals(2, skipped.fileCount)
        assertTrue(
            seen.none { it is RepoProgress.Downloading && it.path == "config.json" },
            "a skipped file must never also report Downloading — no bytes moved for it",
        )
    }

    /**
     * Task 4b's own load-bearing guard: skipping must key off correctness, not mere presence. A
     * staged file whose bytes are wrong must still be fetched — skipping on existence alone would
     * commit a corrupt file, which is guarantee 2 (README's guarantee table: "never a corrupt
     * model"). Revert-checked: with the predicate weakened to bare existence, this test fails (0
     * requests, and the corrupt bytes ride straight into the committed repo).
     */
    @Test
    fun `a staged file whose bytes do not match the manifest is still fetched`() {
        writeText(root / ".staging/a/b.d/model.bin", "wrong bytes, wrong length, staged under the right name")
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        queue.enqueue(body = weightsBody)

        val dir = await { downloaderFor(files).download("a/b", root) }.getOrThrow()

        assertEquals(1, queue.requests.size, "a corrupt staged file must be fetched, not skipped")
        assertEquals(weightsBody, readText(dir / "model.bin"))
    }

    @Test
    fun `abandonStaging removes only this repo's staging`() {
        val mine = root / ".staging/a/b.d/model.bin.part"
        writeText(mine, "mine")
        val other = root / ".staging/c/d.d/model.bin.part"
        writeText(other, "other")

        val result = await { downloaderFor(emptyList()).abandonStaging("a/b", root) }

        assertTrue(result.isSuccess)
        assertFalse(fs.exists(mine))
        assertTrue(fs.exists(other), "another repo's staging is not this call's business")
    }

    /**
     * The property the KDoc calls out by name: abandoning an in-progress download says nothing
     * about a previously committed copy of the same repo id, which may be complete, verified, and in
     * use by the host right now. `abandonStaging` must never resolve against `into` itself, only
     * against `into/.staging`.
     *
     * Branch review: with only a committed copy and no staging, this used to pass for the wrong
     * reason — `abandonStaging` found staging absent and returned early having done nothing at all,
     * which would pass identically for a completely broken `abandonStaging` that never deletes
     * anything. Now stages this same id too, so the assertion on `staged` only passes if
     * `abandonStaging` actually deletes staging, and the assertion on `committed` only means
     * something once it does.
     */
    @Test
    fun `abandonStaging does not touch an already committed repo`() {
        val committed = root / "a/b/model.bin"
        writeText(committed, "committed bytes")
        writeText(root / "a/b/.ferry", "a/b")
        val staged = root / ".staging/a/b.d/model.bin.part"
        writeText(staged, "in-flight bytes, unrelated to the committed copy above")

        val result = await { downloaderFor(emptyList()).abandonStaging("a/b", root) }

        assertTrue(result.isSuccess)
        assertFalse(fs.exists(staged), "abandonStaging must actually delete this repo id's own staging")
        assertTrue(fs.exists(committed), "abandoning a download says nothing about a completed one")
        assertEquals("committed bytes", readText(committed))
    }

    /**
     * repoId is caller-supplied, same as in [RepoDownloader.download]; `abandonStaging` must refuse
     * the same escapes rather than trust its own, separate reasoning about what's safe.
     */
    @Test
    fun `abandonStaging cannot escape into`() {
        val outside = root / "outside.txt"
        writeText(outside, "not yours")

        val result = await { downloaderFor(emptyList()).abandonStaging("../..", root) }

        assertTrue(result.isFailure)
        assertTrue(fs.exists(outside))
    }

    /** The caller asked for a state — no staging for this repo id — that already holds. */
    @Test
    fun `abandoning staging for a repo with no staging succeeds`() {
        assertTrue(
            await { downloaderFor(emptyList()).abandonStaging("never/started", root) }.isSuccess,
        )
    }

    @Test
    fun `stagedBytes is zero when nothing has ever been staged`() {
        assertEquals(0L, await { downloaderFor(emptyList()).stagedBytes("a/b", root) })
    }

    /** A `.part` with a validator is exactly what `ResumableDownloader` resumes from — see its own KDoc. */
    @Test
    fun `stagedBytes credits a part file that has a validator`() {
        writeText(root / ".staging/a/b.d/model.bin.part", "12345678") // 8 bytes
        writeText(root / ".staging/a/b.d/model.bin.validator", "\"v1\"")

        assertEquals(8L, await { downloaderFor(emptyList()).stagedBytes("a/b", root) })
    }

    /**
     * No validator, no resume: `ResumableDownloader` refuses to resume blind and restarts this file
     * from byte zero (its own KDoc). Counting these bytes would overstate what the next attempt
     * actually reuses — exactly the dishonesty `stagedBytes`' own KDoc says it must not commit.
     */
    @Test
    fun `stagedBytes does not credit a part file with no validator`() {
        writeText(root / ".staging/a/b.d/model.bin.part", "12345678")

        assertEquals(0L, await { downloaderFor(emptyList()).stagedBytes("a/b", root) })
    }

    /**
     * The shape `ResumableDownloader` leaves once the *server's* declared length is satisfied, before
     * anything is compared to the manifest — the strongest kind of progress, but not re-verified here
     * (see `stagedBytes`' own doc on why it disagrees with `RepoDownloader`'s own credit check).
     */
    @Test
    fun `stagedBytes counts a bare staged file under its final name`() {
        writeText(root / ".staging/a/b.d/config.json", configBody)

        assertEquals(
            configBody.length.toLong(),
            await { downloaderFor(emptyList()).stagedBytes("a/b", root) },
        )
    }

    @Test
    fun `stagedBytes sums every staged file's reusable bytes together - touching no network`() {
        writeText(root / ".staging/a/b.d/config.json", configBody) // bare, complete-looking: counted in full
        writeText(root / ".staging/a/b.d/model.bin.part", "12345678") // 8 resumable bytes, credited
        writeText(root / ".staging/a/b.d/model.bin.validator", "\"v1\"")
        writeText(root / ".staging/a/b.d/other.bin.part", "not credited, no validator")

        val expected = configBody.length.toLong() + 8L
        assertEquals(expected, await { downloaderFor(emptyList()).stagedBytes("a/b", root) })
        assertEquals(0, queue.requests.size, "must not touch the network")
    }

    /**
     * The marker `download()` writes into staging just before the commit rename (`MARKER_FILE`) is
     * Ferry's own bookkeeping, not a manifest file's bytes, and must not inflate the count.
     */
    @Test
    fun `stagedBytes ignores the ownership marker written just before commit`() {
        writeText(root / ".staging/a/b.d/config.json", configBody)
        writeText(root / ".staging/a/b.d/.ferry", "a/b")

        assertEquals(
            configBody.length.toLong(),
            await { downloaderFor(emptyList()).stagedBytes("a/b", root) },
        )
    }

    /** Total, not `Result`-returning: an escaping repo id is zero bytes of progress, not a thrown exception. */
    @Test
    fun `stagedBytes is zero rather than throwing for an escaping repo id`() {
        assertEquals(0L, await { downloaderFor(emptyList()).stagedBytes("../..", root) })
    }

    /**
     * Ties `stagedBytes` back to the scenario that opened this whole plan: the bytes a failed
     * download leaves behind (Task 1's own test) are exactly what this method exists to surface.
     */
    @Test
    fun `stagedBytes reflects the bytes a failed download actually left behind`() {
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        // A body shorter than declared fails the size check after writing what it sent.
        queue.enqueue(body = weightsBody.take(5))
        val downloader = downloaderFor(files)

        val result = await { downloader.download("a/b", root) }

        assertTrue(result.isFailure)
        assertEquals(5L, await { downloader.stagedBytes("a/b", root) })
    }

    /**
     * CRITICAL, whole-branch review — the design argument that staging sits outside the target
     * directory ("target vs staging" — deleting staging cannot corrupt a committed repo) is true but
     * too narrow: "owner" and "owner/model" are both ordinary repo ids (`MARKER_FILE`'s own doc), and
     * staging mirrored that nesting with no shadow tree to guard it the way target has (`MARKER_ROOT`)
     * — `into/.staging/owner` was a literal ancestor directory of `into/.staging/owner/model`. This is
     * "staging vs staging", not "target vs staging", and the old argument says nothing about it.
     *
     * A failed `download("owner/model")` left its resumable bytes sitting inside "owner"'s own
     * staging subtree. Downloading "owner" next walked straight into them via `pruneOrphans`, which
     * has no way to know "owner/model" is a different repo's live scratch: it deleted the file as an
     * orphan of a manifest that was never its, and the emptied `model/` directory rode the commit
     * rename into the committed `into/owner` on the very same rename — foreign content inside a repo
     * that then reports a cache hit forever, and "owner/model" permanently refused afterwards
     * (`into/owner/model` now exists with no marker of its own).
     *
     * Every assertion below is against public API only (`stagedBytes`, the committed directory
     * `download` itself returns, `Result.isSuccess`) rather than an internal staging path, so this
     * proves the actual observable bug, not merely a hardcoded path shape.
     *
     * Revert-check: with `stagingDirFor` reverted to a bare `resolveInside(stagingRoot, repoId)`
     * (staging/RepoDownloader.kt's pre-fix shape), this fails on all three post-owner-download
     * assertions — see review-fix-report.md for the observed output.
     */
    @Test
    fun `a prefix repo's own download does not reach into a nested repo's staging`() {
        val modelDownloader = downloaderFor(
            listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody))),
        )
        // A body shorter than declared fails the size check after writing what it sent — same shape
        // as "a failed download leaves its partial bytes in staging" above.
        queue.enqueue(body = weightsBody.take(5))
        val modelResult = await { modelDownloader.download("owner/model", root) }
        assertTrue(modelResult.isFailure)
        assertEquals(5L, await { modelDownloader.stagedBytes("owner/model", root) })

        val ownerFiles = listOf(remote("config.json", configBody.length.toLong()))
        queue.enqueue(body = configBody)
        val ownerDir = await { downloaderFor(ownerFiles).download("owner", root) }.getOrThrow()

        assertEquals(
            5L,
            await { modelDownloader.stagedBytes("owner/model", root) },
            "an unrelated prefix repo's own download must not prune owner/model's progress",
        )
        assertFalse(
            fs.exists(ownerDir / "model"),
            "owner/model must never appear inside the committed owner directory",
        )

        queue.enqueue(body = weightsBody)
        val retryResult = await { modelDownloader.download("owner/model", root) }
        assertTrue(
            retryResult.isSuccess,
            "owner/model must still be downloadable, not permanently refused by a leftover directory",
        )
    }

    /**
     * `abandonStaging` reached a nested id's staging the same way `pruneOrphans` did — via
     * `deleteRecursively()` on a `stagingDir` that used to be a literal ancestor directory of a
     * nested id's own. See `stagingDirFor`'s own doc.
     */
    @Test
    fun `abandoning staging for a prefix repo does not touch a nested repo's own staging`() {
        val modelDownloader = downloaderFor(
            listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody))),
        )
        queue.enqueue(body = weightsBody.take(5))
        await { modelDownloader.download("owner/model", root) }

        val result = await { downloaderFor(emptyList()).abandonStaging("owner", root) }

        assertTrue(result.isSuccess)
        assertEquals(
            5L,
            await { modelDownloader.stagedBytes("owner/model", root) },
            "abandoning a prefix repo id must not delete a nested repo's own staging",
        )
    }

    /**
     * `stagedBytes` summed a nested id's staging into the prefix id's own count the same way — the
     * recursive walk walked straight into it. See `stagingDirFor`'s own doc.
     */
    @Test
    fun `stagedBytes for a prefix repo does not count a nested repo's own staging`() {
        val ownerDownloader = downloaderFor(listOf(remote("config.json", configBody.length.toLong())))
        queue.enqueue(body = configBody.take(3))
        await { ownerDownloader.download("owner", root) }

        val modelDownloader = downloaderFor(
            listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody))),
        )
        queue.enqueue(body = weightsBody.take(5))
        await { modelDownloader.download("owner/model", root) }

        assertEquals(
            3L,
            await { ownerDownloader.stagedBytes("owner", root) },
            "must not sum a nested id's own staged bytes into the prefix id's count",
        )
    }

    // ---- file filter: selection (spec tests 1, 2, 3, 13) ----

    @Test
    fun `a filter downloads and commits only the matching subset`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong()),
        )
        queue.enqueue(body = weightsBody)

        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertFalse(fs.exists(dir / "model-Q8_0.gguf"))
        assertEquals(1, queue.requests.size)
    }

    @Test
    fun `a filter matching nothing fails - commits nothing - makes no file request`() {
        val files = listOf(remote("model-Q8_0.gguf", 10L))

        val result = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("no files matched the filter"))
        assertFalse(fs.exists(root / "o/m"))
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun `space preflight sizes only the filtered subset`() {
        // Whole repo (1_000_000 + weights) cannot fit in freeBytes; the selected file alone can.
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("huge-Q8_0.gguf", 1_000_000L),
        )
        queue.enqueue(body = weightsBody)

        val dir = await {
            downloaderFor(files, freeBytes = weightsBody.length.toLong())
                .download("o/m", root, Regex("Q4_K_M"))
        }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
    }

    @Test
    fun `progress numbers each file within the filtered subset - not the manifest`() {
        val files = listOf(
            remote("a-Q4_K_M.gguf", configBody.length.toLong()),
            remote("b-Q8_0.gguf", 10L),
            remote("c-Q4_K_M.gguf", weightsBody.length.toLong()),
        )
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)
        val events = mutableListOf<RepoProgress>()

        await {
            downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) { events += it }
        }.getOrThrow()

        val downloading = events.filterIsInstance<RepoProgress.Downloading>()
        assertEquals(setOf(0, 1), downloading.map { it.fileIndex }.toSet())
        assertTrue(downloading.all { it.fileCount == 2 })
    }

    // ---- file filter: filter-keyed staging (spec tests 4, 8, 12; pinned filterKey) ----

    /**
     * Pins filterKey's output for a known (pattern, options) pair — sha256 of "6:Q4_K_M" (the
     * canonicalIdentity of Regex("Q4_K_M") with no options: 6 is pattern.length, not a typo).
     * The staging directory name is derived state: a refactor of canonicalIdentity that quietly
     * changes this hex silently abandons every already-staged byte rather than failing.
     */
    @Test
    fun `a filter's staging directory lands at the pinned filter key`() {
        val files = listOf(remote("model-Q4_K_M.gguf", 100L))
        queue.enqueue(status = HttpStatusCode.InternalServerError)

        await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }

        val keyed = root / ".staging" / "o" /
            "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce"
        assertTrue(fs.metadataOrNull(keyed)?.isDirectory == true)
    }

    @Test
    fun `an equal filter built from a fresh Regex instance resumes the same staging`() {
        val files = listOf(remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)))
        writeText(
            root / ".staging" / "o" /
                "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce" /
                "model-Q4_K_M.gguf",
            weightsBody,
        )

        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun `a different filter stages independently and the first filter's bytes survive to be resumed`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong(), shaOf(configBody)),
        )
        // First filter's progress: a satisfied bare file, staged by hand in Q4_K_M's own directory.
        val stagedA = root / ".staging" / "o" /
            "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce" /
            "model-Q4_K_M.gguf"
        writeText(stagedA, weightsBody)

        // Second filter's attempt fails mid-flight — its staging is its own, not Q4_K_M's.
        queue.enqueue(status = HttpStatusCode.InternalServerError)
        val second = await { downloaderFor(files).download("o/m", root, Regex("Q8_0")) }
        assertTrue(second.isFailure)
        assertEquals(weightsBody, readText(stagedA))

        // A later Q4_K_M call resumes the surviving bytes: no new request needed.
        val requestsBefore = queue.requests.size
        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()
        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertEquals(requestsBefore, queue.requests.size)
    }

    @Test
    fun `bytes staged by a pre-filter ferry are resumed by an unfiltered call`() {
        // A pre-filter ferry staged at exactly `<id>.d` — the path an unfiltered call resolves.
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        writeText(root / ".staging" / "o" / "m.d" / "model.bin", weightsBody)

        val dir = await { downloaderFor(files).download("o/m", root) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model.bin"))
        assertTrue(queue.requests.isEmpty())
    }
}
