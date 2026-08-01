package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads repository listings from huggingface.co.
 *
 * The tree endpoint is public and needs no authentication for public models, which is the only
 * case this supports today.
 */
class HuggingFace(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://huggingface.co",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelRepo {

    override suspend fun manifest(repoId: String): Result<RepoManifest> = withContext(dispatcher) {
        try {
            val base = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("invalid base URL: $baseUrl"))

            val entries = mutableListOf<TreeEntry>()
            // repoId travels through addPathSegments rather than string interpolation, so a "?" or
            // "#" inside it is percent-encoded into inert segment text, and a "&" is left as a literal
            // character that is inert for a different reason: a path segment has no structural meaning
            // for "&" the way a query string does. None of the three can be reinterpreted as a query
            // or fragment delimiter — pinned by HuggingFaceTest against the actual request produced,
            // not just argued for here.
            //
            // This replaces what used to be a denylist (URL_DELIMITERS) rejecting those three
            // characters outright, which named the bad character in the error immediately. That
            // specificity is a real, deliberate loss, not a free side effect of this conversion: a
            // malformed id now surfaces only as whatever generic rejection the hub sends back. It is
            // accepted anyway because a denylist is a claim about which repo ids exist, and the hub
            // alone is the authority on that — a denylist goes stale the moment the hub widens its own
            // id rules, and fails closed, rejecting a legitimate id the hub would have served. A
            // malformed id is a programming error; a generic hub-side rejection is an acceptable
            // answer for one. See [ModelScope.manifest]'s KDoc, which carries the same reasoning, and
            // docs/known-limitations.md for the related "..", traversal case this does not change.
            //
            // recursive=true is not optional. The tree endpoint lists one level by default, so a
            // repo with unet/, vae/ or onnx/ subtrees would list a fraction of its files, download
            // that fraction, and report a complete model — with totalBytes short by the difference,
            // which also makes the free-space precheck under-reserve.
            var url = base.newBuilder()
                .addPathSegments("api/models")
                .addPathSegments(repoId)
                .addPathSegments("tree/main")
                .addQueryParameter("recursive", "true")
                .build()
                .toString()
            var pages = 0

            // A page caps at 1000 entries and points at the next with a Link header. Recursion made
            // that cap reachable: google/gemma-scope-9b-pt-res lists 1000 then 724. Stopping at the
            // first page is the same defect recursion just fixed, one level up.
            while (true) {
                if (++pages > MAX_PAGES) {
                    return@withContext Result.failure(
                        IOException("tree listing for $repoId exceeded $MAX_PAGES pages"),
                    )
                }
                val request = Request.Builder().url(url).build()
                val next = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code} listing $repoId"),
                        )
                    }
                    val body = response.body?.string()
                        ?: return@withContext Result.failure(
                            IOException("empty tree response for $repoId"),
                        )
                    entries += json.decodeFromString<List<TreeEntry>>(body)
                    // headers(), not header(): the latter returns one field value, and repeated
                    // Link: fields are equivalent to one comma-joined field (RFC 7230 3.2.2). A
                    // response splitting next and prev across two fields would otherwise yield
                    // whichever one header() picked, and a prev link ends the loop — the same
                    // silent truncation, arriving from a hub-side change with no deploy here.
                    nextLink(response.headers("Link").joinToString(","))
                } ?: break

                // Reassigned to the server's own next-page URL as-is, not rebuilt through
                // HttpUrl.Builder: sameOriginOrNull already proves it parses and shares baseUrl's
                // origin, and rebuilding a URL the server already encoded would risk re-encoding it —
                // turning an already-correct URL into a wrong one.
                url = sameOriginOrNull(next)
                    ?: return@withContext Result.failure(
                        IOException("next page of $repoId is not on $baseUrl: $next"),
                    )
            }

            val files = entries
                .filter { it.type == "file" }
                .map { entry ->
                    RemoteFile(
                        path = entry.path,
                        url = downloadUrl(base, repoId, entry.path),
                        // The lfs block carries the authoritative size for large files.
                        sizeBytes = entry.lfs?.size ?: entry.size,
                        // lfs.oid is the SHA-256. The sibling top-level `oid` is a git blob
                        // SHA-1 and will never match the file's contents.
                        sha256 = entry.lfs?.oid,
                    )
                }
            Result.success(RepoManifest(repoId = repoId, files = files))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(IOException("malformed tree response for $repoId", e))
        }
        // No IllegalArgumentException catch, checked rather than assumed against okhttp 4.12.0's own
        // source: Request.Builder().url(String) can only throw by calling String.toHttpUrl(), and
        // every string this method ever hands it has already been proven to parse before it gets
        // there. The first page's url comes from the HttpUrl.Builder chain above, whose build() throws
        // only IllegalStateException — and only for a null scheme or host, which cannot happen off a
        // newBuilder() of the already-validated `base`. Every later page's url is the server's `next`,
        // but only after sameOriginOrNull has already called url.toHttpUrlOrNull() on that exact
        // string — which is defined as nothing but a try/catch around toHttpUrl() — so by the time it
        // reaches Request.Builder().url(...) here it is already proven not to throw. A catch here
        // would be exactly as dead as ModelScope's own, checked here across both of this file's
        // request sites (the first page and every following one) rather than the one ModelScope has.
    }

    /**
     * The `next` URL out of a `Link` header, or null when this was the last page.
     *
     * The format is `<url>; rel="next"` and a response may carry several, comma separated — so the
     * rel is selected on rather than assumed to be the only one or the first.
     *
     * Anything unrecognised here reads as "last page", which is a silent truncation: nothing
     * downstream can catch it, because a file missing from the manifest is a file `RepoDownloader`
     * never iterates, never downloads and never checks. Accepting both the quoted and the unquoted
     * rel, and reading repeated header fields at the call site, is what keeps that unreachable
     * against a hub that changes its header formatting.
     *
     * NEXT_REL is matched only against the text after the URI-Reference's closing `>`, not the
     * whole segment. `;` is a legal character inside a URL's own path or query — `<.../x;rel=next>;
     * rel="prev"` — so anchoring the regex to "right after a `;`" is not enough on its own: the
     * URL's own text has to be out of scope before the anchor ever sees it.
     */
    private fun nextLink(header: String): String? = header.split(',')
        .firstOrNull { NEXT_REL.containsMatchIn(it.substringAfter('>', "")) }
        ?.substringAfter('<', "")
        ?.substringBefore('>', "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * [url] if it is on the same origin as [baseUrl] — scheme, host and port — null otherwise.
     *
     * The next-page URL is chosen by the server, so following it as given would let a hostile or
     * compromised hub aim this client — carrying whatever the host app's OkHttpClient carries — at
     * any address it names, an internal one included. Pagination is the one place in this adapter
     * where a request target comes from the response rather than from the caller.
     *
     * The port is part of that. Against the default huggingface.co it adds nothing, but a baseUrl
     * with an explicit port — a self-hosted mirror, a dev configuration — otherwise lets a next link
     * of `http://<same host>:22/…` through. No hub pages on a different port than it lists on.
     */
    private fun sameOriginOrNull(url: String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val next = url.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            next.scheme == base.scheme && next.host == base.host && next.port == base.port
        }
    }

    /**
     * Where to fetch [path] from, inside [repoId], at the `main` revision.
     *
     * Mirrors [ModelScope]'s private `downloadUrl`: built with [HttpUrl.Builder] rather than
     * interpolated into a string, so a `?` or `#` in [repoId] or [path] is percent-encoded into inert
     * segment text, and a `&` is left as a literal character that is inert for a different reason — a
     * path segment has no structural meaning for `&` the way a query string does. None of the three
     * can be reinterpreted as a query or fragment delimiter.
     */
    private fun downloadUrl(base: HttpUrl, repoId: String, path: String): String = base.newBuilder()
        .addPathSegments(repoId)
        .addPathSegments("resolve/main")
        .addPathSegments(path)
        .build()
        .toString()

    @Serializable
    private data class TreeEntry(
        val type: String,
        val path: String,
        val size: Long = 0,
        val lfs: Lfs? = null,
    )

    @Serializable
    private data class Lfs(
        val oid: String,
        val size: Long,
    )

    private companion object {
        /**
         * A cursor that points back at its own page would otherwise loop forever. 100 pages is
         * 100,000 entries at the hub's 1000-per-page cap — far beyond any real repository, so this
         * can only be reached by a server that is broken or hostile.
         */
        const val MAX_PAGES = 100

        /**
         * `rel="next"`, or the unquoted `rel=next` that RFC 8288 also permits. The trailing
         * lookahead is what stops this matching `rel=nextpage`.
         *
         * Anchored to an actual `;`, not also to the start of the string. `nextLink` already scopes
         * this to the text after the URI-Reference's closing `>`, and the Link grammar (RFC 8288)
         * requires every parameter in that text — including the first — to be preceded by a real
         * `;`; there is no production for one sitting directly against the `>` with nothing between
         * them. Accepting the start of the string as an alternative anchor used to be harmless: it
         * was tested against the *whole* segment, where the URL always came first, so the start of
         * the string was never where a real `rel` attribute could begin. Once the URL was scoped
         * out, that same alternative started meaning "right after `>`, no separator" — a position
         * the grammar never permits — and a segment missing that separator, `<url>rel=next;
         * rel="prev">`, has no valid parameter there at all, but matched anyway.
         */
        val NEXT_REL = Regex(""";\s*rel="?next"?(?![\w-])""")

        /**
         * ignoreUnknownKeys is load-bearing, not hygiene. HuggingFace adds fields to this response
         * without notice — `xetHash` is a recent one — and strict parsing would turn that into a
         * production outage on a day nobody deployed anything.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
