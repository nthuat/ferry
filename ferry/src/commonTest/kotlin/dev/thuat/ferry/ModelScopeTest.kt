package dev.thuat.ferry

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelScopeTest {

    private lateinit var queue: QueueClient
    private lateinit var repo: ModelScope

    /**
     * Shaped exactly like the live response: an envelope carrying Code/Success/Message/RequestId
     * around a Data object, itself carrying Files alongside fields this code has no use for
     * (IsVisual, LatestCommitter). A Recursive=True listing carries both the directory entry and the
     * file nested inside it, which is what makes the directory filter and the nested-path assertion
     * below mean something — verified live, a 39-entry recursive listing carries 32 blobs and 7
     * trees.
     */
    private val listingJson = """
        {
          "Code": 200,
          "Success": true,
          "Message": "",
          "RequestId": "0000-1111-2222-3333",
          "Data": {
            "Files": [
              { "Path": "text_encoder_2", "Type": "tree", "Size": 0 },
              { "Path": "text_encoder_2/config.json", "Type": "blob", "Size": 1234,
                "Sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "IsLFS": false },
              { "Path": "config.json", "Type": "blob", "Size": 659,
                "Sha256": "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1",
                "IsLFS": false },
              { "Path": "model.safetensors", "Type": "blob", "Size": 988097824,
                "Sha256": "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
                "IsLFS": true }
            ],
            "IsVisual": false,
            "LatestCommitter": { "Name": "someone", "Email": "someone@example.com" }
          }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        queue = QueueClient()
        repo = ModelScope(queue.client, baseUrl = "http://hub.test")
    }

    @AfterTest
    fun tearDown() = Unit

    @Test
    fun `manifest lists blobs and skips trees - preserving nested paths`() = runTest {
        queue.enqueue(listingJson)

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(
            listOf("text_encoder_2/config.json", "config.json", "model.safetensors"),
            manifest.files.map { it.path },
        )
    }

    @Test
    fun `sha256 is mapped from every blob - not only lfs ones`() = runTest {
        queue.enqueue(listingJson)

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(
            "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1",
            manifest.files.single { it.path == "config.json" }.sha256,
        )
        assertEquals(
            "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
            manifest.files.single { it.path == "model.safetensors" }.sha256,
        )
    }

    @Test
    fun `total bytes sums every file`() = runTest {
        queue.enqueue(listingJson)

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(1234L + 659L + 988097824L, manifest.totalBytes)
    }

    /**
     * Recursive is case-sensitive and the wrong case is silently ignored, not rejected: verified
     * live, `Recursive=True` returned 39 entries (21 nested) and `recursive=true` returned 18 with
     * none nested — HTTP 200 either way, no error. Pinned exactly, capital R and all, so a future
     * edit cannot reintroduce that truncation unnoticed.
     */
    @Test
    fun `manifest requests the listing endpoint with capital-R Recursive=True`() = runTest {
        queue.enqueue(listingJson)

        repo.manifest("owner/model")

        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * `appendPathSegmentsResolvingDots(repoId)` must add exactly one path segment when repoId has no
     * `/` to split on, not drop it or split on some other character — this code never assumes two
     * segments, and `HuggingFaceTest` pins the equivalent for the same reason.
     */
    @Test
    fun `a single-segment repo id produces one path segment - not a split or dropped id`() = runTest {
        queue.enqueue(listingJson)

        repo.manifest("bert-base-uncased")

        assertEquals(
            "/api/v1/models/bert-base-uncased/repo/files?Revision=master&Recursive=True",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * Unlike HuggingFace's old denylist, none of `?`, `#` or `&` is rejected here: `?` and `#` travel
     * through appendPathSegments and come out percent-encoded, and `&` comes out as an inert literal
     * character — a path segment has no structural meaning for `&` the way a query string does —
     * rather than any of the three reinterpreting as a query or fragment delimiter (see the KDoc on
     * `ModelScope.manifest` for why no pre-check exists). Proven here against the actual request
     * produced, not by argument — this is the same repoId shape `HuggingFaceTest`'s equivalent test
     * exercises; both adapters now send it safely.
     */
    @Test
    fun `a repo id containing url delimiters is percent-encoded rather than reshaping the request`() = runTest {
        queue.enqueue(listingJson)

        val result = repo.manifest("owner/model?recursive=false&x=1#frag")

        assertTrue(
            result.isSuccess,
            "the request must still succeed - the character is encoded, not rejected",
        )
        val recordedPath = queue.requests[0].url.encodedPathAndQuery
        assertEquals(
            1,
            recordedPath.count { it == '?' },
            "the repoId's own '?' must not open a second, earlier query string",
        )
        assertEquals(
            "Revision=master&Recursive=True",
            recordedPath.substringAfter('?'),
            "the real query must be exactly these two parameters, unclobbered by the repoId's own ? and &",
        )
        assertTrue(
            recordedPath.contains("model%3Frecursive"),
            "the repoId's '?' must be percent-encoded rather than left as a literal delimiter",
        )
    }

    /**
     * The fix for docs/known-limitations.md's "a repo id containing `..` can retarget the request":
     * [appendPathSegmentsResolvingDots] resolves ".." by popping the segment before it, so
     * `"../../etc/passwd"` pops two of `api/v1/models`'s three segments off the built listing URL
     * (leaving only `api`), landing the request at `/api/etc/passwd/repo/files` instead of failing —
     * still not `api/v1/models`, so requireWithinNamespace catches it just as it would a full pop.
     * Must be refused before the request is ever sent — asserted on the request count, not only on
     * the `Result`, since a destructive version of this bug could still return a `Result.failure`
     * after already leaking the request.
     */
    @Test
    fun `a repo id that traverses out of the models namespace is refused before any request is issued`() = runTest {
        val result = repo.manifest("../../etc/passwd")

        assertTrue(result.isFailure, "must fail rather than retarget the request")
        assertEquals(
            0,
            queue.requests.size,
            "must not spend the user's data on a request aimed outside the models namespace",
        )
    }

    /** The namespace check must refuse only a repo id that actually escapes, not an ordinary one. */
    @Test
    fun `an ordinary two-segment repo id is not rejected by the namespace check`() = runTest {
        queue.enqueue(listingJson)

        val result = repo.manifest("owner/model")

        assertTrue(result.isSuccess)
        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * The regression this closes: `requireWithinNamespace` used to compare against a literal
     * `MODELS_NAMESPACE` (`["api", "v1", "models"]`), ignoring whatever path segments `baseUrl`
     * itself already carried. A self-hosted mirror at `.../hf` — `baseUrl` is a public parameter —
     * built a perfectly correct `.../hf/api/v1/models/...` request and then had this check reject it
     * outright, for every legitimate id, every call. No test caught it because none used a
     * path-carrying `baseUrl`. Fixed by computing the namespace off `base` via `modelsNamespace(base)`
     * instead of a bare constant.
     */
    @Test
    fun `a legitimate id succeeds against a path-carrying baseUrl`() = runTest {
        val mirror = ModelScope(queue.client, baseUrl = "http://hub.test/hf")
        queue.enqueue(listingJson)

        val result = mirror.manifest("owner/model")

        assertTrue(result.isSuccess)
        assertEquals(
            "/hf/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /** The namespace check must still catch an actual escape when `baseUrl` itself carries a path. */
    @Test
    fun `a repo id that traverses out of the models namespace is still refused against a path-carrying baseUrl`() =
        runTest {
            val mirror = ModelScope(queue.client, baseUrl = "http://hub.test/hf")

            val result = mirror.manifest("../../etc/passwd")

            assertTrue(result.isFailure)
            assertEquals(0, queue.requests.size)
        }

    @Test
    fun `a custom revision is used in the listing url in place of the master default`() = runTest {
        queue.enqueue(listingJson)
        val pinned = ModelScope(queue.client, baseUrl = "http://hub.test", revision = "v1.0")

        pinned.manifest("owner/model")

        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=v1.0&Recursive=True",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * The file path travels in a query parameter here, not the URL path like HuggingFace, so a
     * character `&`, `#`, `+` or a space would corrupt the request or silently fetch the wrong file
     * if interpolated into a string. Built with [URLBuilder]'s `parameters.append` instead, which
     * round-trips exactly — asserted here by parsing the produced URL back and reading the parameter
     * rather than by matching a specific percent-encoding scheme.
     */
    @Test
    fun `a file path containing characters needing encoding produces a correctly encoded url`() = runTest {
        val trickyJson = """
            { "Code": 200, "Success": true, "Data": { "Files": [
              { "Path": "weird file&name+v2.bin", "Type": "blob", "Size": 5, "Sha256": "deadbeef" }
            ] } }
        """.trimIndent()
        queue.enqueue(trickyJson)

        val manifest = repo.manifest("owner/model").getOrThrow()

        val url = Url(manifest.files.single().url)
        assertEquals("weird file&name+v2.bin", url.parameters["FilePath"])
    }

    @Test
    fun `each file carries a resolved download url built from the repo id and revision`() = runTest {
        queue.enqueue(listingJson)

        val manifest = repo.manifest("owner/model").getOrThrow()
        val url = Url(manifest.files.single { it.path == "config.json" }.url)

        assertEquals(listOf("api", "v1", "models", "owner", "model", "repo"), url.segments)
        assertEquals("master", url.parameters["Revision"])
        assertEquals("config.json", url.parameters["FilePath"])
    }

    /**
     * Unlike HuggingFace, a nested file's own "/" travels through a query parameter here
     * (`FilePath`), not a path segment, so Ktor is free to leave it literal or percent-encode it —
     * a `/` has no structural meaning inside a query value either way. What actually matters is the
     * round trip, not the encoding choice: `parameters["FilePath"]` must hand back the exact
     * original nested path, not truncate at the first `/` or come back mangled. Asserted against the
     * contract, not a specific encoding, using the nested blob already in `listingJson`
     * (`text_encoder_2/config.json`).
     */
    @Test
    fun `a nested file path round-trips through the FilePath query parameter unmangled`() = runTest {
        queue.enqueue(listingJson)

        val manifest = repo.manifest("owner/model").getOrThrow()
        val nested = manifest.files.single { it.path == "text_encoder_2/config.json" }

        assertEquals(
            "text_encoder_2/config.json",
            Url(nested.url).parameters["FilePath"],
        )
    }

    @Test
    fun `Success false in a 200 response is a failure carrying the message`() = runTest {
        queue.enqueue("""{ "Code": 400, "Success": false, "Message": "Model not found." }""")

        val result = repo.manifest("nope/nope")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Model not found."))
    }

    @Test
    fun `an http error is returned as a failure`() = runTest {
        queue.enqueue(status = HttpStatusCode.NotFound)

        val result = repo.manifest("nope/nope")

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed json is returned as a failure - not thrown`() = runTest {
        queue.enqueue("not json at all")

        val result = repo.manifest("any/repo")

        assertTrue(result.isFailure)
    }

    /** IsVisual, LatestCommitter, RequestId and IsLFS are all in the fixture and unknown to the parser. */
    @Test
    fun `unknown fields do not break parsing`() = runTest {
        queue.enqueue(listingJson)

        assertTrue(repo.manifest("any/repo").isSuccess)
    }

    @Test
    fun `an invalid baseUrl is returned as a failure - not thrown`() = runTest {
        val invalidRepo = ModelScope(queue.client, baseUrl = "modelscope.cn")

        val result = invalidRepo.manifest("any/repo")

        assertTrue(result.isFailure)
    }
}
