package io.github.nthuat.ferry.sample

/**
 * A model this sample offers to download, and the facts about it known before asking the hub
 * anything — verified live against the API when this app was written:
 *
 * | Repo id                      | Files | Size    |
 * |-------------------------------|-------|---------|
 * | sshleifer/tiny-gpt2           | 9     | 4.7 MB  |
 * | prajjwal1/bert-tiny           | 5     | 18 MB   |
 * | openai-community/gpt2         | 26    | 5.6 GB  |
 *
 * [fileCount] and [sizeLabel] are shown only in the [io.github.nthuat.ferry.sample.DownloadState.Available]
 * row, before any network call. The moment a real check happens — a `WontFit` refusal, a completed
 * download — every number shown instead comes from a live [io.github.nthuat.ferry.SpaceReport] or
 * [io.github.nthuat.ferry.RepoProgress], never from here, so this catalog can never disagree with
 * the library about what actually happened.
 */
data class ModelCatalogEntry(
    val repoId: String,
    val displayName: String,
    val fileCount: Int,
    val sizeLabel: String,
)

val SAMPLE_CATALOG: List<ModelCatalogEntry> = listOf(
    ModelCatalogEntry(
        repoId = "sshleifer/tiny-gpt2",
        displayName = "tiny-gpt2",
        fileCount = 9,
        sizeLabel = "4.7 MB",
    ),
    ModelCatalogEntry(
        repoId = "prajjwal1/bert-tiny",
        displayName = "bert-tiny",
        fileCount = 5,
        sizeLabel = "18 MB",
    ),
    ModelCatalogEntry(
        repoId = "openai-community/gpt2",
        displayName = "gpt2",
        fileCount = 26,
        sizeLabel = "5.6 GB",
    ),
)
