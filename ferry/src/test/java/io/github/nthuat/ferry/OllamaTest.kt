package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OllamaTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: Ollama

    /**
     * Shaped exactly like the live response: a config blob alongside four layers, each carrying
     * nothing but mediaType/size/digest - no filename anywhere. Digests are the verified-live
     * prefixes, used as-is (this code never checks digest length or hex-ness beyond the "sha256:"
     * prefix, so a shortened one exercises the parser identically to a real 64-hex-char one).
     */
    private val basicManifestJson = """
        { "schemaVersion": 2, "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
          "config": { "mediaType": "application/vnd.docker.container.image.v1+json", "size": 490,
                       "digest": "sha256:configdigest00" },
          "layers": [
            { "mediaType": "application/vnd.ollama.image.model", "size": 397807936,
              "digest": "sha256:c5396e06af" },
            { "mediaType": "application/vnd.ollama.image.system", "size": 68,
              "digest": "sha256:66b9ea09bd" },
            { "mediaType": "application/vnd.ollama.image.template", "size": 1482,
              "digest": "sha256:eb4402837c" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 11343,
              "digest": "sha256:832dd9e00a" }
          ] }
    """.trimIndent()

    /**
     * llama3.2-vision:11b's real shape, verified live: five layers, and two of them - both
     * "image.license" - share a mediaType. Naming a file by mediaType suffix alone collides here;
     * this fixture is what a naive suffix-only implementation must fail against (see the
     * distinct-paths and total-bytes tests below), and what this adapter must keep passing forever.
     */
    private val visionCollisionJson = """
        { "schemaVersion": 2, "layers": [
            { "mediaType": "application/vnd.ollama.image.model", "size": 6433703168,
              "digest": "sha256:aaaa1111" },
            { "mediaType": "application/vnd.ollama.image.template", "size": 1565,
              "digest": "sha256:bbbb2222" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 6021,
              "digest": "sha256:cccc3333" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 7680,
              "digest": "sha256:dddd4444" },
            { "mediaType": "application/vnd.ollama.image.params", "size": 59,
              "digest": "sha256:eeee5555" }
          ] }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = Ollama(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sha256 is the digest with its sha256 prefix stripped`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        val manifest = runBlocking { repo.manifest("qwen2.5:0.5b") }.getOrThrow()

        assertEquals("c5396e06af", manifest.files.single { it.path == "model-c5396e06af" }.sha256)
        assertEquals("832dd9e00a", manifest.files.single { it.path == "license-832dd9e00a" }.sha256)
    }

    @Test
    fun `size is mapped from each layer`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        val manifest = runBlocking { repo.manifest("qwen2.5:0.5b") }.getOrThrow()

        assertEquals(397807936L, manifest.files.single { it.path == "model-c5396e06af" }.sizeBytes)
        assertEquals(11343L, manifest.files.single { it.path == "license-832dd9e00a" }.sizeBytes)
    }

    @Test
    fun `total bytes sums every layer`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        val manifest = runBlocking { repo.manifest("qwen2.5:0.5b") }.getOrThrow()

        assertEquals(397807936L + 68L + 1482L + 11343L, manifest.totalBytes)
    }

    /**
     * The design call this adapter's KDoc defends: config describes the layers, it is not one of
     * them. Pinned so a future edit cannot fold it back into `files` unnoticed - four layers in the
     * fixture must mean four files, and none of them the config's own digest.
     */
    @Test
    fun `config is not included among the downloaded files`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        val manifest = runBlocking { repo.manifest("qwen2.5:0.5b") }.getOrThrow()

        assertEquals(4, manifest.files.size)
        assertTrue(manifest.files.none { it.sha256 == "configdigest00" })
    }

    @Test
    fun `each file carries a blob url built from the qualified name and digest`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        val manifest = runBlocking { repo.manifest("qwen2.5:0.5b") }.getOrThrow()
        val model = manifest.files.single { it.path == "model-c5396e06af" }

        assertEquals(
            listOf("v2", "library", "qwen2.5", "blobs", "sha256:c5396e06af"),
            model.url.toHttpUrl().pathSegments,
        )
    }

    /**
     * The whole reason this adapter is interesting: llama3.2-vision:11b's two "image.license"
     * layers must not collapse onto one path. Collision-proof by construction (see [Ollama.manifest]
     * KDoc), not by detecting the collision and bolting on a suffix - reverting the digest suffix
     * back to a bare mediaType suffix (`path = layerSuffix(layer.mediaType)`) makes this go red:
     * confirmed by hand against this exact fixture before committing.
     */
    @Test
    fun `two layers of the same mediaType produce distinct paths, not a collision`() {
        server.enqueue(MockResponse().setBody(visionCollisionJson))

        val manifest = runBlocking { repo.manifest("llama3.2-vision:11b") }.getOrThrow()

        assertEquals(5, manifest.files.size)
        assertEquals(5, manifest.files.map { it.path }.distinct().size)
        assertEquals(
            listOf(
                "model-aaaa1111",
                "template-bbbb2222",
                "license-cccc3333",
                "license-dddd4444",
                "params-eeee5555",
            ),
            manifest.files.map { it.path },
        )
    }

    /** The same fixture's total must include both license layers, not silently drop one. */
    @Test
    fun `total bytes across the colliding layers is not short by the dropped one`() {
        server.enqueue(MockResponse().setBody(visionCollisionJson))

        val manifest = runBlocking { repo.manifest("llama3.2-vision:11b") }.getOrThrow()

        assertEquals(6433703168L + 1565L + 6021L + 7680L + 59L, manifest.totalBytes)
    }

    @Test
    fun `manifest requests the tag given in the repo id`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        runBlocking { repo.manifest("qwen2.5:0.5b") }

        assertEquals("/v2/library/qwen2.5/manifests/0.5b", server.takeRequest().path)
    }

    /** "a bare name with no tag, which conventionally means latest" - stated in Ollama's own KDoc. */
    @Test
    fun `a bare repo id with no tag defaults to latest`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        runBlocking { repo.manifest("llava") }

        assertEquals("/v2/library/llava/manifests/latest", server.takeRequest().path)
    }

    /** A repoId that already names its own namespace must not also get "library/" prepended. */
    @Test
    fun `a repo id that already carries a namespace is not re-prefixed with library`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        runBlocking { repo.manifest("someuser/custom-model:v1") }

        assertEquals("/v2/someuser/custom-model/manifests/v1", server.takeRequest().path)
    }

    /** The Accept header is required - verified live, its absence gets a different response shape. */
    @Test
    fun `manifest request carries the required docker manifest v2 accept header`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        runBlocking { repo.manifest("qwen2.5:0.5b") }

        assertEquals(
            "application/vnd.docker.distribution.manifest.v2+json",
            server.takeRequest().getHeader("Accept"),
        )
    }

    /**
     * The same fix as HuggingFace/ModelScope's own ".." entries in docs/known-limitations.md, applied
     * here: addPathSegments resolves ".." by popping the segment before it, so this would otherwise
     * land the request outside the "v2" namespace instead of failing. Asserted on requestCount, not
     * only the Result, since a destructive version of this bug could still fail after already leaking
     * the request.
     */
    @Test
    fun `a repo id that traverses out of the registry namespace is refused before any request is issued`() {
        val result = runBlocking { repo.manifest("../../etc/passwd") }

        assertTrue("must fail rather than retarget the request", result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an http error is returned as a failure`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { repo.manifest("nope:latest") }

        assertTrue(result.isFailure)
    }

    /** "an OCI registry can also return a structured error body" - surfaced when present. */
    @Test
    fun `an http error with an oci structured error body surfaces the message`() {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{ "errors": [ { "code": "MANIFEST_UNKNOWN", "message": "manifest unknown" } ] }""",
            ),
        )

        val result = runBlocking { repo.manifest("nope:latest") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("manifest unknown"))
    }

    @Test
    fun `malformed json is returned as a failure, not thrown`() {
        server.enqueue(MockResponse().setBody("not json at all"))

        val result = runBlocking { repo.manifest("any:latest") }

        assertTrue(result.isFailure)
    }

    /** schemaVersion, mediaType and config are all in the fixture and unknown to ManifestResponse. */
    @Test
    fun `unknown fields do not break parsing`() {
        server.enqueue(MockResponse().setBody(basicManifestJson))

        assertTrue(runBlocking { repo.manifest("any:latest") }.isSuccess)
    }

    /**
     * RemoteFile.sha256 is specifically a SHA-256 (see its own KDoc); a digest under another
     * algorithm must fail loudly rather than have its hex silently treated as one.
     */
    @Test
    fun `a layer digest that is not sha256 fails the manifest`() {
        server.enqueue(
            MockResponse().setBody(
                """{ "layers": [
                    { "mediaType": "application/vnd.ollama.image.model", "size": 5,
                      "digest": "sha512:deadbeef" }
                ] }""",
            ),
        )

        val result = runBlocking { repo.manifest("weird:latest") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `an invalid baseUrl is returned as a failure, not thrown`() {
        val invalidRepo = Ollama(OkHttpClient(), baseUrl = "registry.ollama.ai")

        val result = runBlocking { invalidRepo.manifest("any:latest") }

        assertTrue(result.isFailure)
    }
}
