package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Ferry has to slot into a host that already decided how it backgrounds work and how it talks HTTP.
 * These assertions are the contract that makes that possible.
 */
class EmbeddabilityTest {

    private val fs = FakeFileSystem()
    private val root = "/downloads".toPath()

    private lateinit var queue: QueueClient

    private val configBody = """{"model_type":"qwen2"}"""

    private val treeJson = """
        [ { "type": "file", "path": "config.json", "size": ${configBody.length} } ]
    """.trimIndent()

    private val modelScopeListingJson = """
        { "Code": 200, "Success": true, "Data": { "Files": [
          { "Path": "config.json", "Type": "blob", "Size": ${configBody.length}, "Sha256": "abc" }
        ] } }
    """.trimIndent()

    private val ollamaManifestJson = """
        { "schemaVersion": 2, "layers": [
          { "mediaType": "application/vnd.ollama.image.model", "size": ${configBody.length},
            "digest": "sha256:8e28eec6e1be158f6ddd03b882aed149db322f3e0824e3dd4c1e860601990a4b" }
        ] }
    """.trimIndent()

    @Before
    fun setUp() {
        fs.createDirectories(root)
        queue = QueueClient()
    }

    @After
    fun tearDown() = fs.checkNoOpenFiles()

    /**
     * Every request, for the manifest and for the files, must travel through the client the host
     * handed over. `queue.requests` is the proof: it only ever grows from inside the QueueClient's
     * own engine, so a request that reached the server without going through `queue.client` — the
     * one Ferry was handed — would simply never appear in it, stronger than tallying an interceptor
     * that could itself be skipped by an unwrapped client.
     */
    @Test
    fun `every request goes through the caller's http client`() = runTest {
        queue.enqueue(body = treeJson)
        queue.enqueue(body = configBody)

        val ferry = Ferry.huggingFace(client = queue.client, fileSystem = fs, baseUrl = "http://hub.test")
        ferry.download("Qwen/Q-0.5B", root)

        assertEquals(
            "manifest and file requests must both be visible to the host",
            2,
            queue.requests.size,
        )
        assertTrue(queue.requests.any { it.url.encodedPath.contains("/tree/main") })
        assertTrue(queue.requests.any { it.url.encodedPath.contains("/resolve/main/config.json") })
    }

    /** Same guarantee as above, for the second hub — a distinct adapter, easy to wire in wrong. */
    @Test
    fun `every modelscope request goes through the caller's http client`() = runTest {
        queue.enqueue(body = modelScopeListingJson)
        queue.enqueue(body = configBody)

        val ferry = Ferry.modelScope(client = queue.client, fileSystem = fs, baseUrl = "http://hub.test")
        ferry.download("owner/model", root)

        assertEquals(
            "manifest and file requests must both be visible to the host",
            2,
            queue.requests.size,
        )
        assertTrue(queue.requests.any { it.url.encodedPath.endsWith("/repo/files") })
        assertTrue(queue.requests.any { it.url.encodedPath.endsWith("/repo") })
    }

    /** Same guarantee again, for the third hub - a structurally different adapter, not just a third copy. */
    @Test
    fun `every ollama request goes through the caller's http client`() = runTest {
        queue.enqueue(body = ollamaManifestJson)
        queue.enqueue(body = configBody)

        val ferry = Ferry.ollama(client = queue.client, fileSystem = fs, baseUrl = "http://hub.test")
        ferry.download("qwen2.5:0.5b", root)

        assertEquals(
            "manifest and blob requests must both be visible to the host",
            2,
            queue.requests.size,
        )
        assertTrue(queue.requests.any { it.url.encodedPath.contains("/manifests/") })
        assertTrue(queue.requests.any { it.url.encodedPath.contains("/blobs/") })
    }

    /**
     * The whole API is driven here from a plain JVM test with no Android object of any kind. If
     * this file ever needs a Context, a Looper or Robolectric, the library stopped being
     * embeddable in something that is not structured like the sample app.
     */
    @Test
    fun `the api is exercisable without any android object`() = runTest {
        queue.enqueue(body = treeJson)
        queue.enqueue(body = configBody)

        val progress = mutableListOf<RepoProgress>()
        val result = Ferry.huggingFace(client = queue.client, fileSystem = fs, baseUrl = "http://hub.test")
            .download("Qwen/Q-0.5B", root) { progress += it }

        assertTrue(result.isSuccess)
        assertTrue(progress.last() is RepoProgress.Complete)
    }

    // jvm-only: moves to jvmTest in Task 7
    /**
     * The interceptor test's other value, beyond `queue.requests`' proof above: a host that hands
     * over its own raw OkHttpClient — interceptors included, not just a bare Ktor client — still
     * gets every request through it. MockWebServer and a real temp dir are jvm-only, unlike the
     * QueueClient tests above, which is why this one is not ported to the other platforms this
     * facade will target from Task 7 on.
     */
    @Test
    fun `a preconfigured okhttp client's interceptors see every request`() {
        val seenByHost = mutableListOf<String>()
        val hostClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seenByHost += chain.request().url.encodedPath
                chain.proceed(chain.request())
            })
            .build()

        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(treeJson))
            server.enqueue(MockResponse().setBody(configBody))

            val tempDir = Files.createTempDirectory("ferry-embeddability-test").toOkioPath()
            val ferry = Ferry.huggingFace(
                client = HttpClient(OkHttp) { engine { preconfigured = hostClient } },
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            runBlocking { ferry.download("Qwen/Q-0.5B", tempDir) }

            assertEquals(
                "manifest and file requests must both be visible to the host",
                2,
                seenByHost.size,
            )
            assertTrue(seenByHost.any { it.contains("/tree/main") })
            assertTrue(seenByHost.any { it.contains("/resolve/main/config.json") })
        } finally {
            server.shutdown()
        }
    }
}
