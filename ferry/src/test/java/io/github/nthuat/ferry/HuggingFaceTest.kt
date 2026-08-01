package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HuggingFaceTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: HuggingFace

    /**
     * Shaped exactly like the live response, including a field this code does not know about.
     *
     * A recursive listing carries both the directory entry and the files inside it, which is what
     * makes the directory filter and the path assertions below mean something: a repo of 57 files
     * across unet/, vae/ and onnx/ lists 10 without recursion.
     */
    private val treeJson = """
        [
          { "type": "directory", "path": "onnx" },
          { "type": "file", "path": "onnx/model.onnx", "size": 1234 },
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

        assertEquals(
            listOf("onnx/model.onnx", "config.json", "model.safetensors"),
            manifest.files.map { it.path },
        )
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

        assertEquals(1234L + 659L + 988097824L, manifest.totalBytes)
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

    /**
     * The tree endpoint is non-recursive by default. Verified live against
     * stabilityai/stable-diffusion-xl-base-1.0: 10 entries without `recursive=true`, 57 with it.
     * Without it Ferry downloads the top level, commits, and reports a complete model.
     */
    @Test
    fun `manifest requests the tree endpoint recursively`() {
        server.enqueue(MockResponse().setBody(treeJson))

        runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }

        assertEquals(
            "/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main?recursive=true",
            server.takeRequest().path,
        )
    }

    /**
     * The repo id is interpolated into a URL that carries a query string, so a delimiter inside it
     * could reshape the request rather than name a repo — "a/b?recursive=false" would turn recursion
     * off again. Refused before any request is made.
     */
    @Test
    fun `a repo id containing a url delimiter is a failure and makes no request`() {
        val result = runBlocking { repo.manifest("owner/model?recursive=false") }

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
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

    /**
     * A page caps at 1000 entries and points at the next with a Link header. Verified live:
     * google/gemma-scope-9b-pt-res answers 1000 entries with a rel="next" link, and that link
     * answers 724 with none — 1724 in total. Consuming one page lists a fraction of the repo,
     * commits it, and reports a complete model, with totalBytes short by the difference.
     */
    @Test
    fun `a paged listing accumulates every page`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/page2")}>; rel=\"next\""),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
        assertEquals("totalBytes must sum every page", 42L, manifest.totalBytes)
    }

    /**
     * NEXT_REL used to be matched with `containsMatchIn` against the whole Link segment, URL
     * included — the shape from docs/known-limitations.md is `<https://huggingface.co/api/models/
     * x/tree/main?rel=next>; rel="prev"` — so a `prev` link whose own URL happens to carry an
     * ordinary `?rel=next` query parameter was misidentified as the next page.
     *
     * Reproduced here on the mock server rather than the literal huggingface.co URL so the guard
     * under test is the regex anchor and not the separate same-origin check: production bounds the
     * live version of this bug to an out-of-turn *same-origin* fetch, so the test has to actually be
     * same-origin to exercise that path. A second page is enqueued so a regression that does chase
     * the link gets an immediate, deterministic answer instead of this test hanging on an empty
     * response queue.
     */
    @Test
    fun `a prev link whose url contains rel=next in its own query string is not treated as next`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/page1?rel=next")}>; rel=\"prev\""),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(
            "a prev link must never be followed as though it were next",
            listOf("page1.bin"),
            manifest.files.map { it.path },
        )
        assertEquals("the prev link's own url must never be requested", 1, server.requestCount)
    }

    /**
     * `;` is a legal sub-delimiter inside a URL's own path, not only a parameter separator, so
     * anchoring the match to "right after any `;`" was not enough on its own: `<.../x;rel=next>;
     * rel="prev"` still has a `;` immediately before the impostor, with no `?` in sight. Excluding
     * the URI-Reference from the match entirely — matching only what follows its closing `>` — is
     * what actually closes this, regardless of whether the impostor sits in a query or a bare path.
     */
    @Test
    fun `a prev link with a semicolon before rel=next inside its own url is not treated as next`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/page1;rel=next")}>; rel=\"prev\""),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(
            "a prev link must never be followed as though it were next",
            listOf("page1.bin"),
            manifest.files.map { it.path },
        )
        assertEquals("the prev link's own url must never be requested", 1, server.requestCount)
    }

    /**
     * Scoping the match to the text after `>` (the fix above) closed the query-string and
     * semicolon-in-URL leaks, but left the anchor's `(?:^|;)` alternative alone. Tested against the
     * whole segment, `^` was practically unreachable — every real segment starts `<url>…`, so only
     * the `;` branch ever fired. Once the URL was out of scope, `^` started meaning "right after
     * `>`, with no separator at all" — a position the Link grammar (RFC 8288) never permits, because
     * every parameter, including the first, is preceded by a real `;`. A segment missing that
     * separator has no valid parameter there at all, but `^` let it match as though it did: this
     * segment's real, well-formed attribute is `rel="prev"`, yet the fake `rel=next` sitting directly
     * against the closing `>` was read as the next page.
     */
    @Test
    fun `a link with no separator before a fake rel=next is not treated as next`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/prev")}>rel=next; rel=\"prev\">"),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(
            "a segment whose real attribute is rel=\"prev\" must not be treated as next",
            listOf("page1.bin"),
            manifest.files.map { it.path },
        )
        assertEquals("no second page may be requested", 1, server.requestCount)
    }

    /**
     * The next-page URL is chosen by the server — the one request target in this adapter that comes
     * from a response rather than from the caller. Following it as given would let a compromised hub
     * aim the host app's own OkHttpClient at any address it names, an internal one included.
     *
     * The off-host URL points at a *reachable* server — this same one under its other loopback name
     * — and a second page is enqueued for it. An unreachable host like "evil.test" would make this
     * test pass on a DNS failure whether or not the guard exists; here, removing the guard fetches
     * the second page and the request count says so.
     */
    @Test
    fun `a next page url on another host is refused and never requested`() {
        val otherName = if (server.hostName == "localhost") "127.0.0.1" else "localhost"
        val offHost = server.url("/page2").newBuilder().host(otherName).build()
        server.enqueue(
            MockResponse().setBody(treeJson).addHeader("Link", "<$offHost>; rel=\"next\""),
        )
        server.enqueue(MockResponse().setBody(treeJson))

        val result = runBlocking { repo.manifest("owner/model") }

        assertTrue(result.isFailure)
        assertEquals("the off-host page must never be requested", 1, server.requestCount)
    }

    /**
     * Repeated `Link:` fields are equivalent to one comma-joined field (RFC 7230 3.2.2), so a
     * response is free to split next and prev across two of them.
     *
     * The ordering here is load-bearing and not arbitrary: OkHttp's `Headers.get` scans backwards
     * and returns the *last* matching field, so `next` is sent first and `prev` second. Reversed,
     * a single-field read would happen to pick the next link up and this test would pass against
     * the very code it exists to fail.
     */
    @Test
    fun `a next link split across repeated Link fields still paginates`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/page2")}>; rel=\"next\"")
                .addHeader("Link", "<${server.url("/prev")}>; rel=\"prev\""),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
    }

    /** RFC 8288 permits an unquoted rel. Reading it as "last page" is a silent truncation. */
    @Test
    fun `an unquoted rel next still paginates`() {
        server.enqueue(
            MockResponse()
                .setBody("""[ { "type": "file", "path": "page1.bin", "size": 10 } ]""")
                .addHeader("Link", "<${server.url("/page2")}>; rel=next"),
        )
        server.enqueue(
            MockResponse().setBody("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]"""),
        )

        val manifest = runBlocking { repo.manifest("owner/model") }.getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
    }

    /**
     * Same host, different port. Harmless against the default huggingface.co, but a baseUrl with an
     * explicit port — a self-hosted mirror — would otherwise follow a next link to any port on that
     * machine. The other server is real and holds an enqueued page, so removing the guard fetches
     * it and the request count says so, rather than the test passing on a refused connection.
     */
    @Test
    fun `a next page url on another port is refused and never requested`() {
        val otherPort = MockWebServer().apply { start() }
        try {
            otherPort.enqueue(MockResponse().setBody(treeJson))
            server.enqueue(
                MockResponse()
                    .setBody(treeJson)
                    .addHeader("Link", "<${otherPort.url("/page2")}>; rel=\"next\""),
            )

            val result = runBlocking { repo.manifest("owner/model") }

            assertTrue(result.isFailure)
            assertEquals("the off-port page must never be requested", 0, otherPort.requestCount)
        } finally {
            otherPort.shutdown()
        }
    }

    /** A cursor pointing back at its own page would otherwise loop until the process died. */
    @Test
    fun `a listing whose cursor never ends fails at the page cap`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setBody(treeJson)
                .addHeader("Link", "<${server.url("/forever")}>; rel=\"next\"")
        }

        val result = runBlocking { repo.manifest("owner/model") }

        assertTrue(result.isFailure)
        assertEquals("must stop at the cap rather than keep asking", 100, server.requestCount)
    }

    @Test
    fun `an invalid baseUrl is returned as a failure, not thrown`() {
        val invalidRepo = HuggingFace(OkHttpClient(), baseUrl = "huggingface.co")

        val result = runBlocking { invalidRepo.manifest("any/repo") }

        assertTrue(result.isFailure)
    }
}
