package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FerryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val configBody = """{"model_type":"qwen2"}"""

    private val treeJson = """
        [ { "type": "file", "path": "config.json", "size": ${configBody.length} } ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches a repo end to end through the facade`() {
        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        val dir = runBlocking { ferry.download("Qwen/Q-0.5B", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dir, "config.json").readText())
    }

    @Test
    fun `the facade reports progress`() {
        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val seen = mutableListOf<RepoProgress>()
        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        runBlocking { ferry.download("Qwen/Q-0.5B", temp.root) { seen += it } }

        assertTrue(seen.last() is RepoProgress.Complete)
    }

    @Test
    fun `a missing repo is a failure, not an exception`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        val result = runBlocking { ferry.download("nope/nope", temp.root) }

        assertTrue(result.isFailure)
    }
}
