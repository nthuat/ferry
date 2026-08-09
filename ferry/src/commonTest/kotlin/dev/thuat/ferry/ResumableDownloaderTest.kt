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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ResumableDownloaderTest {

    private val fs = FakeFileSystem()
    private val root = "/downloads".toPath()

    private lateinit var queue: QueueClient
    private lateinit var downloader: ResumableDownloader

    private val fullBody = "0123456789abcdefghij" // 20 bytes, each position identifiable
    private val url = "https://files.example.test/asset.bin"

    @BeforeTest
    fun setUp() {
        fs.createDirectories(root)
        queue = QueueClient()
        downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    private fun target(): Path = root / "asset.bin"

    private fun partFile(): Path = root / "asset.bin.part"

    private fun validatorFile(): Path = root / "asset.bin.validator"

    private fun readText(path: Path): String = fs.read(path) { readUtf8() }

    private fun writeText(path: Path, text: String) = fs.write(path) { writeUtf8(text) }

    private fun sizeOf(path: Path): Long = fs.metadataOrNull(path)?.size ?: 0L

    private fun download(target: Path = target()): Result<Path> {
        var result: Result<Path>? = null
        runTest { result = downloader.download(url, target) }
        return result!!
    }

    @Test
    fun `fresh download writes the whole file`() {
        queue.enqueue(body = fullBody, headers = headersOf("ETag", "\"v1\""))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
        assertNull(queue.requests[0].headers[HttpHeaders.Range]) // nothing to resume from
    }

    @Test
    fun `the part file is renamed only at the end`() {
        queue.enqueue(body = fullBody)

        download()

        assertFalse(fs.exists(partFile()), "part file must not survive a successful download")
        assertTrue(fs.exists(target()))
    }

    @Test
    fun `an existing part file resumes from where it stopped`() {
        writeText(partFile(), fullBody.take(8))
        writeText(validatorFile(), "\"v1\"")
        queue.enqueue(
            bodyBytes = fullBody.drop(8).encodeToByteArray(),
            status = HttpStatusCode.PartialContent,
            headers = headersOf("Content-Range", "bytes 8-19/20"),
        )

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))

        val request = queue.requests[0]
        assertEquals("bytes=8-", request.headers[HttpHeaders.Range])
        assertEquals("\"v1\"", request.headers[HttpHeaders.IfRange])
    }

    /**
     * The corruption bug. We asked for bytes 8- and the server sent all 20 anyway. Appending would
     * produce a 28-byte file that looks plausible and is permanently wrong.
     */
    @Test
    fun `a 200 answer to a range request restarts instead of appending`() {
        writeText(partFile(), fullBody.take(8))
        queue.enqueue(body = fullBody, status = HttpStatusCode.OK)

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
        assertEquals(20L, sizeOf(target()))
    }

    /**
     * ModelScope honours a Range request and answers 200 with a valid Content-Range instead of 206.
     * Reading only the status code makes resume impossible against that hub: every attempt restarts,
     * writes the tail at offset zero, and fails the length check forever.
     */
    @Test
    fun `a 200 carrying a matching Content-Range is a continuation - not a restart`() {
        writeText(partFile(), fullBody.take(8))
        writeText(validatorFile(), "\"v1\"")
        queue.enqueue(
            bodyBytes = fullBody.drop(8).encodeToByteArray(),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Range", "bytes 8-19/20"),
        )

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
    }

    /** A whole body offered while we asked for a tail must still restart, code notwithstanding. */
    @Test
    fun `a 200 whose Content-Range starts at zero still restarts`() {
        writeText(partFile(), "STALE---")
        writeText(validatorFile(), "\"v1\"")
        queue.enqueue(
            body = fullBody,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Range", "bytes 0-19/20"),
        )

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
        assertEquals(20L, sizeOf(target()))
    }

    /** Same path, reached the other way: If-Range fails because the resource changed. */
    @Test
    fun `a changed validator restarts cleanly`() {
        writeText(partFile(), "STALE---")
        writeText(validatorFile(), "\"v1\"")
        // Server sees If-Range: "v1", knows the file is now v2, answers with the whole thing.
        queue.enqueue(body = fullBody, status = HttpStatusCode.OK, headers = headersOf("ETag", "\"v2\""))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
    }

    @Test
    fun `a short body fails and keeps the part file for the next attempt`() {
        queue.enqueue(
            bodyBytes = fullBody.take(5).encodeToByteArray(),
            status = HttpStatusCode.PartialContent,
            headers = headersOf("Content-Range", "bytes 0-19/20"),
        )

        val result = download()

        assertTrue(result.isFailure)
        assertFalse(fs.exists(target()), "target must not appear until it is complete")
        assertTrue(fs.exists(partFile()), "part file is the resume point")
        assertEquals(5L, sizeOf(partFile()))
    }

    /**
     * A server with no ETag and no Last-Modified gives us nothing to validate against, so there
     * is no safe way to resume. Restarting costs bandwidth; resuming risks a corrupt file.
     */
    @Test
    fun `a part file with no validator restarts rather than resuming blind`() {
        writeText(partFile(), "STALE---")
        queue.enqueue(body = fullBody)

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, readText(target()))
        assertNull(queue.requests[0].headers[HttpHeaders.Range], "must not resume without a validator")
    }

    /**
     * Ranges index the encoded representation. If the first request is allowed to be gzipped, the
     * .part file holds decompressed bytes and its length is not a valid resume offset.
     */
    @Test
    fun `every request asks for identity encoding`() {
        queue.enqueue(body = fullBody)

        download()

        assertEquals("identity", queue.requests[0].headers[HttpHeaders.AcceptEncoding])
    }

    /**
     * A malformed url must be returned as a failure, not thrown across the Result-returning
     * boundary. ModelHub is public — a third-party adapter's bad URL would otherwise throw straight
     * out of RemoteFile.url reaching here.
     */
    @Test
    fun `a malformed url is returned as a failure - not thrown`() {
        var result: Result<Path>? = null
        runTest { result = downloader.download("not a url", target()) }

        assertTrue(result!!.isFailure)
    }

    @Test
    fun `an http error is reported and does not create the target`() {
        queue.enqueue(status = HttpStatusCode.NotFound)

        val result = download()

        assertTrue(result.isFailure)
        assertFalse(fs.exists(target()))
    }

    @Test
    fun `progress reports totals from Content-Range - not the slice length`() {
        writeText(partFile(), fullBody.take(8))
        writeText(validatorFile(), "\"v1\"")
        queue.enqueue(
            bodyBytes = fullBody.drop(8).encodeToByteArray(),
            status = HttpStatusCode.PartialContent,
            headers = headersOf("Content-Range", "bytes 8-19/20"),
        )

        val seen = mutableListOf<Pair<Long, Long?>>()
        runTest {
            downloader.download(url, target()) { written, total ->
                seen += written to total
            }
        }

        assertEquals(20L, seen.last().first)
        assertEquals(20L, seen.last().second) // not 12, the size of this response's body
        assertTrue(seen.first().first > 8L, "progress must start past the resumed bytes")
    }
}
