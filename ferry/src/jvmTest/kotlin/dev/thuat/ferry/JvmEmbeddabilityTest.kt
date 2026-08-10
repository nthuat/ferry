package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only halves of `EmbeddabilityTest` and `SpaceCheckTest`: a real OkHttpClient, a real
 * MockWebServer and a real temp directory on disk are all jvm-only, unlike the FakeFileSystem and
 * Ktor MockEngine the common suites run against — nothing here has an appleMain equivalent.
 */
class JvmEmbeddabilityTest {

    private val configBody = """{"model_type":"qwen2"}"""

    private val treeJson = """
        [ { "type": "file", "path": "config.json", "size": ${configBody.length} } ]
    """.trimIndent()

    private val tempDirs = mutableListOf<File>()

    /** Deletes every temp directory this test class created, however many of these tests ran. */
    @AfterTest
    fun cleanUpTempDirs() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newTempDir(prefix: String): java.nio.file.Path =
        Files.createTempDirectory(prefix).also { tempDirs += it.toFile() }

    /**
     * The interceptor test's value, beyond `EmbeddabilityTest`'s QueueClient-based proof: a host
     * that hands over its own raw OkHttpClient — interceptors included, not just a bare Ktor client
     * — still gets every request through it.
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

            val tempDir = newTempDir("ferry-embeddability-test").toOkioPath()
            val ferry = Ferry.huggingFace(
                client = HttpClient(OkHttp) { engine { preconfigured = hostClient } },
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            runBlocking { ferry.download("Qwen/Q-0.5B", tempDir) }

            assertEquals(
                2,
                seenByHost.size,
                "manifest and file requests must both be visible to the host",
            )
            assertTrue(seenByHost.any { it.contains("/tree/main") })
            assertTrue(seenByHost.any { it.contains("/resolve/main/config.json") })
        } finally {
            server.shutdown()
        }
    }

    /**
     * `File.usableSpace` — the default probe's JVM implementation — is what makes this behavior
     * worth pinning on the JVM specifically: it needs a real directory on a real disk to answer.
     */
    @Test
    fun `the default probe reports a positive figure for a real directory`() {
        val tempPath = newTempDir("ferry-test").toString().toPath()
        assertTrue(DefaultFreeSpaceProbe.freeBytes(tempPath) > 0L)
    }

    /**
     * `File.usableSpace` returns 0 — not an exception — for a path that does not exist, and
     * `SpaceCheck` is public, exported on the `api` configuration: a host preflighting with
     * `SpaceCheck().check(manifest, File(filesDir, "models"))` on a clean install, before that
     * directory is ever created, would otherwise see "nothing fits" regardless of how much space is
     * actually free. The temp directory created here is used only as the *parent*, never as the
     * directory under test itself, which must not exist yet — exactly the condition this test proves.
     */
    @Test
    fun `the default probe reports the volume's real free space for a directory that does not exist yet`() {
        val parentDir = newTempDir("ferry-test-parent")
        val missing = "$parentDir/fresh-install".toPath()

        val report = SpaceCheck().check(RepoManifest("test/repo", listOf(RemoteFile("f", "u", 1L, null))), missing)

        assertTrue(
            report.freeBytes > 0L,
            "must report the volume's real free space, not the phantom zero usableSpace reports " +
                "for a path that does not exist yet",
        )
    }
}
