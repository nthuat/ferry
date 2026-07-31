package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResumableDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ResumableDownloader

    private val fullBody = "0123456789abcdefghij" // 20 bytes, each position identifiable

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = ResumableDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun target(): File = File(temp.root, "asset.bin")

    private fun partFile(): File = File(temp.root, "asset.bin.part")

    private fun download(target: File = target()) =
        runBlocking { downloader.download(server.url("/asset.bin").toString(), target) }

    @Test
    fun `fresh download writes the whole file`() {
        server.enqueue(MockResponse().setBody(fullBody).addHeader("ETag", "\"v1\""))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, target().readText())
        assertNull(server.takeRequest().getHeader("Range")) // nothing to resume from
    }

    @Test
    fun `the part file is renamed only at the end`() {
        server.enqueue(MockResponse().setBody(fullBody))

        download()

        assertFalse("part file must not survive a successful download", partFile().exists())
        assertTrue(target().exists())
    }

    @Test
    fun `an existing part file resumes from where it stopped`() {
        partFile().writeText(fullBody.take(8))
        File(temp.root, "asset.bin.validator").writeText("\"v1\"")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(fullBody.drop(8))
                .addHeader("Content-Range", "bytes 8-19/20"),
        )

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, target().readText())

        val request = server.takeRequest()
        assertEquals("bytes=8-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    /**
     * The corruption bug. We asked for bytes 8- and the server sent all 20 anyway. Appending would
     * produce a 28-byte file that looks plausible and is permanently wrong.
     */
    @Test
    fun `a 200 answer to a range request restarts instead of appending`() {
        partFile().writeText(fullBody.take(8))
        server.enqueue(MockResponse().setResponseCode(200).setBody(fullBody))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, target().readText())
        assertEquals(20, target().length())
    }

    /** Same path, reached the other way: If-Range fails because the resource changed. */
    @Test
    fun `a changed validator restarts cleanly`() {
        partFile().writeText("STALE---")
        File(temp.root, "asset.bin.validator").writeText("\"v1\"")
        // Server sees If-Range: "v1", knows the file is now v2, answers with the whole thing.
        server.enqueue(MockResponse().setResponseCode(200).setBody(fullBody).addHeader("ETag", "\"v2\""))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, target().readText())
    }

    @Test
    fun `a short body fails and keeps the part file for the next attempt`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(fullBody.take(5))
                .addHeader("Content-Range", "bytes 0-19/20"),
        )

        val result = download()

        assertTrue(result.isFailure)
        assertFalse("target must not appear until it is complete", target().exists())
        assertTrue("part file is the resume point", partFile().exists())
        assertEquals(5, partFile().length())
    }

    /**
     * A server with no ETag and no Last-Modified gives us nothing to validate against, so there
     * is no safe way to resume. Restarting costs bandwidth; resuming risks a corrupt file.
     */
    @Test
    fun `a part file with no validator restarts rather than resuming blind`() {
        partFile().writeText("STALE---")
        server.enqueue(MockResponse().setBody(fullBody))

        val result = download()

        assertTrue(result.isSuccess)
        assertEquals(fullBody, target().readText())
        assertNull("must not resume without a validator", server.takeRequest().getHeader("Range"))
    }

    /**
     * Ranges index the encoded representation. If the first request is allowed to be gzipped, the
     * .part file holds decompressed bytes and its length is not a valid resume offset.
     */
    @Test
    fun `every request asks for identity encoding`() {
        server.enqueue(MockResponse().setBody(fullBody))

        download()

        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun `an http error is reported and does not create the target`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = download()

        assertTrue(result.isFailure)
        assertFalse(target().exists())
    }

    @Test
    fun `progress reports totals from Content-Range, not the slice length`() {
        partFile().writeText(fullBody.take(8))
        File(temp.root, "asset.bin.validator").writeText("\"v1\"")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(fullBody.drop(8))
                .addHeader("Content-Range", "bytes 8-19/20"),
        )

        val seen = mutableListOf<Pair<Long, Long?>>()
        runBlocking {
            downloader.download(server.url("/asset.bin").toString(), target()) { written, total ->
                seen += written to total
            }
        }

        assertEquals(20L, seen.last().first)
        assertEquals(20L, seen.last().second) // not 12, the size of this response's body
        assertTrue("progress must start past the resumed bytes", seen.first().first > 8L)
    }
}
