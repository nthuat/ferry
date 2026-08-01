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
            // answer for one. See [ModelScope.manifest]'s KDoc, which carries the same `?`/`&`/`#`
            // reasoning.
            //
            // ".." used to be argued away by this same paragraph, and that was wrong: a denylist is a
            // claim about which repo ids are legal, and ".." is not an id-shape question at all — it's
            // addPathSegments doing exactly what it always does, popping the segment before it. No
            // denylist reasoning applies to it either way, which is what made deferring to "the hub is
            // the authority on valid ids" a non sequitur here. requireWithinNamespace below checks the
            // *built URL* instead, after this call has already decided what it meant to request: it
            // says nothing about which repo ids are legal, only that this request still lands under
            // this adapter's own models namespace, so it can't go stale the way a denylist can. See
            // docs/known-limitations.md, which now documents this as closed rather than deferred.
            //
            // namespace is computed off `base`, not a literal "api/models" constant: baseUrl is a
            // public parameter (a self-hosted mirror is a contemplated configuration, see
            // sameOriginOrNull's doc below), and a literal comparison ignores whatever path segments
            // base itself already carries — a mirror at ".../hf" would build a perfectly correct
            // ".../hf/api/models/..." request and then have this check reject it, which is the same
            // staleness mistake as a denylist, aimed at baseUrl instead of repoId. Building namespace
            // from base first and continuing the same builder from it is what keeps this correct for
            // any base, not only a bare-origin one.
            //
            // recursive=true is not optional. The tree endpoint lists one level by default, so a
            // repo with unet/, vae/ or onnx/ subtrees would list a fraction of its files, download
            // that fraction, and report a complete model — with totalBytes short by the difference,
            // which also makes the free-space precheck under-reserve.
            val namespace = base.newBuilder().addPathSegments("api/models").build()
            var url = namespace.newBuilder()
                .addPathSegments(repoId)
                .addPathSegments("tree/main")
                .addQueryParameter("recursive", "true")
                .build()
                .also { requireWithinNamespace(it, namespace.pathSegments, "repoId '$repoId'") }
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
     *
     * Checked against a **computed**, not literal, prefix — unlike the tree-listing URL above, nothing
     * adapter-owned precedes [repoId] here, so `"api/models"` is not the right thing to assert (it is
     * never part of a real resolve URL; `HuggingFaceTest` pins the actual shape, and asserting it here
     * would reject every legitimate download). The prefix this call actually intends is
     * `{repoId}/resolve/main` — everything before [path] — which is exactly [intended] below, built
     * once with [path] left off and reused both to assert against and to extend. [repoId]'s own `..`
     * cannot violate this: confirmed against okhttp 4.12.0, nothing precedes [repoId] in *this* method
     * for it to pop, and `resolve`/`main` are pushed by a later, independent `addPathSegments` call
     * that nothing processed earlier can reach back into — so [intended] always ends in `resolve`,
     * `main` regardless of [repoId]'s content, and the check on it is a no-op for that vector.
     *
     * [path] is a different story: it comes from the hub's own manifest over the network, not from
     * [repoId], and it is appended *after* [intended] is already fixed — a `path` of
     * `"../../../../other/repo/resolve/main/secret.bin"` pops `main`, `resolve` and both of [repoId]'s
     * segments away, retargeting the fetch at a different repo's file on this same origin entirely
     * (confirmed empirically). `RepoDownloader.resolveInside` guards the *filesystem* destination
     * built from the same [path], but that is a different boundary, at a depth that varies with
     * whatever directory the caller passed — nothing previously asserted this URL stays where this
     * call meant it to, independent of that. [requireWithinNamespace] now does.
     */
    private fun downloadUrl(base: HttpUrl, repoId: String, path: String): String {
        val intended = base.newBuilder()
            .addPathSegments(repoId)
            .addPathSegments("resolve/main")
            .build()
        val full = intended.newBuilder()
            .addPathSegments(path)
            .build()
        requireWithinNamespace(full, intended.pathSegments, "path '$path' for repoId '$repoId'")
        return full.toString()
    }

    /**
     * Fails when [url]'s path no longer starts with [prefix] — the structural signature that a `..`
     * somewhere upstream popped segments this call intended to keep and retargeted the request onto
     * some other path on `baseUrl`'s origin (docs/known-limitations.md). [subject] only shapes the
     * error message.
     *
     * [prefix] is computed off `base` at the tree-listing call site (`base` plus the literal
     * `"api/models"`, so a path-carrying `baseUrl` — a self-hosted mirror — is included rather than
     * ignored), and computed per call from [repoId] at the download-URL site (see [downloadUrl]) —
     * either way, it is known at build time, before the request is issued, which is what makes this
     * an assertion on the output rather than a check on the input.
     *
     * Checked on the built URL, not on the input's own text: a check on the input would be a denylist,
     * and the hub alone is the authority on which ids or paths are legal — that is still the right
     * call for `?`/`&`/`#` (see the comment on [manifest]) but was the wrong argument to extend to
     * `..`, which isn't a legality question at all. This assertion says nothing about which ids or
     * paths are legal; it only refuses to send a request that no longer targets the namespace this
     * call meant it for.
     */
    private fun requireWithinNamespace(url: HttpUrl, prefix: List<String>, subject: String) {
        if (url.pathSegments.take(prefix.size) != prefix) {
            throw IOException("$subject escaped its expected namespace: $url")
        }
    }

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
