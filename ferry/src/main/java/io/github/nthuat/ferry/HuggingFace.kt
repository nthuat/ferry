package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
        // repoId is interpolated into a URL that now carries a query string, so a repoId containing
        // a URL delimiter could reshape the request — "a/b?recursive=false#" would silently turn
        // recursion back off and truncate the repo again. Refused rather than encoded: a repo id is
        // an owner and a name, and none of these three characters belongs in either.
        if (repoId.any { it in URL_DELIMITERS }) {
            return@withContext Result.failure(
                IOException("repo id must not contain '?', '&' or '#': $repoId"),
            )
        }
        try {
            val entries = mutableListOf<TreeEntry>()
            // recursive=true is not optional. The tree endpoint lists one level by default, so a
            // repo with unet/, vae/ or onnx/ subtrees would list a fraction of its files, download
            // that fraction, and report a complete model — with totalBytes short by the difference,
            // which also makes the free-space precheck under-reserve.
            var url = "$baseUrl/api/models/$repoId/tree/main?recursive=true"
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
                        url = "$baseUrl/$repoId/resolve/main/${entry.path}",
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
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("invalid base URL or repo ID", e))
        }
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
        /** Characters that would end the path segment and start a query or fragment. */
        const val URL_DELIMITERS = "?&#"

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
         * Anchored to `;` or the start of the string — but that anchor alone is not sufficient,
         * because `;` is a legal sub-delimiter inside a URL's own path or query, not only a
         * parameter separator: `<.../x;rel=next>; rel="prev"` still has a `;` sitting right before
         * the impostor. What actually keeps this to the real `rel` attribute is the call site
         * (`nextLink`) testing this only against the text after the URI-Reference's closing `>`, so
         * the URL's own text is out of scope before this regex ever runs against it.
         */
        val NEXT_REL = Regex("""(?:^|;)\s*rel="?next"?(?![\w-])""")

        /**
         * ignoreUnknownKeys is load-bearing, not hygiene. HuggingFace adds fields to this response
         * without notice — `xetHash` is a recent one — and strict parsing would turn that into a
         * production outage on a day nobody deployed anything.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
