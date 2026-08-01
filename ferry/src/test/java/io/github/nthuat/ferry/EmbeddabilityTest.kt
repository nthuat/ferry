package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
            client = hostClient,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        runBlocking { ferry.download("Qwen/Q-0.5B", temp.root) }

        assertEquals(
            "manifest and file requests must both be visible to the host",
            2,
            seenByHost.size,
        )
        assertTrue(seenByHost.any { it.contains("/tree/main") })
        assertTrue(seenByHost.any { it.contains("/resolve/main/config.json") })
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
                .download("Qwen/Q-0.5B", temp.root) { progress += it }
        }

        assertTrue(result.isSuccess)
        assertTrue(progress.last() is RepoProgress.Complete)
    }
}
