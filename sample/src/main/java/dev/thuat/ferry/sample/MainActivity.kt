package dev.thuat.ferry.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * No Service, no WorkManager, no DI framework — just an Activity, a ViewModel, and Ferry called
 * directly. That is the whole point being demonstrated: the library imposes no architecture on its
 * host, so the host can be as small as this.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: SampleViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleScreen(
                        state = state,
                        onDownload = viewModel::download,
                        onRecheck = viewModel::recheck,
                        onToggleLowDisk = viewModel::setLowDiskSimulation,
                        onCorruptFile = viewModel::corruptADownloadedFile,
                    )
                }
            }
        }
    }
}
