package dev.thuat.ferry

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class FerryTest {

    private val fs = FakeFileSystem()
    private val root = "/downloads".toPath()

    private lateinit var queue: QueueClient

    private val configBody = """{"model_type":"qwen2"}"""

    private val treeJson = """
        [ { "type": "file", "path": "config.json", "size": ${configBody.length} } ]
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        fs.createDirectories(root)
        queue = QueueClient()
    }

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    private fun huggingFace() = Ferry.huggingFace(
        client = queue.client,
        fileSystem = fs,
        baseUrl = "http://hub.test",
    )

    @Test
    fun `fetches a repo end to end through the facade`() = runTest {
        queue.enqueue(body = treeJson)
        queue.enqueue(body = configBody)

        val dir = huggingFace().download("Qwen/Q-0.5B", root).getOrThrow()

        assertEquals(configBody, fs.read(dir / "config.json") { readUtf8() })
    }

    @Test
    fun `the facade reports progress`() = runTest {
        queue.enqueue(body = treeJson)
        queue.enqueue(body = configBody)

        val seen = mutableListOf<RepoProgress>()
        huggingFace().download("Qwen/Q-0.5B", root) { seen += it }

        assertTrue(seen.last() is RepoProgress.Complete)
    }

    @Test
    fun `a missing repo is a failure, not an exception`() = runTest {
        queue.enqueue(status = HttpStatusCode.NotFound)

        val result = huggingFace().download("nope/nope", root)

        assertTrue(result.isFailure)
    }
}
