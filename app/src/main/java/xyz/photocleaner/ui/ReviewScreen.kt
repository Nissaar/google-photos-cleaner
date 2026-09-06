package xyz.photocleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.photocleaner.data.CleanupMode
import xyz.photocleaner.data.Decision
import xyz.photocleaner.ui.theme.VerdictColors
import xyz.photocleaner.vm.AppViewModel
import xyz.photocleaner.vm.ReviewViewModel

/**
 * The commit step. Everything up to here has been local and reversible;
 * this is the only screen that changes anything in the Google account.
 */
@Composable
fun ReviewScreen(
    appVm: AppViewModel,
    onBack: () -> Unit,
    vm: ReviewViewModel = viewModel(),
) {
    val pending by vm.pending.collectAsState()
    val applied by vm.applied.collectAsState()
    val progress by vm.progress.collectAsState()
    val mode by appVm.mode.collectAsState()
    val albumName by appVm.albumName.collectAsState()

    var confirming by remember { mutableStateOf(false) }
    var showApplied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (showApplied) "Already deleted" else "Marked for deletion",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (showApplied) {
                        "${applied.size} in Google's trash · recoverable for 60 days"
                    } else {
                        "${pending.size} photos · nothing sent to Google yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (applied.isNotEmpty()) {
                TextButton(onClick = { showApplied = !showApplied }) {
                    Text(if (showApplied) "Pending" else "History")
                }
            }
        }

        if (progress.running) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${progress.done} of ${progress.total} — pacing requests to stay " +
                        "within Google's limits",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val list = if (showApplied) applied else pending

        Box(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                Text(
                    if (showApplied) "Nothing deleted yet."
                    else "Nothing marked for deletion.\nSwipe left on photos to add them here.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list, key = { it.dedupKey }) { decision ->
                        DecisionTile(
                            decision = decision,
                            removable = !showApplied,
                            onRemove = { vm.removeFromPending(decision) },
                        )
                    }
                }
            }
        }

        // ---- Commit bar ---------------------------------------------------
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                if (showApplied) {
                    OutlinedButton(
                        onClick = { vm.restore(applied) },
                        enabled = applied.isNotEmpty() && !progress.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Restore all ${applied.size} from trash")
                    }
                } else {
                    Text(
                        when (mode) {
                            CleanupMode.TRASH ->
                                "These will be moved to the Google Photos trash, where " +
                                    "they stay recoverable for 60 days."
                            CleanupMode.ALBUM ->
                                "These will be added to the album \"$albumName\". " +
                                    "Nothing is deleted — you delete them yourself in Google Photos."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { confirming = true },
                        enabled = pending.isNotEmpty() && !progress.running,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == CleanupMode.TRASH) {
                                VerdictColors.delete
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                    ) {
                        Text(
                            when (mode) {
                                CleanupMode.TRASH -> "Delete ${pending.size} from Google Photos"
                                CleanupMode.ALBUM -> "Add ${pending.size} to \"$albumName\""
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text(
                    if (mode == CleanupMode.TRASH) "Delete ${pending.size} photos?"
                    else "Add ${pending.size} photos to \"$albumName\"?",
                )
            },
            text = {
                Text(
                    if (mode == CleanupMode.TRASH) {
                        "They move to the Google Photos trash and are permanently removed " +
                            "after 60 days. You can restore them from the History tab, or " +
                            "from Google Photos itself, until then."
                    } else {
                        "The photos stay in your library. They will just be collected into " +
                            "the album so you can delete them yourself."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; vm.apply() }) {
                    Text(if (mode == CleanupMode.TRASH) "Delete" else "Add to album")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    progress.result?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(if (result.error == null) "Done" else "Partly finished") },
            text = {
                Text(
                    buildString {
                        when (result.mode) {
                            CleanupMode.TRASH -> append("${result.succeeded} moved to trash.")
                            CleanupMode.ALBUM ->
                                append("${result.succeeded} added to \"${result.albumName}\".")
                        }
                        if (result.failed > 0) append("\n${result.failed} could not be processed.")
                        result.error?.let { append("\n\n$it") }
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("OK") } },
        )
    }
}

@Composable
private fun DecisionTile(decision: Decision, removable: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    Box {
        Box(
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(
                        "${decision.thumbBaseUrl}=w300-h300-k-no" +
                            "?authuser=${xyz.photocleaner.Graph.session.authUser}",
                    )
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (removable) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Keep this one after all",
                    modifier = Modifier.padding(5.dp),
                )
            }
        }
    }
}
