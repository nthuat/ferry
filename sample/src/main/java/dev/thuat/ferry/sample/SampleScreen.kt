package dev.thuat.ferry.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The whole screen: the model list, then the sabotage panel below it.
 *
 * State is hoisted — every parameter here is data or a callback, nothing reaches into a ViewModel —
 * so this and everything it calls is previewable on its own; see the previews at the bottom.
 */
@Composable
fun SampleScreen(
    state: SampleUiState,
    onDownload: (String) -> Unit,
    onRecheck: (String) -> Unit,
    onToggleLowDisk: (Boolean) -> Unit,
    onCorruptFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Ferry sample",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.catalog.repoId }) { row ->
                ModelRowCard(
                    row = row,
                    onDownload = { onDownload(row.catalog.repoId) },
                    onRecheck = { onRecheck(row.catalog.repoId) },
                )
            }
        }
        HorizontalDivider()
        SabotagePanel(
            sabotage = state.sabotage,
            hasDownloadedModel = state.hasDownloadedModel,
            onToggleLowDisk = onToggleLowDisk,
            onCorruptFile = onCorruptFile,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private val ROW_MIN_HEIGHT = 96.dp

// Generous rather than measured exactly: M3's default Button content padding is 24.dp per side, and
// this needs headroom for "Download"/"Re-check" at any device font scale, not just a default one.
private val ACTION_SLOT_WIDTH = 120.dp
private val ACTION_SLOT_HEIGHT = 40.dp
private val PROGRESS_HEIGHT = 4.dp

/**
 * One model's row. Fixed minimum height, a caption pinned to exactly two lines, and a fixed-size
 * action slot regardless of which (if any) action is shown — so no state transition changes this
 * row's size and the list never reflows under the user's thumb.
 */
@Composable
fun ModelRowCard(
    row: ModelRow,
    onDownload: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.catalog.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = captionFor(row.state, row.catalog),
                        style = MaterialTheme.typography.bodySmall,
                        color = captionColorFor(row.state),
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier.size(width = ACTION_SLOT_WIDTH, height = ACTION_SLOT_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    ActionControl(row.state, onDownload = onDownload, onRecheck = onRecheck)
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressSlot(row.state)
        }
    }
}

@Composable
private fun ActionControl(state: DownloadState, onDownload: () -> Unit, onRecheck: () -> Unit) {
    when (state) {
        is DownloadState.Available, is DownloadState.Failed ->
            Button(onClick = onDownload) { Text("Download") }

        // Action disabled — the numbers above already say why.
        is DownloadState.WontFit ->
            Button(onClick = onDownload, enabled = false) { Text("Download") }

        is DownloadState.Downloaded ->
            OutlinedButton(onClick = onRecheck) { Text("Re-check") }

        DownloadState.CheckingSpace, is DownloadState.Downloading, is DownloadState.Verifying -> Unit
    }
}

@Composable
private fun ProgressSlot(state: DownloadState) {
    val modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT)
    when (state) {
        is DownloadState.Downloading ->
            LinearProgressIndicator(progress = { state.fraction }, modifier = modifier)

        DownloadState.CheckingSpace, is DownloadState.Verifying ->
            LinearProgressIndicator(modifier = modifier)

        else -> Spacer(modifier)
    }
}

/** Same fraction the progress bar draws and the caption's percentage is computed from — one source. */
private val DownloadState.Downloading.fraction: Float
    get() = if (fileBytes > 0) (bytesWritten.toFloat() / fileBytes.toFloat()).coerceIn(0f, 1f) else 0f

private fun captionFor(state: DownloadState, catalog: ModelCatalogEntry): String = when (state) {
    DownloadState.Available -> "${catalog.fileCount} files · ${catalog.sizeLabel}"

    DownloadState.CheckingSpace -> "Checking free space…"

    is DownloadState.WontFit ->
        "Won't fit — needs ${formatBytes(state.requiredBytes)} · ${formatBytes(state.freeBytes)} free"

    is DownloadState.Downloading -> {
        val percent = (state.fraction * 100).toInt()
        "File ${state.fileIndex + 1} of ${state.fileCount}: ${state.path} — $percent%"
    }

    is DownloadState.Verifying -> "Verifying ${state.path}…"

    is DownloadState.Downloaded -> if (state.cacheHit) {
        "Re-check complete — 0 bytes transferred, cache already matched"
    } else {
        // state.fileCount is the live count from this attempt's own Downloading events, not the
        // static catalog entry — falling back to the catalog only covers a count that in practice
        // never arises (a real transfer always fires at least one Downloading event first).
        "Downloaded — ${state.fileCount ?: catalog.fileCount} files"
    }

    is DownloadState.Failed -> state.message
}

@Composable
private fun captionColorFor(state: DownloadState): Color = when (state) {
    is DownloadState.Failed, is DownloadState.WontFit -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Decimal (SI) units — HuggingFace's own repo sizes, and this brief's, are quoted the same way. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.2f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.2f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Controls that stage Ferry's refusals on demand. Each caption names what a downloader without that
 * guard would have done instead — the contrast is the point of the app, not the download itself.
 */
@Composable
fun SabotagePanel(
    sabotage: SabotageState,
    hasDownloadedModel: Boolean,
    onToggleLowDisk: (Boolean) -> Unit,
    onCorruptFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Sabotage", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Pretend the disk is nearly full")
                Text(
                    text = "Without this guard: would have started, then failed partway through " +
                        "— after already spending the download.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = sabotage.lowDiskSimulationEnabled, onCheckedChange = onToggleLowDisk)
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Corrupt a downloaded file")
                Text(
                    text = "Without this guard: would have loaded a corrupt model.",
                    style = MaterialTheme.typography.bodySmall,
                )
                sabotage.lastCorruption?.let { caption ->
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Button(onClick = onCorruptFile, enabled = hasDownloadedModel) { Text("Corrupt") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SampleScreenPreview() {
    val rows = listOf(
        ModelRow(SAMPLE_CATALOG[0], DownloadState.Available),
        ModelRow(
            SAMPLE_CATALOG[1],
            DownloadState.Downloading(
                fileIndex = 2,
                fileCount = 5,
                path = "pytorch_model.bin",
                bytesWritten = 4_000_000,
                fileBytes = 17_756_393,
            ),
        ),
        ModelRow(SAMPLE_CATALOG[2], DownloadState.WontFit(5_632_417_295, 1_000_000, 5_631_417_295)),
    )
    MaterialTheme {
        Surface {
            SampleScreen(
                state = SampleUiState(rows = rows, sabotage = SabotageState(lowDiskSimulationEnabled = true)),
                onDownload = {},
                onRecheck = {},
                onToggleLowDisk = {},
                onCorruptFile = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelRowStatesPreview() {
    val states = listOf(
        DownloadState.Available,
        DownloadState.CheckingSpace,
        DownloadState.WontFit(5_632_417_295, 1_000_000, 5_631_417_295),
        DownloadState.Downloading(2, 9, "pytorch_model.bin", 1_200_000, 2_514_146),
        DownloadState.Verifying("pytorch_model.bin"),
        DownloadState.Downloaded(cacheHit = false),
        DownloadState.Downloaded(cacheHit = true),
        DownloadState.Failed("HTTP 503 listing sshleifer/tiny-gpt2"),
    )
    MaterialTheme {
        Surface {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(states) { state ->
                    ModelRowCard(ModelRow(SAMPLE_CATALOG[0], state), onDownload = {}, onRecheck = {})
                }
            }
        }
    }
}
