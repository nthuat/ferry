package dev.thuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads repository listings from modelscope.cn.
 *
 * The listing endpoint is public and needs no authentication for public models, the same scope
 * [HuggingFace] holds.
 *
 * Unlike [HuggingFace], this makes exactly one request per [manifest] call — no paging parameter
 * (`PageSize`, `PageNumber`, `Limit`, `limit`) or `Link`-style header was found on live repos of 15
 * and 39 files, and the official Python client's own model-listing call takes no such parameter
 * either — only its *dataset* listing does, via a plain page-number loop. That is evidence, not
 * proof: nobody has tested a repo of hundreds or thousands of files, which is exactly the scale
 * HuggingFace's own 1000-entry cap only appears at. See docs/known-limitations.md — a truncated
 * manifest here would look identical to a complete one, committed and reported as success.
 */
class ModelScope(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://modelscope.cn",
    private val revision: String = "master",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelRepo {

    override suspend fun manifest(repoId: String): Result<RepoManifest> = withContext(dispatcher) {
        try {
            val base = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("invalid base URL: $baseUrl"))

            // repoId travels through addPathSegments rather than string interpolation, so a "?" or
            // "#" inside it is percent-encoded into inert segment text, and a "&" is left as a literal
            // character that is inert for a different reason: a path segment has no structural meaning
            // for "&" the way a query string does. None of the three can be reinterpreted as a query
            // or fragment delimiter — pinned by ModelScopeTest against the actual request produced,
            // not just argued for here. HuggingFace originally built its URL
            // by string interpolation and had to reject those characters up front with a denylist for
            // exactly this reason; it has since converted to this same typed-API approach and dropped
            // that denylist (see HuggingFace.manifest's own comment for that history), rather than
            // this file duplicating an equivalent guard.
            //
            // A malformed id is left for the hub itself to reject, rather than pre-checked here. A
            // client-side shape check on repo ids is a denylist, and the hub alone is the authority
            // on which ids exist: a denylist goes stale the moment the hub widens its own id rules,
            // and fails closed — rejecting a legitimate id the hub would have served. Deferring to
            // the hub cannot go stale that way; the cost is a slower, less specific failure for what
            // is a programming error, not a user-facing path.
            //
            // "../x" used to be covered by this same paragraph, and that was wrong: a denylist is a
            // claim about which repo ids are legal, and ".." isn't an id-legality question — it's
            // addPathSegments doing exactly what it always does, popping the segment before it. No
            // denylist reasoning applies to it either way, so deferring to "the hub decides which ids
            // are valid" was a non sequitur here, not a narrower case of the same argument.
            // requireWithinNamespace below checks the *built URL* instead, after this call has already
            // decided what it meant to request: it says nothing about which repo ids are legal, only
            // that this request still lands under this adapter's own models namespace, so it can't go
            // stale the way a denylist can. See docs/known-limitations.md, which now documents this as
            // closed rather than deferred.
            //
            // namespace is computed off `base` via modelsNamespace(base), not a literal "api/v1/models"
            // constant: baseUrl is a public parameter, and a literal comparison ignores whatever path
            // segments base itself already carries — a mirror at ".../hf" would build a perfectly
            // correct ".../hf/api/v1/models/..." request and then have this check reject it, the same
            // staleness mistake as a denylist, aimed at baseUrl instead of repoId.
            val namespace = modelsNamespace(base)
            val listingUrl = namespace.newBuilder()
                .addPathSegments(repoId)
                .addPathSegments("repo/files")
                .addQueryParameter("Revision", revision)
                // Case-sensitive, and the wrong case is silently ignored rather than rejected:
                // verified live, ?Recursive=True answered 39 entries (21 nested); ?recursive=true
                // answered 18 with none nested — HTTP 200 either way, no error. A lowercase typo is a
                // truncated repo that commits and reports success, so the exact casing is pinned by
                // ModelScopeTest rather than left to whoever next touches this line.
                .addQueryParameter("Recursive", "True")
                .build()
                .also { requireWithinNamespace(it, namespace.pathSegments, "repoId '$repoId'") }

            val request = Request.Builder().url(listingUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} listing $repoId"),
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty response listing $repoId"))

                val parsed = json.decodeFromString<ListingResponse>(body)
                // An API-level failure can arrive inside a 200, so this is checked separately from
                // the transport-level isSuccessful check above rather than folded into it.
                if (!parsed.success) {
                    return@withContext Result.failure(
                        IOException(
                            parsed.message
                                ?: "ModelScope reported failure (code ${parsed.code}) for $repoId",
                        ),
                    )
                }

                val files = (parsed.data?.files ?: emptyList())
                    // Type is "tree" for directories and "blob" for files. Directories are still
                    // present under Recursive=True — verified live, 32 blobs and 7 trees in one
                    // 39-entry response — so this filter is load-bearing, not defensive dead code.
                    .filter { it.type == "blob" }
                    .map { entry ->
                        RemoteFile(
                            path = entry.path,
                            url = downloadUrl(base, repoId, entry.path),
                            sizeBytes = entry.size,
                            // Every blob carries Sha256 here, including small non-LFS files — unlike
                            // HuggingFace, where only LFS files have one. Mapped straight through and
                            // left nullable regardless: that is the interface's shape, not this hub's.
                            sha256 = entry.sha256,
                        )
                    }
                Result.success(RepoManifest(repoId = repoId, files = files))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(IOException("malformed listing response for $repoId", e))
        }
        // No IllegalArgumentException catch: unlike HuggingFace, nothing in this method parses a
        // raw URL string. baseUrl goes through the null-returning toHttpUrlOrNull() above, and
        // every other URL is assembled through HttpUrl.Builder, which encodes rather than throws —
        // confirmed empirically (see the review-fixes note in the report) against empty strings, NUL
        // bytes, unpaired surrogates and a 100k-character id. A catch here would be copied from a
        // construction method this file does not use, dead and untestable.
    }

    /**
     * Where to fetch [path] from, inside [repoId] at this adapter's [revision].
     *
     * The file path is a query parameter here, not a path segment — HuggingFace puts it in the URL
     * path, this hub does not. Built with [HttpUrl.Builder.addQueryParameter], which percent-encodes
     * the value, rather than interpolated into a string: a path containing `&`, `#`, `+` or a space
     * would otherwise corrupt the request or silently resolve to the wrong file.
     *
     * `api/v1/models` precedes [repoId] here exactly as it does in [manifest]'s listing URL — unlike
     * [HuggingFace]'s own `downloadUrl`, where [repoId] is the first thing appended and there is no
     * fixed prefix to protect — so the same [requireWithinNamespace] check applies to this URL too,
     * against the same [modelsNamespace] computed off [base].
     *
     * [path] itself needs no such check here, unlike [HuggingFace]'s equivalent: it travels through
     * `addQueryParameter`, which treats it as an opaque value to percent-encode, never as path
     * segments to resolve `..` against — confirmed empirically, a [path] of
     * `"../../../../other/repo/resolve/main/secret.bin"` round-trips into `FilePath` unchanged and
     * `pathSegments` stays exactly `api/v1/models/{repoId}/repo`, regardless of what [path] contains.
     * `HuggingFace` puts its per-file path in the URL path instead, which is what exposes it there.
     *
     * The [requireWithinNamespace] call below is consequently unreachable in practice, not merely
     * unlikely: this is `private`, called only from [manifest], only after [manifest]'s own listing-URL
     * call has already run the identical check against the identical [repoId] and [base]. If that one
     * didn't throw, this one structurally cannot either — same prefix, same inputs, a different suffix
     * after [repoId] that the check never looks at. Kept for symmetry with [HuggingFace] and because a
     * future caller of this method in isolation should not go unchecked, not because a test here could
     * ever fail for the right reason; no such test is written.
     */
    private fun downloadUrl(base: HttpUrl, repoId: String, path: String): String {
        val namespace = modelsNamespace(base)
        val full = namespace.newBuilder()
            .addPathSegments(repoId)
            .addPathSegment("repo")
            .addQueryParameter("Revision", revision)
            .addQueryParameter("FilePath", path)
            .build()
        requireWithinNamespace(full, namespace.pathSegments, "repoId '$repoId'")
        return full.toString()
    }

    /**
     * [base] plus this adapter's literal `api/v1/models` prefix — computed off [base] rather than a
     * bare constant so a path-carrying `baseUrl` (a self-hosted mirror) is included in the namespace
     * both call sites check against, not silently ignored. Shared between [manifest]'s listing URL and
     * [downloadUrl] so the literal string exists in exactly one place, not two that could drift apart.
     */
    private fun modelsNamespace(base: HttpUrl): HttpUrl = base.newBuilder()
        .addPathSegments("api/v1/models")
        .build()

    /**
     * Fails when [url]'s path no longer starts with [prefix] — the structural signature that a `..`
     * in [subject] popped segments this call intended to keep and retargeted the request onto some
     * other path on `baseUrl`'s origin (docs/known-limitations.md).
     *
     * [prefix] is [modelsNamespace] computed off `base`, at both call sites — never a bare constant,
     * so a path-carrying `baseUrl` is asserted against correctly rather than rejected outright.
     *
     * Checked on the built URL, not on [subject]'s own text: a check on the input would be a denylist,
     * and the hub alone is the authority on which ids are legal — that is still the right call for
     * `?`/`&`/`#` (see the comment on [manifest]) but was the wrong argument to extend to `..`, which
     * isn't an id-legality question at all. This assertion says nothing about which ids are legal; it
     * only refuses to send a request that no longer targets the namespace this method meant it for.
     */
    private fun requireWithinNamespace(url: HttpUrl, prefix: List<String>, subject: String) {
        if (url.pathSegments.take(prefix.size) != prefix) {
            throw IOException("$subject escaped the models namespace: $url")
        }
    }

    @Serializable
    private data class ListingResponse(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("Code") val code: Int = 0,
        @SerialName("Message") val message: String? = null,
        @SerialName("Data") val data: ListingData? = null,
    )

    @Serializable
    private data class ListingData(
        @SerialName("Files") val files: List<FileEntry> = emptyList(),
    )

    @Serializable
    private data class FileEntry(
        @SerialName("Path") val path: String,
        @SerialName("Type") val type: String,
        @SerialName("Size") val size: Long = 0,
        @SerialName("Sha256") val sha256: String? = null,
    )

    private companion object {
        /**
         * ignoreUnknownKeys is load-bearing, not hygiene, exactly as in [HuggingFace]: this envelope
         * already carries `IsVisual` and `LatestCommitter` that this code has no use for, and a hub
         * that adds another field tomorrow must not turn every listing into a hard failure today.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
