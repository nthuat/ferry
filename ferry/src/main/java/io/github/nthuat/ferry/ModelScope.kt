package io.github.nthuat.ferry

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

            // repoId travels through addPathSegments rather than string interpolation, so a "?", "#"
            // or "&" inside it is percent-encoded as ordinary segment text instead of being
            // reinterpreted as a query or fragment delimiter — pinned by ModelScopeTest against the
            // actual request produced, not just argued for here. HuggingFace builds its URL by
            // string interpolation and has to reject those characters up front for exactly this
            // reason (see its URL_DELIMITERS); building through the typed API here removes the need
            // for an equivalent guard rather than duplicating it.
            //
            // A malformed id is left for the hub itself to reject, rather than pre-checked here. A
            // client-side shape check on repo ids is a denylist, and the hub alone is the authority
            // on which ids exist: a denylist goes stale the moment the hub widens its own id rules,
            // and fails closed — rejecting a legitimate id the hub would have served. Deferring to
            // the hub cannot go stale that way; the cost is a slower, less specific failure for what
            // is a programming error, not a user-facing path. The same reasoning covers "../x" (see
            // docs/known-limitations.md, which affects both adapters): the traversal it enables is
            // bounded to the hub's own origin, which is what makes deferring acceptable rather than
            // merely convenient.
            val listingUrl = base.newBuilder()
                .addPathSegments("api/v1/models")
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
     */
    private fun downloadUrl(base: HttpUrl, repoId: String, path: String): String = base.newBuilder()
        .addPathSegments("api/v1/models")
        .addPathSegments(repoId)
        .addPathSegment("repo")
        .addQueryParameter("Revision", revision)
        .addQueryParameter("FilePath", path)
        .build()
        .toString()

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
