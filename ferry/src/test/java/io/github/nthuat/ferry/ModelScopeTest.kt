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

class ModelScopeTest {

    private lateinit var server: MockWebServer
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

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = ModelScope(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest lists blobs and skips trees, preserving nested paths`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(
            listOf("text_encoder_2/config.json", "config.json", "model.safetensors"),
            manifest.files.map { it.path },
        )
    }

    @Test
    fun `sha256 is mapped from every blob, not only lfs ones`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

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
    fun `total bytes sums every file`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(1234L + 659L + 988097824L, manifest.totalBytes)
    }

    /**
     * Recursive is case-sensitive and the wrong case is silently ignored, not rejected: verified
     * live, `Recursive=True` returned 39 entries (21 nested) and `recursive=true` returned 18 with
     * none nested — HTTP 200 either way, no error. Pinned exactly, capital R and all, so a future
     * edit cannot reintroduce that truncation unnoticed.
     */
    @Test
    fun `manifest requests the listing endpoint with capital-R Recursive=True`() {
        server.enqueue(MockResponse().setBody(listingJson))

        runBlocking { repo.manifest("owner/model") }

        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            server.takeRequest().path,
        )
    }

    /**
     * `addPathSegments(repoId)` must add exactly one path segment when repoId has no `/` to split
     * on, not drop it or split on some other character — this code never assumes two segments, and
     * `HuggingFaceTest` pins the equivalent for the same reason.
     */
    @Test
    fun `a single-segment repo id produces one path segment, not a split or dropped id`() {
        server.enqueue(MockResponse().setBody(listingJson))

        runBlocking { repo.manifest("bert-base-uncased") }

        assertEquals(
            "/api/v1/models/bert-base-uncased/repo/files?Revision=master&Recursive=True",
            server.takeRequest().path,
        )
    }

    /**
     * Unlike HuggingFace's old denylist, none of `?`, `#` or `&` is rejected here: `?` and `#` travel
     * through addPathSegments and come out percent-encoded, and `&` comes out as an inert literal
     * character — a path segment has no structural meaning for `&` the way a query string does —
     * rather than any of the three reinterpreting as a query or fragment delimiter (see the KDoc on
     * `ModelScope.manifest` for why no pre-check exists). Proven here against the actual request
     * produced, not by argument — this is the same repoId shape `HuggingFaceTest`'s equivalent test
     * exercises; both adapters now send it safely.
     */
    @Test
    fun `a repo id containing url delimiters is percent-encoded rather than reshaping the request`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val result = runBlocking { repo.manifest("owner/model?recursive=false&x=1#frag") }

        assertTrue(
            "the request must still succeed - the character is encoded, not rejected",
            result.isSuccess,
        )
        val recordedPath = server.takeRequest().path!!
        assertEquals(
            "the repoId's own '?' must not open a second, earlier query string",
            1,
            recordedPath.count { it == '?' },
        )
        assertEquals(
            "the real query must be exactly these two parameters, unclobbered by the repoId's own ? and &",
            "Revision=master&Recursive=True",
            recordedPath.substringAfter('?'),
        )
        assertTrue(
            "the repoId's '?' must be percent-encoded rather than left as a literal delimiter",
            recordedPath.contains("model%3Frecursive"),
        )
    }

    /**
     * The fix for docs/known-limitations.md's "a repo id containing `..` can retarget the request":
     * addPathSegments resolves ".." by popping the segment before it, so `"../../etc/passwd"` pops
     * two of `api/v1/models`'s three segments off the built listing URL (leaving only `api`), landing
     * the request at `/api/etc/passwd/repo/files` instead of failing — still not `api/v1/models`, so
     * requireWithinNamespace catches it just as it would a full pop. Must be refused before the
     * request is ever sent — asserted on `server.requestCount`, not only on the `Result`, since a
     * destructive version of this bug could still return a `Result.failure` after already leaking the
     * request.
     */
    @Test
    fun `a repo id that traverses out of the models namespace is refused before any request is issued`() {
        val result = runBlocking { repo.manifest("../../etc/passwd") }

        assertTrue("must fail rather than retarget the request", result.isFailure)
        assertEquals(
            "must not spend the user's data on a request aimed outside the models namespace",
            0,
            server.requestCount,
        )
    }

    /** The namespace check must refuse only a repo id that actually escapes, not an ordinary one. */
    @Test
    fun `an ordinary two-segment repo id is not rejected by the namespace check`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val result = runBlocking { repo.manifest("owner/model") }

        assertTrue(result.isSuccess)
        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            server.takeRequest().path,
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
    fun `a legitimate id succeeds against a path-carrying baseUrl`() {
        val mirror = ModelScope(OkHttpClient(), baseUrl = server.url("/hf").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody(listingJson))

        val result = runBlocking { mirror.manifest("owner/model") }

        assertTrue(result.isSuccess)
        assertEquals(
            "/hf/api/v1/models/owner/model/repo/files?Revision=master&Recursive=True",
            server.takeRequest().path,
        )
    }

    /** The namespace check must still catch an actual escape when `baseUrl` itself carries a path. */
    @Test
    fun `a repo id that traverses out of the models namespace is still refused against a path-carrying baseUrl`() {
        val mirror = ModelScope(OkHttpClient(), baseUrl = server.url("/hf").toString().trimEnd('/'))

        val result = runBlocking { mirror.manifest("../../etc/passwd") }

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a custom revision is used in the listing url in place of the master default`() {
        server.enqueue(MockResponse().setBody(listingJson))
        val pinned = ModelScope(
            OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            revision = "v1.0",
        )

        runBlocking { pinned.manifest("owner/model") }

        assertEquals(
            "/api/v1/models/owner/model/repo/files?Revision=v1.0&Recursive=True",
            server.takeRequest().path,
        )
    }

    /**
     * The file path travels in a query parameter here, not the URL path like HuggingFace, so a
     * character `&`, `#`, `+` or a space would corrupt the request or silently fetch the wrong file
     * if interpolated into a string. Built with HttpUrl.Builder instead, which round-trips exactly —
     * asserted here by parsing the produced URL back and reading the parameter rather than by
     * matching a specific percent-encoding scheme.
     */
    @Test
    fun `a file path containing characters needing encoding produces a correctly encoded url`() {
        val trickyJson = """
            { "Code": 200, "Success": true, "Data": { "Files": [
              { "Path": "weird file&name+v2.bin", "Type": "blob", "Size": 5, "Sha256": "deadbeef" }
            ] } }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(trickyJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        val url = manifest.files.single().url.toHttpUrl()
        assertEquals("weird file&name+v2.bin", url.queryParameter("FilePath"))
    }

    @Test
    fun `each file carries a resolved download url built from the repo id and revision`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()
        val url = manifest.files.single { it.path == "config.json" }.url.toHttpUrl()

        assertEquals(listOf("api", "v1", "models", "owner", "model", "repo"), url.pathSegments)
        assertEquals("master", url.queryParameter("Revision"))
        assertEquals("config.json", url.queryParameter("FilePath"))
    }

    /**
     * Unlike HuggingFace, a nested file's own "/" travels through a query parameter here
     * (`FilePath`), not a path segment, so okhttp is free to leave it literal or percent-encode it —
     * a `/` has no structural meaning inside a query value either way. What actually matters is the
     * round trip, not the encoding choice: `queryParameter("FilePath")` must hand back the exact
     * original nested path, not truncate at the first `/` or come back mangled. Asserted against the
     * contract, not a specific encoding, using the nested blob already in `listingJson`
     * (`text_encoder_2/config.json`).
     */
    @Test
    fun `a nested file path round-trips through the FilePath query parameter unmangled`() {
        server.enqueue(MockResponse().setBody(listingJson))

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()
        val nested = manifest.files.single { it.path == "text_encoder_2/config.json" }

        assertEquals(
            "text_encoder_2/config.json",
            nested.url.toHttpUrl().queryParameter("FilePath"),
        )
    }

    @Test
    fun `Success false in a 200 response is a failure carrying the message`() {
        server.enqueue(
            MockResponse().setBody(
                """{ "Code": 400, "Success": false, "Message": "Model not found." }""",
            ),
        )

        val result = runBlocking { repo.manifest("nope/nope") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Model not found."))
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

    /** IsVisual, LatestCommitter, RequestId and IsLFS are all in the fixture and unknown to the parser. */
    @Test
    fun `unknown fields do not break parsing`() {
        server.enqueue(MockResponse().setBody(listingJson))

        assertTrue(runBlocking { repo.manifest("any/repo") }.isSuccess)
    }

    @Test
    fun `an invalid baseUrl is returned as a failure, not thrown`() {
        val invalidRepo = ModelScope(OkHttpClient(), baseUrl = "modelscope.cn")

        val result = runBlocking { invalidRepo.manifest("any/repo") }

        assertTrue(result.isFailure)
    }
}
