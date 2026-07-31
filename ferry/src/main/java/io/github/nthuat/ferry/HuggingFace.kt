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
        try {
            val request = Request.Builder().url("$baseUrl/api/models/$repoId/tree/main").build()
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
        /**
         * ignoreUnknownKeys is load-bearing, not hygiene. HuggingFace adds fields to this response
         * without notice — `xetHash` is a recent one — and strict parsing would turn that into a
         * production outage on a day nobody deployed anything.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
