package dev.thuat.ferry

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuggingFaceTest {

    private lateinit var queue: QueueClient
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

    @BeforeTest
    fun setUp() {
        queue = QueueClient()
        repo = HuggingFace(queue.client, baseUrl = "http://hub.test")
    }

    @AfterTest
    fun tearDown() = Unit

    @Test
    fun `manifest lists files and skips directories`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()

        assertEquals(
            listOf("onnx/model.onnx", "config.json", "model.safetensors"),
            manifest.files.map { it.path },
        )
    }

    @Test
    fun `sha256 comes from lfs oid, not the top level git oid`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()
        val weights = manifest.files.single { it.path == "model.safetensors" }

        assertEquals(
            "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
            weights.sha256,
        )
    }

    @Test
    fun `a small non-lfs file has no sha256`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()

        assertNull(manifest.files.single { it.path == "config.json" }.sha256)
    }

    @Test
    fun `total bytes sums every file`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()

        assertEquals(1234L + 659L + 988097824L, manifest.totalBytes)
    }

    /** xetHash is in the fixture and unknown to the parser. Strict parsing would throw here. */
    @Test
    fun `unknown fields do not break parsing`() = runTest {
        queue.enqueue(treeJson)

        assertTrue(repo.manifest("any/repo").isSuccess)
    }

    @Test
    fun `an http error is returned as a failure`() = runTest {
        queue.enqueue(status = HttpStatusCode.NotFound)

        val result = repo.manifest("nope/nope")

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed json is returned as a failure, not thrown`() = runTest {
        queue.enqueue("not json at all")

        val result = repo.manifest("any/repo")

        assertTrue(result.isFailure)
    }

    /**
     * The tree endpoint is non-recursive by default. Verified live against
     * stabilityai/stable-diffusion-xl-base-1.0: 10 entries without `recursive=true`, 57 with it.
     * Without it Ferry downloads the top level, commits, and reports a complete model.
     */
    @Test
    fun `manifest requests the tree endpoint recursively`() = runTest {
        queue.enqueue(treeJson)

        repo.manifest("Qwen/Qwen2.5-0.5B-Instruct")

        assertEquals(
            "/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main?recursive=true",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * HuggingFace serves single-segment canonical models with no owner — `gpt2`, `bert-base-uncased`
     * — alongside `owner/name` ones. `appendPathSegments(repoId)` must add exactly one path segment
     * for these, not drop the id or split on some other character, since there is no `/` to split on.
     */
    @Test
    fun `a single-segment canonical repo id produces one path segment, not a split or dropped id`() = runTest {
        queue.enqueue(treeJson)

        repo.manifest("gpt2")

        assertEquals(
            "/api/models/gpt2/tree/main?recursive=true",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * Unlike the denylist this used to be, none of `?`, `#` or `&` is rejected here: `?` and `#`
     * travel through appendPathSegments and come out percent-encoded, and `&` comes out as an inert
     * literal character — a path segment has no structural meaning for `&` the way a query string
     * does — rather than any of the three reinterpreting as a query or fragment delimiter (see the
     * comment on `HuggingFace.manifest` for why no pre-check exists any more). Proven here against the
     * actual request produced, not by argument — mirrors `ModelScopeTest`'s equivalent test against
     * the same repoId shape.
     */
    @Test
    fun `a repo id containing url delimiters is percent-encoded rather than reshaping the request`() = runTest {
        queue.enqueue(treeJson)

        val result = repo.manifest("org/name?recursive=false#x&evil=1")

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
            "recursive=true",
            recordedPath.substringAfter('?'),
            "the real query must be exactly recursive=true, unclobbered by the repoId's own ?, & and #",
        )
        assertTrue(
            recordedPath.contains("name%3Frecursive"),
            "the repoId's '?' must be percent-encoded rather than left as a literal delimiter",
        )
        assertEquals(
            "/api/models/org/name%3Frecursive=false%23x&evil=1/tree/main?recursive=true",
            recordedPath,
            "the exact request path, proving both the path segment's own encoding and the appended query",
        )
    }

    /**
     * The fix for docs/known-limitations.md's "a repo id containing `..` can retarget the request":
     * [appendPathSegmentsResolvingDots] resolves ".." by popping the segment before it, so
     * `"../../etc/passwd"` pops `api/models` off the built listing URL entirely (two ".." against
     * that two-segment prefix), landing the request at `/etc/passwd/tree/main` instead of failing.
     * requireWithinNamespace checks the built URL's path rather than repoId's text and must refuse
     * this before the request is ever sent — asserted on the request count, not only on the `Result`,
     * since a destructive version of this bug could still return a `Result.failure` after already
     * leaking the request.
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
        queue.enqueue(treeJson)

        val result = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct")

        assertTrue(result.isSuccess)
        assertEquals(
            "/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main?recursive=true",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /**
     * A single-segment canonical id (`gpt2`) must not be rejected by the namespace check either —
     * `segments.take(2)` against a 5-segment path (`api/models/gpt2/tree/main`) still lands on
     * exactly `[api, models]` regardless of how many segments follow, but this is pinned explicitly
     * rather than left to coincide with `a single-segment canonical repo id produces one path
     * segment...` below, whose own point is appendPathSegments's splitting behaviour, not this check.
     */
    @Test
    fun `a single-segment repo id is not rejected by the namespace check`() = runTest {
        queue.enqueue(treeJson)

        val result = repo.manifest("gpt2")

        assertTrue(result.isSuccess)
        assertEquals("/api/models/gpt2/tree/main?recursive=true", queue.requests[0].url.encodedPathAndQuery)
    }

    /**
     * The regression this closes: `requireWithinNamespace` used to compare against a literal
     * `MODELS_NAMESPACE` (`["api", "models"]`), ignoring whatever path segments `baseUrl` itself
     * already carried. A self-hosted mirror at `.../hf` — `baseUrl` is a public parameter, and a
     * mirror is a contemplated configuration (see `sameOriginOrNull`'s own doc) — built a perfectly
     * correct `.../hf/api/models/...` request and then had this check reject it outright, for every
     * legitimate id, every call. No test caught it because none used a path-carrying `baseUrl`.
     *
     * Fixed by computing the namespace off `base` instead of a bare constant, mirroring the technique
     * already used for `downloadUrl`'s prefix.
     */
    @Test
    fun `a legitimate id succeeds against a path-carrying baseUrl`() = runTest {
        val mirror = HuggingFace(queue.client, baseUrl = "http://hub.test/hf")
        queue.enqueue(treeJson)

        val result = mirror.manifest("Qwen/Qwen2.5-0.5B-Instruct")

        assertTrue(result.isSuccess)
        assertEquals(
            "/hf/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main?recursive=true",
            queue.requests[0].url.encodedPathAndQuery,
        )
    }

    /** The namespace check must still catch an actual escape when `baseUrl` itself carries a path. */
    @Test
    fun `a repo id that traverses out of the models namespace is still refused against a path-carrying baseUrl`() =
        runTest {
            val mirror = HuggingFace(queue.client, baseUrl = "http://hub.test/hf")

            val result = mirror.manifest("../../etc/passwd")

            assertTrue(result.isFailure)
            assertEquals(0, queue.requests.size)
        }

    @Test
    fun `each file carries a resolved download url`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()
        val weights = manifest.files.single { it.path == "model.safetensors" }

        assertTrue(
            weights.url.endsWith("/Qwen/Qwen2.5-0.5B-Instruct/resolve/main/model.safetensors"),
        )
    }

    /**
     * `entry.path` for a nested file already contains its own "/" — "onnx/model.onnx", present in
     * `treeJson` for exactly this reason. `appendPathSegmentsResolvingDots(path)` must split that
     * into two real path segments the same way it splits `repoId`, not fold it into one opaque,
     * percent-encoded segment (`onnx%2Fmodel.onnx`), which would 404 against a hub that expects an
     * actual nested path.
     */
    @Test
    fun `a nested file path produces a download url with the nesting preserved as real path segments`() = runTest {
        queue.enqueue(treeJson)

        val manifest = repo.manifest("Qwen/Qwen2.5-0.5B-Instruct").getOrThrow()
        val nested = manifest.files.single { it.path == "onnx/model.onnx" }

        assertTrue(
            nested.url.endsWith("/Qwen/Qwen2.5-0.5B-Instruct/resolve/main/onnx/model.onnx"),
        )
    }

    /**
     * The other half of docs/known-limitations.md's `..` entry, closed alongside the repoId one: a
     * manifest entry's own `path` comes from the hub's response, not from `repoId`, and is appended
     * after `resolve/main` in `downloadUrl`. A `path` of
     * `"../../../../other/repo/resolve/main/secret.bin"` pops `main`, `resolve` and both of
     * `repoId`'s own segments away, retargeting the download at a different repo's file on this same
     * origin entirely — confirmed empirically before this fix existed. `requireWithinNamespace` now
     * catches it at manifest-build time, so the whole call fails before any `RemoteFile` carrying
     * that URL is ever handed back to a caller.
     *
     * Legitimate nesting is proven not to be rejected by the existing, unchanged
     * `a nested file path produces a download url with the nesting preserved as real path segments`
     * test just above, which already exercises this same, now-checked code path and still passes.
     */
    @Test
    fun `a manifest file path that traverses out of the resolve namespace fails the whole manifest call`() = runTest {
        val evilPath = "../../../../other/repo/resolve/main/secret.bin"
        queue.enqueue("""[ { "type": "file", "path": "$evilPath", "size": 5 } ]""")

        val result = repo.manifest("owner/model")

        assertTrue(
            result.isFailure,
            "must fail rather than hand back a RemoteFile pointing outside the resolve namespace",
        )
        // manifest() never issues a request for a per-file download URL itself - it only computes
        // the string - so this is 1 (the tree listing) regardless of this fix. Asserted anyway: it
        // is what "no download request issued" actually means at this layer, and it costs nothing.
        assertEquals(1, queue.requests.size)
    }

    /**
     * A page caps at 1000 entries and points at the next with a Link header. Verified live:
     * google/gemma-scope-9b-pt-res answers 1000 entries with a rel="next" link, and that link
     * answers 724 with none — 1724 in total. Consuming one page lists a fraction of the repo,
     * commits it, and reports a complete model, with totalBytes short by the difference.
     */
    @Test
    fun `a paged listing accumulates every page`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(HttpHeaders.Link, "<http://hub.test/page2>; rel=\"next\""),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
        assertEquals(42L, manifest.totalBytes, "totalBytes must sum every page")
    }

    /**
     * NEXT_REL used to be matched with `containsMatchIn` against the whole Link segment, URL
     * included — the shape from docs/known-limitations.md is `<https://huggingface.co/api/models/
     * x/tree/main?rel=next>; rel="prev"` — so a `prev` link whose own URL happens to carry an
     * ordinary `?rel=next` query parameter was misidentified as the next page.
     *
     * Reproduced here against the same host/origin the guard under test compares against, so the
     * guard exercised is the regex anchor and not the separate same-origin check. A second page is
     * enqueued so a regression that does chase the link gets an immediate, deterministic answer
     * instead of this test hanging on an empty response queue.
     */
    @Test
    fun `a prev link whose url contains rel=next in its own query string is not treated as next`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(HttpHeaders.Link, "<http://hub.test/page1?rel=next>; rel=\"prev\""),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(
            listOf("page1.bin"),
            manifest.files.map { it.path },
            "a prev link must never be followed as though it were next",
        )
        assertEquals(1, queue.requests.size, "the prev link's own url must never be requested")
    }

    /**
     * `;` is a legal sub-delimiter inside a URL's own path, not only a parameter separator, so
     * anchoring the match to "right after any `;`" was not enough on its own: `<.../x;rel=next>;
     * rel="prev"` still has a `;` immediately before the impostor, with no `?` in sight. Excluding
     * the URI-Reference from the match entirely — matching only what follows its closing `>` — is
     * what actually closes this, regardless of whether the impostor sits in a query or a bare path.
     */
    @Test
    fun `a prev link with a semicolon before rel=next inside its own url is not treated as next`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(HttpHeaders.Link, "<http://hub.test/page1;rel=next>; rel=\"prev\""),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(
            listOf("page1.bin"),
            manifest.files.map { it.path },
            "a prev link must never be followed as though it were next",
        )
        assertEquals(1, queue.requests.size, "the prev link's own url must never be requested")
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
    fun `a link with no separator before a fake rel=next is not treated as next`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(HttpHeaders.Link, "<http://hub.test/prev>rel=next; rel=\"prev\">"),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 20 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(
            listOf("page1.bin"),
            manifest.files.map { it.path },
            "a segment whose real attribute is rel=\"prev\" must not be treated as next",
        )
        assertEquals(1, queue.requests.size, "no second page may be requested")
    }

    /**
     * The next-page URL is chosen by the server — the one request target in this adapter that comes
     * from a response rather than from the caller. Following it as given would let a compromised hub
     * aim the host app's own [HttpClient] at any address it names, an internal one included.
     *
     * Unlike the original MockWebServer version of this test, [QueueClient]'s single [MockEngine]
     * answers a request against any host or port from the same FIFO queue — it never actually
     * dials out, so there is no way to make an off-host target "unreachable" the way a second real
     * server let the original test do. A second response is enqueued here instead: if the guard were
     * missing, the code would follow the off-host link and consume it, which the request count below
     * would catch just the same. The guard under test — [HuggingFace]'s own same-origin check — is
     * exercised identically either way; only the mechanism proving "never requested" changed.
     */
    @Test
    fun `a next page url on another host is refused and never requested`() = runTest {
        queue.enqueue(
            body = treeJson,
            headers = headersOf(HttpHeaders.Link, "<http://other.hub.test/page2>; rel=\"next\""),
        )
        queue.enqueue(treeJson)

        val result = repo.manifest("owner/model")

        assertTrue(result.isFailure)
        assertEquals(1, queue.requests.size, "the off-host page must never be requested")
    }

    /**
     * Repeated `Link:` fields are equivalent to one comma-joined field (RFC 7230 3.2.2), so a
     * response is free to split next and prev across two of them.
     *
     * The ordering here is load-bearing and not arbitrary: this adapter reads every `Link` field via
     * `headers.getAll`, comma-joined, in the order the response declared them — so a next link listed
     * first and a prev link second must still paginate. Reversed, a single-field read would happen to
     * pick the next link up anyway and this test would pass against the very code it exists to fail.
     */
    @Test
    fun `a next link split across repeated Link fields still paginates`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(
                HttpHeaders.Link,
                listOf(
                    "<http://hub.test/page2>; rel=\"next\"",
                    "<http://hub.test/prev>; rel=\"prev\"",
                ),
            ),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
    }

    /** RFC 8288 permits an unquoted rel. Reading it as "last page" is a silent truncation. */
    @Test
    fun `an unquoted rel next still paginates`() = runTest {
        queue.enqueue(
            body = """[ { "type": "file", "path": "page1.bin", "size": 10 } ]""",
            headers = headersOf(HttpHeaders.Link, "<http://hub.test/page2>; rel=next"),
        )
        queue.enqueue("""[ { "type": "file", "path": "page2.bin", "size": 32 } ]""")

        val manifest = repo.manifest("owner/model").getOrThrow()

        assertEquals(listOf("page1.bin", "page2.bin"), manifest.files.map { it.path })
    }

    /**
     * Same host, different port. Harmless against the default huggingface.co, but a baseUrl with an
     * explicit port — a self-hosted mirror — would otherwise follow a next link to any port on that
     * machine.
     *
     * As with the off-host test above, [QueueClient]'s single [MockEngine] cannot model "a real,
     * reachable server on a different port" the way two live `MockWebServer`s did — it answers any
     * target from the one FIFO queue regardless of host or port. A second response is enqueued so a
     * missing guard would consume it and be caught by the request count, exactly as the off-host test
     * does; only the mechanism proving "never requested" changed, not what is being proven.
     */
    @Test
    fun `a next page url on another port is refused and never requested`() = runTest {
        queue.enqueue(
            body = treeJson,
            headers = headersOf(HttpHeaders.Link, "<http://hub.test:9999/page2>; rel=\"next\""),
        )
        queue.enqueue(treeJson)

        val result = repo.manifest("owner/model")

        assertTrue(result.isFailure)
        assertEquals(1, queue.requests.size, "the off-port page must never be requested")
    }

    /**
     * A cursor pointing back at its own page would otherwise loop until the process died.
     *
     * The original test used a custom MockWebServer `Dispatcher` that answered every request
     * identically, forever. [QueueClient]'s FIFO queue has no such "answer forever" mode, so this
     * enqueues exactly [MAX_PAGES] responses instead — the maximum number of requests the code is
     * allowed to make before it must give up — each pointing at itself. If the page cap did not stop
     * the loop, the queue would run dry on the very next request and fail the test with "no response
     * enqueued" rather than with the assertion below, which is exactly as loud a signal.
     */
    @Test
    fun `a listing whose cursor never ends fails at the page cap`() = runTest {
        val selfLink = headersOf(HttpHeaders.Link, "<http://hub.test/forever>; rel=\"next\"")
        repeat(100) { queue.enqueue(body = treeJson, headers = selfLink) }

        val result = repo.manifest("owner/model")

        assertTrue(result.isFailure)
        assertEquals(100, queue.requests.size, "must stop at the cap rather than keep asking")
    }

    @Test
    fun `an invalid baseUrl is returned as a failure, not thrown`() = runTest {
        val invalidRepo = HuggingFace(queue.client, baseUrl = "huggingface.co")

        val result = invalidRepo.manifest("any/repo")

        assertTrue(result.isFailure)
    }
}
