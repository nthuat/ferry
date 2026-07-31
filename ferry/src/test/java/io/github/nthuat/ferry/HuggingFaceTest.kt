package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HuggingFaceTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: HuggingFace

    /** Shaped exactly like the live response, including a field this code does not know about. */
    private val treeJson = """
        [
          { "type": "directory", "path": "onnx" },
          { "type": "file", "path": "config.json", "size": 659 },
          { "type": "file", "path": "model.safetensors", "size": 988097824,
            "oid": "d7db405a3f0d9bf1ba5bdd4e4211db8022ebe4eb",
            "lfs": { "oid": "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
                     "size": 988097824 },
            "xetHash": "bb5ff7e71536bbce6378f6d4bb523a77f1e9455965702d18bec33f599d5851f7" }
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = HuggingFace(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest lists files and skips directories`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertEquals(listOf("config.json", "model.safetensors"), manifest.files.map { it.path })
    }

    @Test
    fun `sha256 comes from lfs oid, not the top level git oid`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()
        val weights = manifest.files.single { it.path == "model.safetensors" }

        assertEquals(
            "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
            weights.sha256,
        )
    }

    @Test
    fun `a small non-lfs file has no sha256`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertNull(manifest.files.single { it.path == "config.json" }.sha256)
    }

    @Test
    fun `total bytes sums every file`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertEquals(659L + 988097824L, manifest.totalBytes)
    }

    /** xetHash is in the fixture and unknown to the parser. Strict parsing would throw here. */
    @Test
    fun `unknown fields do not break parsing`() {
        server.enqueue(MockResponse().setBody(treeJson))

        assertTrue(runBlocking { repo.manifest("any/repo") }.isSuccess)
    }

    @Test
    fun `an http error is returned as a failure`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { repo.manifest("nope/nope") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed json is returned as a failure, not thrown`() {
        server.enqueue(MockResponse().setBody("not json at all"))

        val result = runBlocking { repo.manifest("any/repo") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `manifest requests the tree endpoint`() {
        server.enqueue(MockResponse().setBody(treeJson))

        runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }

        assertEquals(
            "/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main",
            server.takeRequest().path,
        )
    }

    @Test
    fun `each file carries a resolved download url`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()
        val weights = manifest.files.single { it.path == "model.safetensors" }

        assertTrue(
            weights.url.endsWith("/Qwen/Qwen2.5-0.5B-Instruct/resolve/main/model.safetensors"),
        )
    }
}
