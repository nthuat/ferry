package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ferry has to slot into a host that already decided how it backgrounds work and how it talks HTTP.
 * These assertions are the contract that makes that possible.
 */
class EmbeddabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

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
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Every request, for the manifest and for the files, must travel through the client the host
     * handed over. A host's auth interceptor that fires on some requests and not others is worse
     * than one that never fires, because it works in testing.
     */
    @Test
    fun `every request goes through the caller's http client`() {
        val seenByHost = mutableListOf<String>()
        val hostClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seenByHost += chain.request().url.encodedPath
                chain.proceed(chain.request())
            })
            .build()

        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val ferry = Ferry.huggingFace(
            // Ferry's factories take an HttpClient now (Task 5); this test's whole point is that a
            // host's own OkHttpClient — interceptors included — is the one every request travels
            // through, so it stays OkHttp-typed above and is wrapped here rather than switched to a
            // plain Ktor client that would lose the interceptor. Task 6 gives Ferry a real
            // OkHttpClient-friendly entry point; this bridge is a minimal compile fix, not that.
            client = HttpClient(OkHttp) { engine { preconfigured = hostClient } },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        runBlocking { ferry.download("Qwen/Q-0.5B", temp.root.toOkioPath()) }

        assertEquals(
            "manifest and file requests must both be visible to the host",
            2,
            seenByHost.size,
        )
        assertTrue(seenByHost.any { it.contains("/tree/main") })
        assertTrue(seenByHost.any { it.contains("/resolve/main/config.json") })
    }

    /** Same guarantee as above, for the second hub — a distinct adapter, easy to wire in wrong. */
    @Test
    fun `every modelscope request goes through the caller's http client`() {
        val seenByHost = mutableListOf<String>()
        val hostClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seenByHost += chain.request().url.encodedPath
                chain.proceed(chain.request())
            })
            .build()

        server.enqueue(MockResponse().setBody(modelScopeListingJson))
        server.enqueue(MockResponse().setBody(configBody))

        val ferry = Ferry.modelScope(
            // Ferry's factories take an HttpClient now (Task 5); this test's whole point is that a
            // host's own OkHttpClient — interceptors included — is the one every request travels
            // through, so it stays OkHttp-typed above and is wrapped here rather than switched to a
            // plain Ktor client that would lose the interceptor. Task 6 gives Ferry a real
            // OkHttpClient-friendly entry point; this bridge is a minimal compile fix, not that.
            client = HttpClient(OkHttp) { engine { preconfigured = hostClient } },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        runBlocking { ferry.download("owner/model", temp.root.toOkioPath()) }

        assertEquals(
            "manifest and file requests must both be visible to the host",
            2,
            seenByHost.size,
        )
        assertTrue(seenByHost.any { it.endsWith("/repo/files") })
        assertTrue(seenByHost.any { it.endsWith("/repo") })
    }

    /** Same guarantee again, for the third hub - a structurally different adapter, not just a third copy. */
    @Test
    fun `every ollama request goes through the caller's http client`() {
        val seenByHost = mutableListOf<String>()
        val hostClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seenByHost += chain.request().url.encodedPath
                chain.proceed(chain.request())
            })
            .build()

        server.enqueue(MockResponse().setBody(ollamaManifestJson))
        server.enqueue(MockResponse().setBody(configBody))

        val ferry = Ferry.ollama(
            // Ferry's factories take an HttpClient now (Task 5); this test's whole point is that a
            // host's own OkHttpClient — interceptors included — is the one every request travels
            // through, so it stays OkHttp-typed above and is wrapped here rather than switched to a
            // plain Ktor client that would lose the interceptor. Task 6 gives Ferry a real
            // OkHttpClient-friendly entry point; this bridge is a minimal compile fix, not that.
            client = HttpClient(OkHttp) { engine { preconfigured = hostClient } },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        runBlocking { ferry.download("qwen2.5:0.5b", temp.root.toOkioPath()) }

        assertEquals(
            "manifest and blob requests must both be visible to the host",
            2,
            seenByHost.size,
        )
        assertTrue(seenByHost.any { it.contains("/manifests/") })
        assertTrue(seenByHost.any { it.contains("/blobs/") })
    }

    /**
     * The whole API is driven here from a plain JVM test with no Android object of any kind. If
     * this file ever needs a Context, a Looper or Robolectric, the library stopped being
     * embeddable in something that is not structured like the sample app.
     */
    @Test
    fun `the api is exercisable without any android object`() {
        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val progress = mutableListOf<RepoProgress>()
        val result = runBlocking {
            Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
                .download("Qwen/Q-0.5B", temp.root.toOkioPath()) { progress += it }
        }

        assertTrue(result.isSuccess)
        assertTrue(progress.last() is RepoProgress.Complete)
    }
}
