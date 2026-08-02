package dev.thuat.ferry

/**
 * One downloadable file in a model repository.
 *
 * [sha256] is null for files the hub does not track with a content hash — typically small config
 * and tokenizer files. Those are verified by size alone, which is weaker and unavoidable.
 */
data class RemoteFile(
    /** Where this file lands on disk, relative to the repo directory. */
    val path: String,
    /**
     * Where to fetch it from, resolved by the adapter while building the manifest.
     *
     * Carried here rather than derived later from [path] because not every hub names its files.
     * Ollama serves content-addressed OCI blobs whose only identifier is a digest, so a stateless
     * `fileUrl(repoId, path)` could not map a synthesized path back to one without re-fetching the
     * manifest it already had.
     */
    val url: String,
    /**
     * Checked unconditionally against what lands on disk, by both `RepoDownloader.download()` and
     * `isSatisfiedBy` — including when this is `0`, which is a real assertion that the file is empty,
     * not a sentinel for "size unknown" (docs/known-limitations.md). A third-party `ModelRepo` that
     * cannot report a real size for some file has no way to opt out of this check.
     */
    val sizeBytes: Long,
    val sha256: String?,
)

/** Everything needed to decide whether a repo can be downloaded, before downloading any of it. */
data class RepoManifest(
    val repoId: String,
    val files: List<RemoteFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * A model hub. Implemented per host, because HuggingFace and ModelScope describe repositories
 * differently while the download mechanics are identical.
 */
interface ModelRepo {

    suspend fun manifest(repoId: String): Result<RepoManifest>
}
