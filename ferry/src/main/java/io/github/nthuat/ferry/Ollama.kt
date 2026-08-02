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
 * Reads image manifests from registry.ollama.ai's OCI distribution API.
 *
 * Structurally unlike [HuggingFace] and [ModelScope]: those list a directory tree of named files
 * with per-file hashes; this reads a single OCI image manifest whose `layers` carry no filename at
 * all, only a `mediaType`, a `size` and a content-addressed `digest`. Every difference from the other
 * two adapters below follows from that one fact.
 */
class Ollama(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://registry.ollama.ai",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelRepo {

    override suspend fun manifest(repoId: String): Result<RepoManifest> = withContext(dispatcher) {
        try {
            val base = baseUrl.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("invalid base URL: $baseUrl"))

            // repoId is Docker/Ollama reference shorthand, "[namespace/]name[:tag]":
            //  - the tag after the last ':' selects the manifest; missing, it defaults to "latest",
            //    Ollama's own convention for an unqualified `ollama pull name`.
            //  - a namespace before a '/' selects who published the model; missing, it defaults to
            //    "library", Ollama's namespace for its own curated models, so "qwen2.5:0.5b" and
            //    "library/qwen2.5:0.5b" name the same manifest. Never invented when repoId already
            //    carries one of its own, so "someuser/model:tag" is requested exactly as given, not
            //    rewritten to "library/someuser/model:tag". (docs/known-limitations.md: only the
            //    library/ form has been verified live.)
            val (namePart, tag) = splitTag(repoId)
            val qualifiedName = if ('/' in namePart) namePart else "library/$namePart"

            // qualifiedName travels through addPathSegments rather than string interpolation, the
            // same reason HuggingFace/ModelScope's repoId does (see ModelScope.manifest's KDoc): a
            // "?", "#" or "&" inside it can't reinterpret this request's query, and a ".." can't
            // retarget it either, because requireWithinNamespace below asserts the built URL still
            // starts under "v2" - the one segment every OCI distribution request lives under - before
            // the request is issued.
            val namespace = registryNamespace(base)
            val manifestUrl = namespace.newBuilder()
                .addPathSegments(qualifiedName)
                .addPathSegment("manifests")
                .addPathSegment(tag)
                .build()
                .also { requireWithinNamespace(it, namespace.pathSegments, "repoId '$repoId'") }

            val request = Request.Builder()
                .url(manifestUrl)
                // Required, not a politeness header: verified live, omitting it answers a
                // legacy-schema-shaped body instead of the config/layers shape this parser expects.
                .header("Accept", MANIFEST_ACCEPT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // An OCI registry's error body is structured ({"errors":[{"code","message"}]}),
                    // not prose - surfaced when present, same spirit as ModelScope's own
                    // `parsed.message` for an API-level failure, but here the transport status is
                    // already the failure signal, so the body only ever adds detail, never changes
                    // the verdict.
                    val detail = response.body?.string()?.let { errorDetail(it) }
                    return@withContext Result.failure(
                        IOException(
                            "HTTP ${response.code} for manifest $repoId" +
                                (detail?.let { ": $it" } ?: ""),
                        ),
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty manifest for $repoId"))

                val parsed = json.decodeFromString<ManifestResponse>(body)

                // Every layer's digest is checked before any is mapped, rather than mapping and
                // failing partway: a manifest half-translated into RemoteFiles is never handed back,
                // only a complete one or none. Ollama has only ever been observed publishing sha256
                // (verified live), but OCI's digest grammar allows other algorithms, and
                // RemoteFile.sha256 is specifically a SHA-256 - silently treating another algorithm's
                // hex as though it were one would fail verification later, at download time, for a
                // reason indistinguishable from real corruption.
                val badDigest = parsed.layers.map { it.digest }
                    .firstOrNull { !it.startsWith(SHA256_PREFIX) }
                if (badDigest != null) {
                    return@withContext Result.failure(
                        IOException("layer digest for $repoId is not sha256: $badDigest"),
                    )
                }

                // config is deliberately not in files: the manifest carries `config` and `layers` as
                // two different kinds of thing, not two pages of one list. `layers` is the model's
                // content; `config` describes how to interpret it - the OCI-mandated Docker-image-
                // config shape (rootfs, architecture, diff_ids) Ollama's own runtime consults, not
                // something an inference engine loads. The layers already carry everything a consumer
                // of the downloaded files needs: weights, projector, template, system prompt,
                // license, parameters. ManifestResponse below does not even parse `config` -
                // ignoreUnknownKeys drops it - so this is a real exclusion, not an oversight papered
                // over by a filter. A future caller needing the raw config JSON has nowhere to read
                // it from today; that would need a deliberate, additive change - another field on
                // RepoManifest, or a second method - not a quiet addition to `files`.
                val files = parsed.layers.map { layer ->
                    val hex = layer.digest.removePrefix(SHA256_PREFIX)
                    RemoteFile(
                        // Collision-proof by construction: two layers can share this path only if
                        // they share both a mediaType suffix and a digest, and a shared digest means
                        // identical content by definition of content-addressing - the same file
                        // referenced twice, not a collision. This is what tells
                        // llama3.2-vision:11b's two "image.license" layers apart (OllamaTest pins
                        // this exact shape): same suffix, different digest, different path. Naming
                        // by suffix alone - the obvious mapping - collides there and silently drops
                        // one, the same silent-truncation class HuggingFace's non-recursive listing
                        // already cost this project once.
                        path = "${layerSuffix(layer.mediaType)}-$hex",
                        url = blobUrl(base, qualifiedName, layer.digest),
                        sizeBytes = layer.size,
                        sha256 = hex,
                    )
                }
                Result.success(RepoManifest(repoId = repoId, files = files))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(IOException("malformed manifest for $repoId", e))
        }
        // No IllegalArgumentException catch, same reasoning as ModelScope.manifest: every URL here
        // is either base (proven via toHttpUrlOrNull above) or built through HttpUrl.Builder, which
        // encodes rather than throws, and Request.Builder().url(HttpUrl) - not the String overload -
        // never throws IllegalArgumentException in the first place.
    }

    /**
     * Where to fetch a layer's content from: [base]'s "v2" namespace, [qualifiedName], "blobs", and
     * the digest itself - an OCI registry addresses a blob by its own digest, not by any name this
     * adapter invents.
     *
     * Mirrors [ModelScope]'s own private `downloadUrl` in shape and in why it recomputes
     * [registryNamespace] rather than receiving it as a parameter: kept safe to call in isolation,
     * not only from the one place it is actually called from today.
     */
    private fun blobUrl(base: HttpUrl, qualifiedName: String, digest: String): String {
        val namespace = registryNamespace(base)
        val full = namespace.newBuilder()
            .addPathSegments(qualifiedName)
            .addPathSegment("blobs")
            .addPathSegment(digest)
            .build()
        requireWithinNamespace(full, namespace.pathSegments, "repo name '$qualifiedName'")
        return full.toString()
    }

    /**
     * [base] plus the literal "v2" prefix every OCI distribution request lives under - computed off
     * [base], not a bare constant, so a path-carrying `baseUrl` (a mirrored or self-hosted registry)
     * is included in the namespace both call sites check against. Mirrors [ModelScope]'s own
     * `modelsNamespace`.
     */
    private fun registryNamespace(base: HttpUrl): HttpUrl = base.newBuilder()
        .addPathSegments("v2")
        .build()

    /**
     * Fails when [url]'s path no longer starts with [prefix] - the same structural check
     * [HuggingFace] and [ModelScope] each carry as their own private `requireWithinNamespace`,
     * applied here against this adapter's own namespace. See either for the full reasoning behind
     * checking the built URL rather than [subject]'s own text; not repeated here.
     */
    private fun requireWithinNamespace(url: HttpUrl, prefix: List<String>, subject: String) {
        if (url.pathSegments.take(prefix.size) != prefix) {
            throw IOException("$subject escaped the registry namespace: $url")
        }
    }

    /**
     * The last dot-delimited segment of an Ollama layer's mediaType - "model", "projector", "system",
     * "template", "license", "params" observed live (`application/vnd.ollama.image.<this>`).
     * Human-readable, and deliberately not what makes the synthesized path collision-proof on its
     * own - see the comment where this is combined with a digest, at the call site in [manifest].
     */
    private fun layerSuffix(mediaType: String): String = mediaType.substringAfterLast('.')

    /**
     * [repoId] split into a name and a tag: everything before the last ':' and everything after it.
     * No ':' means no tag was given, which resolves to "latest" here - the one place repoId becomes
     * a request - rather than pushed onto every caller.
     */
    private fun splitTag(repoId: String): Pair<String, String> {
        val colon = repoId.lastIndexOf(':')
        return if (colon >= 0) {
            repoId.substring(0, colon) to repoId.substring(colon + 1)
        } else {
            repoId to "latest"
        }
    }

    /**
     * The first message out of an OCI-shaped `{"errors":[{"code","message"}]}` body, or null if
     * [body] is not one. A non-2xx response is not obligated to be in this shape - a proxy in front
     * of the registry can answer with anything - so a body that isn't is a silently absent detail,
     * not a second failure stacked on the HTTP status this already carries.
     */
    private fun errorDetail(body: String): String? =
        runCatching { json.decodeFromString<OciErrorResponse>(body) }
            .getOrNull()
            ?.errors
            ?.firstOrNull()
            ?.message

    @Serializable
    private data class ManifestResponse(
        val layers: List<LayerEntry> = emptyList(),
    )

    @Serializable
    private data class LayerEntry(
        val mediaType: String,
        val size: Long = 0,
        val digest: String,
    )

    @Serializable
    private data class OciErrorResponse(
        val errors: List<OciError> = emptyList(),
    )

    @Serializable
    private data class OciError(
        val code: String = "",
        val message: String = "",
    )

    private companion object {
        const val SHA256_PREFIX = "sha256:"
        const val MANIFEST_ACCEPT = "application/vnd.docker.distribution.manifest.v2+json"

        /**
         * ignoreUnknownKeys is load-bearing, not hygiene, exactly as in HuggingFace/ModelScope:
         * schemaVersion, mediaType and config all appear in a real response and are dropped
         * deliberately (config: see the exclusion comment in manifest above), and a registry that
         * adds another field tomorrow must not turn every manifest into a hard failure today.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
