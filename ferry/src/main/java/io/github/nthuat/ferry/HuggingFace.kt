package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
            // recursive=true is not optional. The tree endpoint lists one level by default, so a
            // repo with unet/, vae/ or onnx/ subtrees would list a fraction of its files, download
            // that fraction, and report a complete model — with totalBytes short by the difference,
            // which also makes the free-space precheck under-reserve.
            val request = Request.Builder()
                .url("$baseUrl/api/models/$repoId/tree/main?recursive=true")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} listing $repoId"),
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty tree response for $repoId"))
                val files = json.decodeFromString<List<TreeEntry>>(body)
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
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(IOException("malformed tree response for $repoId", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("invalid base URL or repo ID", e))
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
         * ignoreUnknownKeys is load-bearing, not hygiene. HuggingFace adds fields to this response
         * without notice — `xetHash` is a recent one — and strict parsing would turn that into a
         * production outage on a day nobody deployed anything.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
