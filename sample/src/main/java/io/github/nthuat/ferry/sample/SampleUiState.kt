package io.github.nthuat.ferry.sample

/** One row: the static catalog facts, and the live state Ferry has reported for it. */
data class ModelRow(
    val catalog: ModelCatalogEntry,
    val state: DownloadState = DownloadState.Available,
)

/**
 * State of the sabotage panel. Both controls are captions plus a trigger — there is no state to
 * hold for "corrupt a file" beyond the caption reporting what it just did, since the effect itself
 * lives on disk and is only observed the next time a row re-checks.
 */
data class SabotageState(
    val lowDiskSimulationEnabled: Boolean = false,
    val lastCorruption: String? = null,
)

data class SampleUiState(
    val rows: List<ModelRow>,
    val sabotage: SabotageState = SabotageState(),
) {
    val hasDownloadedModel: Boolean get() = rows.any { it.state is DownloadState.Downloaded }

    companion object {
        val Initial = SampleUiState(rows = SAMPLE_CATALOG.map { ModelRow(catalog = it) })
    }
}
