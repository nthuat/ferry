package dev.thuat.ferry

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaTest {

    private lateinit var queue: QueueClient
    private lateinit var repo: Ollama

    /**
     * Shaped exactly like the live response: a config blob alongside four layers, each carrying
     * nothing but mediaType/size/digest - no filename anywhere. Digests are real (but arbitrary)
     * SHA-256 values, 64 lowercase hex characters, matching what Ollama.manifest now requires.
     */
    private val basicManifestJson = """
        { "schemaVersion": 2, "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
          "config": { "mediaType": "application/vnd.docker.container.image.v1+json", "size": 490,
                       "digest": "sha256:a547ede7297270a575049cbc4c9267378b77adac5287396d9754a294f2a0d86e" },
          "layers": [
            { "mediaType": "application/vnd.ollama.image.model", "size": 397807936,
              "digest": "sha256:0ee70bd579839c5691f0ed80505934ecc07f8894cd5322fe0ecc2ea4a5a3b469" },
            { "mediaType": "application/vnd.ollama.image.system", "size": 68,
              "digest": "sha256:889b19303e4bb733f80c1942dc750396ede241118913efac28b88478d0b3f38d" },
            { "mediaType": "application/vnd.ollama.image.template", "size": 1482,
              "digest": "sha256:a6dcd10d5555d85a45fec20f302760b9ecb478ceca43af8a9747652204d4cd28" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 11343,
              "digest": "sha256:d2a0ed60ae6e544e2bd046a702276e6a628183243e81db30581981d44719bd2f" }
          ] }
    """.trimIndent()

    /**
     * llama3.2-vision:11b's real shape, verified live: five layers, and two of them - both
     * "image.license" - share a mediaType (but not a digest). Naming a file by mediaType suffix
     * alone collides here; this fixture is what a naive suffix-only implementation must fail
     * against (see the distinct-paths and total-bytes tests below), and what this adapter must keep
     * passing forever.
     */
    private val visionCollisionJson = """
        { "schemaVersion": 2, "layers": [
            { "mediaType": "application/vnd.ollama.image.model", "size": 6433703168,
              "digest": "sha256:97e9d4a64e65dca698a4d49ec78a9cf8d6f397310d823338267e5921071a26be" },
            { "mediaType": "application/vnd.ollama.image.template", "size": 1565,
              "digest": "sha256:103a38cdb19f1b687ea4bfcf78a4c2a596af884727b1c4401e972a0d53d250a4" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 6021,
              "digest": "sha256:0b05627c0bef591a71788ba49f598908ec344c2c8bf033e0bb6664723e5e66f9" },
            { "mediaType": "application/vnd.ollama.image.license", "size": 7680,
              "digest": "sha256:f5043a7d9bf4e6e8eb685cfa363f98ce099799d64ee8acbe2b63dafd642f0669" },
            { "mediaType": "application/vnd.ollama.image.params", "size": 59,
              "digest": "sha256:ad226d5d91591cc0db6f55d11bb3d36d8dd0295900df5ce2abe7f2abcdf014ff" }
          ] }
    """.trimIndent()

    /**
     * Two layers that share both mediaType and digest - the same content, listed twice, not two
     * distinct files. `files` is built by mapping every layer unconditionally, so without dedup this
     * would count 5_000_000 bytes twice.
     */
    private val exactDuplicateLayerJson = """
        { "layers": [
            { "mediaType": "application/vnd.ollama.image.model", "size": 5000000,
              "digest": "sha256:0ee70bd579839c5691f0ed80505934ecc07f8894cd5322fe0ecc2ea4a5a3b469" },
            { "mediaType": "application/vnd.ollama.image.model", "size": 5000000,
              "digest": "sha256:0ee70bd579839c5691f0ed80505934ecc07f8894cd5322fe0ecc2ea4a5a3b469" }
          ] }
    """.trimIndent()

    /**
     * An empty "system" prompt and an empty "params" file hash identically - both the well-known
     * SHA-256 of the empty string - despite being two different, real files. Dedup keyed on digest
     * instead of path would wrongly collapse these into one.
     */
    private val sameDigestDifferentSuffixJson = """
        { "layers": [
            { "mediaType": "application/vnd.ollama.image.system", "size": 0,
              "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" },
            { "mediaType": "application/vnd.ollama.image.params", "size": 0,
              "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" }
          ] }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        queue = QueueClient()
        repo = Ollama(queue.client, baseUrl = "http://hub.test")
    }

    @AfterTest
    fun tearDown() = Unit

    @Test
    fun `sha256 is the digest with its sha256 prefix stripped`() = runTest {
        queue.enqueue(basicManifestJson)

        val manifest = repo.manifest("qwen2.5:0.5b").getOrThrow()

        assertEquals(
            "0ee70bd579839c5691f0ed80505934ecc07f8894cd5322fe0ecc2ea4a5a3b469",
            manifest.files.single { it.path.startsWith("model-") }.sha256,
        )
        assertEquals(
            "d2a0ed60ae6e544e2bd046a702276e6a628183243e81db30581981d44719bd2f",
            manifest.files.single { it.path.startsWith("license-") }.sha256,
        )
    }

    @Test
    fun `size is mapped from each layer`() = runTest {
        queue.enqueue(basicManifestJson)

        val manifest = repo.manifest("qwen2.5:0.5b").getOrThrow()

        assertEquals(397807936L, manifest.files.single { it.path.startsWith("model-") }.sizeBytes)
        assertEquals(11343L, manifest.files.single { it.path.startsWith("license-") }.sizeBytes)
    }

    @Test
    fun `total bytes sums every layer`() = runTest {
        queue.enqueue(basicManifestJson)

        val manifest = repo.manifest("qwen2.5:0.5b").getOrThrow()

        assertEquals(397807936L + 68L + 1482L + 11343L, manifest.totalBytes)
    }

    /**
     * The design call this adapter's KDoc defends: config describes the layers, it is not one of
     * them. Pinned so a future edit cannot fold it back into `files` unnoticed - four layers in the
     * fixture must mean four files, and none of them the config's own digest.
     */
    @Test
    fun `config is not included among the downloaded files`() = runTest {
        queue.enqueue(basicManifestJson)

        val manifest = repo.manifest("qwen2.5:0.5b").getOrThrow()

        assertEquals(4, manifest.files.size)
        assertTrue(
            manifest.files.none {
                it.sha256 == "a547ede7297270a575049cbc4c9267378b77adac5287396d9754a294f2a0d86e"
            },
        )
    }

    @Test
    fun `each file carries a blob url built from the qualified name and digest`() = runTest {
        queue.enqueue(basicManifestJson)

        val manifest = repo.manifest("qwen2.5:0.5b").getOrThrow()
        val model = manifest.files.single { it.path.startsWith("model-") }

        assertEquals(
            listOf(
                "v2", "library", "qwen2.5", "blobs",
                "sha256:0ee70bd579839c5691f0ed80505934ecc07f8894cd5322fe0ecc2ea4a5a3b469",
            ),
            Url(model.url).segments,
        )
    }

    /**
     * The whole reason this adapter is interesting: llama3.2-vision:11b's two "image.license"
     * layers must not collapse onto one path. Two different digests here, so distinctBy in
     * [Ollama.manifest] keeps both - reverting the digest suffix back to a bare mediaType suffix
     * (`path = layerSuffix(layer.mediaType)`) makes this go red: confirmed by hand against this
     * exact fixture before committing.
     */
    @Test
    fun `two layers of the same mediaType produce distinct paths, not a collision`() = runTest {
        queue.enqueue(visionCollisionJson)

        val manifest = repo.manifest("llama3.2-vision:11b").getOrThrow()

        assertEquals(5, manifest.files.size)
        assertEquals(5, manifest.files.map { it.path }.distinct().size)
        assertEquals(
            listOf(
                "model-97e9d4a64e65dca698a4d49ec78a9cf8d6f397310d823338267e5921071a26be",
                "template-103a38cdb19f1b687ea4bfcf78a4c2a596af884727b1c4401e972a0d53d250a4",
                "license-0b05627c0bef591a71788ba49f598908ec344c2c8bf033e0bb6664723e5e66f9",
                "license-f5043a7d9bf4e6e8eb685cfa363f98ce099799d64ee8acbe2b63dafd642f0669",
                "params-ad226d5d91591cc0db6f55d11bb3d36d8dd0295900df5ce2abe7f2abcdf014ff",
            ),
            manifest.files.map { it.path },
        )
    }

    /** The same fixture's total must include both license layers, not silently drop one. */
    @Test
    fun `total bytes across the colliding layers is not short by the dropped one`() = runTest {
        queue.enqueue(visionCollisionJson)

        val manifest = repo.manifest("llama3.2-vision:11b").getOrThrow()

        assertEquals(6433703168L + 1565L + 6021L + 7680L + 59L, manifest.totalBytes)
    }

    /**
     * The other edge the suffix+digest scheme has to get right: two layers sharing *both* suffix
     * and digest are the same content listed twice, not two files. Every layer is mapped
     * unconditionally in [Ollama.manifest], so without `distinctBy { it.path }` this would produce
     * two RemoteFiles at an identical path and double-count the bytes.
     *
     * Revert-checked: removing `.distinctBy { it.path }` makes this fail (2 files, 10_000_000 total)
     * before restoring it.
     */
    @Test
    fun `two layers with identical mediaType and digest dedupe to one file, not double counted`() = runTest {
        queue.enqueue(exactDuplicateLayerJson)

        val manifest = repo.manifest("dup:latest").getOrThrow()

        assertEquals(1, manifest.files.size)
        assertEquals(5000000L, manifest.totalBytes)
    }

    /**
     * The dedup above must be keyed on path (suffix + digest together), not on digest alone: two
     * different suffixes can legitimately share a digest - an empty "system" prompt and an empty
     * "params" file both hash to the empty-string SHA-256 - and are two real, distinct files.
     */
    @Test
    fun `two layers with the same digest but different mediaType stay two distinct files`() = runTest {
        queue.enqueue(sameDigestDifferentSuffixJson)

        val manifest = repo.manifest("shared-digest:latest").getOrThrow()

        assertEquals(2, manifest.files.size)
        assertEquals(
            listOf(
                "system-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "params-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            manifest.files.map { it.path },
        )
    }

    @Test
    fun `manifest requests the tag given in the repo id`() = runTest {
        queue.enqueue(basicManifestJson)

        repo.manifest("qwen2.5:0.5b")

        assertEquals("/v2/library/qwen2.5/manifests/0.5b", queue.requests[0].url.encodedPath)
    }

    /** "a bare name with no tag, which conventionally means latest" - stated in Ollama's own KDoc. */
    @Test
    fun `a bare repo id with no tag defaults to latest`() = runTest {
        queue.enqueue(basicManifestJson)

        repo.manifest("llava")

        assertEquals("/v2/library/llava/manifests/latest", queue.requests[0].url.encodedPath)
    }

    /** A repoId that already names its own namespace must not also get "library/" prepended. */
    @Test
    fun `a repo id that already carries a namespace is not re-prefixed with library`() = runTest {
        queue.enqueue(basicManifestJson)

        repo.manifest("someuser/custom-model:v1")

        assertEquals("/v2/someuser/custom-model/manifests/v1", queue.requests[0].url.encodedPath)
    }

    /** The Accept header is required - verified live, its absence gets a different response shape. */
    @Test
    fun `manifest request carries the required docker manifest v2 accept header`() = runTest {
        queue.enqueue(basicManifestJson)

        repo.manifest("qwen2.5:0.5b")

        assertEquals(
            "application/vnd.docker.distribution.manifest.v2+json",
            queue.requests[0].headers["Accept"],
        )
    }

    /**
     * The same fix as HuggingFace/ModelScope's own ".." entries in docs/known-limitations.md, applied
     * here: [appendPathSegmentsResolvingDots] resolves ".." by popping the segment before it, so this
     * would otherwise land the request outside the "v2" namespace instead of failing. Asserted on the
     * request count, not only the Result, since a destructive version of this bug could still fail
     * after already leaking the request.
     */
    @Test
    fun `a repo id that traverses out of the registry namespace is refused before any request is issued`() =
        runTest {
            val result = repo.manifest("../../etc/passwd")

            assertTrue(result.isFailure, "must fail rather than retarget the request")
            assertEquals(0, queue.requests.size)
        }

    /**
     * The regression HuggingFace and ModelScope each closed once (docs/known-limitations.md):
     * `requireWithinNamespace` must assert against a namespace computed off `base`, not a bare
     * constant, or a self-hosted/mirrored registry at a non-root path fails every legitimate call.
     * `registryNamespace` already computes off `base` - this is the test that would have caught it
     * if it hadn't.
     */
    @Test
    fun `a legitimate id succeeds against a path-carrying baseUrl`() = runTest {
        val mirror = Ollama(queue.client, baseUrl = "http://hub.test/hf")
        queue.enqueue(basicManifestJson)

        val result = mirror.manifest("qwen2.5:0.5b")

        assertTrue(result.isSuccess)
        assertEquals(
            "/hf/v2/library/qwen2.5/manifests/0.5b",
            queue.requests[0].url.encodedPath,
        )
    }

    /** The namespace check must still catch an actual escape when baseUrl itself carries a path. */
    @Test
    fun `a repo id that traverses out of the registry namespace is still refused against a path-carrying baseUrl`() =
        runTest {
            val mirror = Ollama(queue.client, baseUrl = "http://hub.test/hf")

            val result = mirror.manifest("../../etc/passwd")

            assertTrue(result.isFailure)
            assertEquals(0, queue.requests.size)
        }

    @Test
    fun `an http error is returned as a failure`() = runTest {
        queue.enqueue(status = HttpStatusCode.NotFound)

        val result = repo.manifest("nope:latest")

        assertTrue(result.isFailure)
    }

    /** "an OCI registry can also return a structured error body" - surfaced when present. */
    @Test
    fun `an http error with an oci structured error body surfaces the message`() = runTest {
        queue.enqueue(
            body = """{ "errors": [ { "code": "MANIFEST_UNKNOWN", "message": "manifest unknown" } ] }""",
            status = HttpStatusCode.NotFound,
        )

        val result = repo.manifest("nope:latest")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("manifest unknown"))
    }

    @Test
    fun `malformed json is returned as a failure, not thrown`() = runTest {
        queue.enqueue("not json at all")

        val result = repo.manifest("any:latest")

        assertTrue(result.isFailure)
    }

    /** schemaVersion, mediaType and config are all in the fixture and unknown to ManifestResponse. */
    @Test
    fun `unknown fields do not break parsing`() = runTest {
        queue.enqueue(basicManifestJson)

        assertTrue(repo.manifest("any:latest").isSuccess)
    }

    /**
     * RemoteFile.sha256 is specifically a SHA-256 (see its own KDoc); a digest under another
     * algorithm must fail loudly rather than have its hex silently treated as one.
     */
    @Test
    fun `a layer digest that is not sha256 fails the manifest`() = runTest {
        queue.enqueue(
            """{ "layers": [
                { "mediaType": "application/vnd.ollama.image.model", "size": 5,
                  "digest": "sha512:deadbeef" }
            ] }""",
        )

        val result = repo.manifest("weird:latest")

        assertTrue(result.isFailure)
    }

    /**
     * The "sha256:" prefix alone isn't sufficient - the remainder must actually be 64 lowercase hex
     * characters, or it lands unvalidated in RemoteFile.path and RemoteFile.sha256 both.
     */
    @Test
    fun `a layer digest with the sha256 prefix but malformed hex fails the manifest`() = runTest {
        queue.enqueue(
            """{ "layers": [
                { "mediaType": "application/vnd.ollama.image.model", "size": 5,
                  "digest": "sha256:not-valid-hex" }
            ] }""",
        )

        val result = repo.manifest("weird:latest")

        assertTrue(result.isFailure)
    }

    @Test
    fun `an invalid baseUrl is returned as a failure, not thrown`() = runTest {
        val invalidRepo = Ollama(queue.client, baseUrl = "registry.ollama.ai")

        val result = invalidRepo.manifest("any:latest")

        assertTrue(result.isFailure)
    }
}
